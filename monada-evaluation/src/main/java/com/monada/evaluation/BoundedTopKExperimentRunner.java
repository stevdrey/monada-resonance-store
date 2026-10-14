package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.core.FrequencyVector;
import com.monada.core.ResonanceResult;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.evaluation.BoundedTopKExperimentReport.Decision;
import com.monada.evaluation.BoundedTopKExperimentReport.ParityCounts;
import com.monada.evaluation.BoundedTopKExperimentReport.PointResult;
import com.monada.evaluation.BoundedTopKExperimentReport.RetentionCounts;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import com.monada.index.ExactBoundedTopKSelector;
import com.monada.index.ExperimentalBoundedTopKLinearScanIndex;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.index.ResonanceIndex;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.VectorFormatProfile;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Evaluation-only harness for Issue #120: compares the experimental exact bounded top-K selector
 * against the production full-sort oracle over the same deterministic corpora, scale points, top-K
 * values, seed and dimensions as {@link ScanPhaseBreakdownRunner}.
 *
 * <p>Exact equivalence comes first: for every query and K it checks the selector against the
 * full-sort oracle (in production hash order and a seeded shuffle) and the experimental index
 * against {@link LinearScanResonanceIndex} (at the evaluation threshold and at an exact-boundary
 * threshold), counting mismatches rather than assuming parity. Only then does it time the isolated
 * selection phase on identical candidate lists and the end-to-end search. Any mismatch yields
 * {@link Decision#NEEDS_FIX}; the candidate is never enabled automatically.
 */
public final class BoundedTopKExperimentRunner {

    private static final double EVALUATION_THRESHOLD = Double.NEGATIVE_INFINITY;
    private static final int DEFAULT_DIMENSIONS = 128;
    private static final String ATOM_LOG = "atoms/segment-000001.log";
    private static final String VECTOR_FILE = "vectors/segment-000001.f32";

    /** Verbatim production comparator, so the oracle is the production full sort. */
    private static final Comparator<ResonanceResult> ORACLE_ORDER =
            Comparator.comparingDouble(ResonanceResult::score).reversed()
                    .thenComparing(result -> result.atom().id());

    private final String datasetName;
    private final EvaluationDataset seedDataset;
    private final List<Integer> scalePoints;
    private final List<Integer> topKs;
    private final int warmupCount;
    private final int repetitionCount;
    private final long seed;
    private final DeterministicScaleCorpusGenerator generator;
    private final AllocationMeter allocationMeter = AllocationMeter.create();
    private long sink;

    public BoundedTopKExperimentRunner() {
        this("expanded-technology", ExpandedTechnologyDataset.get(), ScanPhaseBreakdownRunner.DEFAULT_SCALE_POINTS,
                ScanPhaseBreakdownRunner.DEFAULT_TOP_KS, ScanPhaseBreakdownRunner.DEFAULT_WARMUP_COUNT,
                ScanPhaseBreakdownRunner.DEFAULT_REPETITION_COUNT, DeterministicScaleCorpusGenerator.DEFAULT_SEED);
    }

    public BoundedTopKExperimentRunner(
            String datasetName,
            EvaluationDataset seedDataset,
            List<Integer> scalePoints,
            List<Integer> topKs,
            int warmupCount,
            int repetitionCount,
            long seed) {
        this.datasetName = Objects.requireNonNull(datasetName, "datasetName");
        this.seedDataset = Objects.requireNonNull(seedDataset, "seedDataset");
        ScanPhaseBreakdownRunner.validateScaleConfig(seedDataset, scalePoints, warmupCount, repetitionCount);
        Objects.requireNonNull(topKs, "topKs");
        if (topKs.isEmpty()) {
            throw new IllegalArgumentException("topKs must not be empty");
        }
        for (int k : topKs) {
            if (k <= 0) {
                throw new IllegalArgumentException("topK must be > 0, got: " + k);
            }
        }
        if (new HashSet<>(topKs).size() != topKs.size()) {
            throw new IllegalArgumentException("topKs must contain distinct values");
        }
        this.scalePoints = List.copyOf(scalePoints);
        this.topKs = List.copyOf(topKs);
        this.warmupCount = warmupCount;
        this.repetitionCount = repetitionCount;
        this.seed = seed;
        this.generator = new DeterministicScaleCorpusGenerator(seed);
    }

    public BoundedTopKExperimentReport run(Path rootTempDir) throws IOException {
        Objects.requireNonNull(rootTempDir, "rootTempDir");
        var memoryOptions = new MonadaMemoryOptions(new LexicalEnrichmentPipeline(), false);
        var textNormalizer = memoryOptions.textNormalizer();
        var encoder = new SimpleFrequencyEncoder(DEFAULT_DIMENSIONS);

        var results = new ArrayList<PointResult>();
        for (int corpusSize : scalePoints) {
            var dataset = generator.generate(seedDataset, corpusSize);
            var scaleDir = rootTempDir.resolve("bounded-topk-scale-" + corpusSize);
            Files.createDirectories(scaleDir);

            // Setup (untimed): same persisted files and shared store pair as the #119 phase breakdown.
            LatencyScaleSweepRunner.seedStoreFiles(dataset, scaleDir, memoryOptions, textNormalizer, encoder);
            var atomStore = new FileAtomStore(scaleDir, ATOM_LOG);
            var vectorStore = FileFrequencyStore.openExisting(
                    scaleDir, VECTOR_FILE, VectorFormatProfile.currentFixedRaw(DEFAULT_DIMENSIONS));
            var production = new LinearScanResonanceIndex(atomStore, vectorStore);
            var experimental = new ExperimentalBoundedTopKLinearScanIndex(atomStore, vectorStore);

            var queryVectors = new ArrayList<FrequencyVector>();
            for (var query : dataset.queries()) {
                var normalized = textNormalizer.normalize(query.text());
                if (!normalized.enrichedText().isBlank()) {
                    queryVectors.add(encoder.encode(normalized.toWeightedText(memoryOptions.expansionOptions())));
                }
            }

            for (int k : topKs) {
                // Parity + retained counts (deterministic, untimed). The instrumented scan is itself
                // verified against production before its candidates are used as selector input.
                var shuffle = new Random(seed ^ ((long) corpusSize << 20) ^ k);
                var candidateLists = new ArrayList<List<ResonanceResult>>(queryVectors.size());
                long selectorChecks = 0, selectorMismatches = 0, searchChecks = 0, searchMismatches = 0;
                long candidates = 0, candidatePeak = 0, returned = 0;
                int maxCandidates = 0, maxPeak = 0;
                for (var qv : queryVectors) {
                    var trace = InstrumentedLinearScan.search(atomStore, vectorStore, qv, k, EVALUATION_THRESHOLD, true);
                    InstrumentedLinearScan.verifyParity(production, qv, k, EVALUATION_THRESHOLD, trace);
                    var sortedCandidates = trace.candidates();
                    var hashOrder = productionHashOrder(sortedCandidates);
                    candidateLists.add(hashOrder);

                    var oracle = oracleSelect(hashOrder, k);
                    var selection = ExactBoundedTopKSelector.select(hashOrder, k);
                    selectorChecks++;
                    if (!sameResults(oracle, selection.results())) {
                        selectorMismatches++;
                    }
                    var shuffled = new ArrayList<>(hashOrder);
                    Collections.shuffle(shuffled, shuffle);
                    selectorChecks++;
                    if (!sameResults(oracleSelect(shuffled, k), ExactBoundedTopKSelector.select(shuffled, k).results())) {
                        selectorMismatches++;
                    }

                    searchChecks++;
                    if (!sameSearch(production, experimental, qv, k, EVALUATION_THRESHOLD)) {
                        searchMismatches++;
                    }
                    if (!sortedCandidates.isEmpty()) {
                        var boundary = sortedCandidates.get(sortedCandidates.size() / 2).score();
                        searchChecks++;
                        if (!sameSearch(production, experimental, qv, k, boundary)) {
                            searchMismatches++;
                        }
                    }

                    candidates += hashOrder.size();
                    candidatePeak += selection.peakRetained();
                    returned += selection.results().size();
                    maxCandidates = Math.max(maxCandidates, hashOrder.size());
                    maxPeak = Math.max(maxPeak, selection.peakRetained());
                }
                var parity = new ParityCounts(queryVectors.size(), selectorChecks, selectorMismatches,
                        searchChecks, searchMismatches);
                var retention = new RetentionCounts(candidates, candidates, candidatePeak, maxCandidates, maxPeak,
                        returned);

                // Warm-up (unmeasured); alternate which arm runs first.
                for (int w = 0; w < warmupCount; w++) {
                    for (int q = 0; q < queryVectors.size(); q++) {
                        var qv = queryVectors.get(q);
                        var list = candidateLists.get(q);
                        if (w % 2 == 0) {
                            consume(oracleSelect(list, k));
                            consume(ExactBoundedTopKSelector.select(list, k).results());
                            consume(production.search(qv, k, EVALUATION_THRESHOLD));
                            consume(experimental.search(qv, k, EVALUATION_THRESHOLD));
                        } else {
                            consume(ExactBoundedTopKSelector.select(list, k).results());
                            consume(oracleSelect(list, k));
                            consume(experimental.search(qv, k, EVALUATION_THRESHOLD));
                            consume(production.search(qv, k, EVALUATION_THRESHOLD));
                        }
                    }
                }

                // Measured repetitions; the oracle/production arm runs first on even repetitions and
                // second on odd ones so neither arm systematically benefits from the other's cache warmth.
                var oracleSel = new ArrayList<Long>();
                var candidateSel = new ArrayList<Long>();
                var oracleAlloc = new ArrayList<Long>();
                var candidateAlloc = new ArrayList<Long>();
                var productionSearch = new ArrayList<Long>();
                var candidateSearch = new ArrayList<Long>();
                for (int rep = 0; rep < repetitionCount; rep++) {
                    for (int q = 0; q < queryVectors.size(); q++) {
                        var qv = queryVectors.get(q);
                        var list = candidateLists.get(q);
                        if (rep % 2 == 0) {
                            timeOracleSelection(list, k, oracleSel, oracleAlloc);
                            timeCandidateSelection(list, k, candidateSel, candidateAlloc);
                            productionSearch.add(timeSearch(production, qv, k));
                            candidateSearch.add(timeSearch(experimental, qv, k));
                        } else {
                            timeCandidateSelection(list, k, candidateSel, candidateAlloc);
                            timeOracleSelection(list, k, oracleSel, oracleAlloc);
                            candidateSearch.add(timeSearch(experimental, qv, k));
                            productionSearch.add(timeSearch(production, qv, k));
                        }
                    }
                }
                results.add(new PointResult(
                        corpusSize, k, queryVectors.size(), productionSearch.size(), parity, retention,
                        ScanPhaseBreakdownRunner.latency(oracleSel),
                        ScanPhaseBreakdownRunner.latency(candidateSel),
                        ScanPhaseBreakdownRunner.latency(productionSearch),
                        ScanPhaseBreakdownRunner.latency(candidateSearch),
                        allocationP50(oracleAlloc), allocationP50(candidateAlloc)));
            }
        }

        var decision = decide(results);
        return new BoundedTopKExperimentReport(datasetName, seed, DEFAULT_DIMENSIONS, scalePoints, topKs,
                warmupCount, repetitionCount, allocationMeter.supported(), results, decision,
                rationale(decision, results));
    }

    /**
     * Rebuilds the deduplicated candidates in the iteration order the production scan feeds its sort
     * (a {@link HashMap} keyed by atom id), so neither arm receives pre-sorted input.
     */
    static List<ResonanceResult> productionHashOrder(List<ResonanceResult> candidates) {
        var byAtomId = new HashMap<String, ResonanceResult>();
        for (var candidate : candidates) {
            byAtomId.put(candidate.atom().id(), candidate);
        }
        return List.copyOf(byAtomId.values());
    }

    /** The production full sort + truncate, applied to an explicit candidate list. */
    static List<ResonanceResult> oracleSelect(List<ResonanceResult> candidates, int topK) {
        var results = new ArrayList<>(candidates);
        results.sort(ORACLE_ORDER);
        if (results.size() > topK) {
            return List.copyOf(results.subList(0, topK));
        }
        return List.copyOf(results);
    }

    static boolean sameResults(List<ResonanceResult> expected, List<ResonanceResult> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).atom().id().equals(actual.get(i).atom().id())
                    || Double.doubleToLongBits(expected.get(i).score())
                    != Double.doubleToLongBits(actual.get(i).score())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSearch(ResonanceIndex production, ResonanceIndex candidate, FrequencyVector qv,
                                      int k, double threshold) throws IOException {
        return sameResults(production.search(qv, k, threshold), candidate.search(qv, k, threshold));
    }

    private void timeOracleSelection(List<ResonanceResult> list, int k, List<Long> nanos, List<Long> allocated) {
        long bytesBefore = allocationMeter.currentThreadAllocatedBytes();
        long start = System.nanoTime();
        var selected = oracleSelect(list, k);
        long elapsed = System.nanoTime() - start;
        long bytesAfter = allocationMeter.currentThreadAllocatedBytes();
        nanos.add(elapsed);
        allocated.add(bytesAfter - bytesBefore);
        consume(selected);
    }

    private void timeCandidateSelection(List<ResonanceResult> list, int k, List<Long> nanos, List<Long> allocated) {
        long bytesBefore = allocationMeter.currentThreadAllocatedBytes();
        long start = System.nanoTime();
        var selected = ExactBoundedTopKSelector.select(list, k).results();
        long elapsed = System.nanoTime() - start;
        long bytesAfter = allocationMeter.currentThreadAllocatedBytes();
        nanos.add(elapsed);
        allocated.add(bytesAfter - bytesBefore);
        consume(selected);
    }

    private long timeSearch(ResonanceIndex index, FrequencyVector qv, int k) throws IOException {
        long start = System.nanoTime();
        var selected = index.search(qv, k, EVALUATION_THRESHOLD);
        long elapsed = System.nanoTime() - start;
        consume(selected);
        return elapsed;
    }

    /** Keeps results observable so the JIT cannot drop the measured work. */
    private void consume(List<ResonanceResult> selected) {
        sink += selected.size();
        if (!selected.isEmpty()) {
            sink += selected.getFirst().atom().id().hashCode();
        }
    }

    /** Median allocated bytes, or {@link BoundedTopKExperimentReport#NOT_MEASURED} without accounting or samples. */
    private long allocationP50(List<Long> samples) {
        if (!allocationMeter.supported() || samples.isEmpty()) {
            return BoundedTopKExperimentReport.NOT_MEASURED;
        }
        var sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get((sorted.size() - 1) / 2);
    }

    /** Points at the largest scale, one per top-K arm. */
    private static List<PointResult> largestScale(List<PointResult> results) {
        int max = results.stream().mapToInt(PointResult::corpusSize).max().orElse(0);
        return results.stream().filter(r -> r.corpusSize() == max).toList();
    }

    private static boolean insufficientEvidence(List<PointResult> largest) {
        return largest.isEmpty() || largest.getFirst().corpusSize() < BoundedTopKExperimentReport.MIN_DECISION_SCALE
                || largest.stream().anyMatch(r -> r.sampleCount() == 0 || r.oracleSelection().p50Nanos() == 0
                || r.productionSearch().p50Nanos() == 0);
    }

    static Decision decide(List<PointResult> results) {
        if (results.stream().anyMatch(r -> r.parity().mismatches() > 0)) {
            return Decision.NEEDS_FIX;
        }
        var largest = largestScale(results);
        if (insufficientEvidence(largest)) {
            return Decision.NO_BENEFIT;
        }
        boolean beneficial = largest.stream().allMatch(r ->
                r.selectionRatio() <= BoundedTopKExperimentReport.SELECTION_SPEEDUP_RATIO
                        && r.candidateSearch().p50Nanos() <= r.productionSearch().p50Nanos());
        return beneficial ? Decision.RETAIN_EXPERIMENT : Decision.NO_BENEFIT;
    }

    static String rationale(Decision decision, List<PointResult> results) {
        if (decision == Decision.NEEDS_FIX) {
            long mismatches = results.stream().mapToLong(r -> r.parity().mismatches()).sum();
            return mismatches + " parity mismatch(es) against the full-sort oracle; exact equivalence takes"
                    + " precedence over performance, so the candidate is rejected and must not be enabled.";
        }
        var largest = largestScale(results);
        if (insufficientEvidence(largest)) {
            return "Parity holds, but there is insufficient evidence for a benefit: need timing samples and a"
                    + " largest scale point >= " + BoundedTopKExperimentReport.MIN_DECISION_SCALE + ".";
        }
        var sb = new StringBuilder("Parity holds for every query and K (selector and search checks). ");
        for (var r : largest) {
            double searchDelta = 1.0 - r.searchRatio();
            sb.append(String.format(Locale.ROOT,
                    "N=%d K=%d: selection p50 %.1f us -> %.1f us (%.2fx of oracle), end-to-end search p50"
                            + " %.1f us -> %.1f us (%+.1f%%), retained %d -> %d candidates/query max. ",
                    r.corpusSize(), r.topK(), r.oracleSelection().p50Nanos() / 1000.0,
                    r.candidateSelection().p50Nanos() / 1000.0, r.selectionRatio(),
                    r.productionSearch().p50Nanos() / 1000.0, r.candidateSearch().p50Nanos() / 1000.0,
                    -searchDelta * 100.0, r.retention().maxCandidatesPerQuery(),
                    r.retention().maxCandidatePeakRetainedPerQuery()));
        }
        sb.append(decision == Decision.RETAIN_EXPERIMENT
                ? "The bounded selector is at most " + (int) (BoundedTopKExperimentReport.SELECTION_SPEEDUP_RATIO * 100)
                + "% of the oracle selection cost and end-to-end search is not slower for every K at the largest"
                + " scale, so the experiment is retained for a separate, explicitly approved adoption decision."
                : "The bounded selector does not meet both criteria (selection p50 <= "
                + (int) (BoundedTopKExperimentReport.SELECTION_SPEEDUP_RATIO * 100)
                + "% of the oracle and end-to-end search p50 not slower) for every K at the largest scale.");
        sb.append(" The selector only replaces sort+truncate; vector load and scoring still bound end-to-end"
                + " latency, so a smaller K does not guarantee lower end-to-end latency. Timings are exploratory.");
        return sb.toString();
    }

    /**
     * Optional per-thread allocation accounting ({@code com.sun.management.ThreadMXBean}). Read-only: when
     * accounting is unsupported or disabled the figures are reported as not measured, never force-enabled.
     */
    record AllocationMeter(com.sun.management.ThreadMXBean bean) {

        static AllocationMeter create() {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                    && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
                return new AllocationMeter(bean);
            }
            return new AllocationMeter(null);
        }

        boolean supported() {
            return bean != null;
        }

        long currentThreadAllocatedBytes() {
            return bean == null ? 0L : bean.getCurrentThreadAllocatedBytes();
        }
    }
}
