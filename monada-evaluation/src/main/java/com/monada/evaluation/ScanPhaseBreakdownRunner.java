package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.core.FrequencyVector;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.evaluation.ScanPhaseBreakdownReport.Decision;
import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.ScanPhaseBreakdownReport.PointResult;
import com.monada.evaluation.ScanPhaseBreakdownReport.StructuralCounts;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.VectorFormatProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Evaluation-only harness that isolates the cost centers of linear-scan recall (atom load, vector
 * load, cosine scoring, dedup, full sort, truncation) over the same deterministic corpora and
 * benchmark semantics as {@link LatencyScaleSweepRunner}.
 *
 * <p>It never touches the production path: it runs an {@link InstrumentedLinearScan} next to a
 * real {@link LinearScanResonanceIndex} over the same persisted files and verifies parity for every
 * measured query before reporting anything.
 */
public final class ScanPhaseBreakdownRunner {

    public static final List<Integer> DEFAULT_SCALE_POINTS = LatencyScaleSweepRunner.DEFAULT_SCALE_POINTS;
    public static final List<Integer> DEFAULT_TOP_KS = LatencyScaleSweepRunner.DEFAULT_TOP_KS;
    public static final int DEFAULT_WARMUP_COUNT = LatencyScaleSweepRunner.DEFAULT_WARMUP_COUNT;
    public static final int DEFAULT_REPETITION_COUNT = LatencyScaleSweepRunner.DEFAULT_REPETITION_COUNT;

    private static final double EVALUATION_THRESHOLD = Double.NEGATIVE_INFINITY;
    private static final int DEFAULT_DIMENSIONS = 128;
    private static final String ATOM_LOG = "atoms/segment-000001.log";
    private static final String VECTOR_FILE = "vectors/segment-000001.f32";

    private final String datasetName;
    private final EvaluationDataset seedDataset;
    private final List<Integer> scalePoints;
    private final List<Integer> topKs;
    private final int warmupCount;
    private final int repetitionCount;
    private final long seed;
    private final DeterministicScaleCorpusGenerator generator;

    public ScanPhaseBreakdownRunner() {
        this("expanded-technology", ExpandedTechnologyDataset.get(), DEFAULT_SCALE_POINTS, DEFAULT_TOP_KS,
                DEFAULT_WARMUP_COUNT, DEFAULT_REPETITION_COUNT, DeterministicScaleCorpusGenerator.DEFAULT_SEED);
    }

    public ScanPhaseBreakdownRunner(
            String datasetName,
            EvaluationDataset seedDataset,
            List<Integer> scalePoints,
            List<Integer> topKs,
            int warmupCount,
            int repetitionCount,
            long seed) {
        this.datasetName = Objects.requireNonNull(datasetName, "datasetName");
        this.seedDataset = Objects.requireNonNull(seedDataset, "seedDataset");
        validateScaleConfig(seedDataset, scalePoints, warmupCount, repetitionCount);
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

    /**
     * Shared scale-sweep configuration checks (also used by {@link VectorLoadBreakdownRunner}): non-empty,
     * distinct scale points no smaller than the seed dataset, and non-negative warm-up/repetition counts.
     */
    static void validateScaleConfig(
            EvaluationDataset seedDataset, List<Integer> scalePoints, int warmupCount, int repetitionCount) {
        Objects.requireNonNull(scalePoints, "scalePoints");
        if (scalePoints.isEmpty()) {
            throw new IllegalArgumentException("scalePoints must not be empty");
        }
        if (new HashSet<>(scalePoints).size() != scalePoints.size()) {
            throw new IllegalArgumentException("scalePoints must contain distinct values");
        }
        for (int sp : scalePoints) {
            if (sp < seedDataset.atoms().size()) {
                throw new IllegalArgumentException(
                        "scale point " + sp + " must be >= seed dataset size " + seedDataset.atoms().size());
            }
        }
        if (warmupCount < 0) {
            throw new IllegalArgumentException("warmupCount must be >= 0, got: " + warmupCount);
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0, got: " + repetitionCount);
        }
    }

    public ScanPhaseBreakdownReport run(Path rootTempDir) throws IOException {
        Objects.requireNonNull(rootTempDir, "rootTempDir");
        var memoryOptions = new MonadaMemoryOptions(new LexicalEnrichmentPipeline(), false);
        var textNormalizer = memoryOptions.textNormalizer();
        var encoder = new SimpleFrequencyEncoder(DEFAULT_DIMENSIONS);

        var results = new ArrayList<PointResult>();
        for (int corpusSize : scalePoints) {
            var dataset = generator.generate(seedDataset, corpusSize);
            var scaleDir = rootTempDir.resolve("phase-scale-" + corpusSize);
            Files.createDirectories(scaleDir);

            // Setup (untimed): write the same persisted files as the #82 sweep, then open one store
            // pair shared by the production index and the instrumented scan so both see the same cache state.
            LatencyScaleSweepRunner.seedStoreFiles(dataset, scaleDir, memoryOptions, textNormalizer, encoder);
            var atomStore = new FileAtomStore(scaleDir, ATOM_LOG);
            var vectorStore = FileFrequencyStore.openExisting(
                    scaleDir, VECTOR_FILE, VectorFormatProfile.currentFixedRaw(DEFAULT_DIMENSIONS));
            var production = new LinearScanResonanceIndex(atomStore, vectorStore);

            // Setup (untimed): encode each non-blank query once, as the production path would.
            var queryVectors = new ArrayList<FrequencyVector>();
            for (var query : dataset.queries()) {
                var normalized = textNormalizer.normalize(query.text());
                if (!normalized.enrichedText().isBlank()) {
                    queryVectors.add(encoder.encode(normalized.toWeightedText(memoryOptions.expansionOptions())));
                }
            }

            for (int k : topKs) {
                // Parity + structural counts (deterministic, one pass).
                long atoms = 0, visited = 0, orphans = 0, cosine = 0, candidates = 0, returned = 0;
                long sortModel = 0, topKModel = 0;
                for (var qv : queryVectors) {
                    var trace = InstrumentedLinearScan.search(atomStore, vectorStore, qv, k, EVALUATION_THRESHOLD, true);
                    InstrumentedLinearScan.verifyParity(production, qv, k, EVALUATION_THRESHOLD, trace);
                    atoms += trace.atomsLoaded();
                    visited += trace.vectorsVisited();
                    orphans += trace.orphanVectors();
                    cosine += trace.cosineEvaluations();
                    candidates += trace.candidateCount();
                    returned += trace.resultsReturned();
                    sortModel += log2Ceil(trace.candidateCount()) * trace.candidateCount();
                    topKModel += log2Ceil(k + 1L) * trace.candidateCount();
                }
                var counts = new StructuralCounts(atoms, visited, orphans, cosine, candidates, returned,
                        sortModel, topKModel);

                // Warm-up (unmeasured); alternate which scan runs first.
                for (int w = 0; w < warmupCount; w++) {
                    for (var qv : queryVectors) {
                        if (w % 2 == 0) {
                            production.search(qv, k, EVALUATION_THRESHOLD);
                            InstrumentedLinearScan.search(atomStore, vectorStore, qv, k, EVALUATION_THRESHOLD, false);
                        } else {
                            InstrumentedLinearScan.search(atomStore, vectorStore, qv, k, EVALUATION_THRESHOLD, false);
                            production.search(qv, k, EVALUATION_THRESHOLD);
                        }
                    }
                }

                // Measured repetitions; production runs first on even repetitions and second on odd ones
                // so neither path systematically benefits from the other's cache warmth.
                var prod = new ArrayList<Long>();
                var traces = new ArrayList<InstrumentedLinearScan.PhaseTrace>();
                for (int rep = 0; rep < repetitionCount; rep++) {
                    for (var qv : queryVectors) {
                        InstrumentedLinearScan.PhaseTrace trace;
                        if (rep % 2 == 0) {
                            prod.add(timeProduction(production, qv, k));
                            trace = InstrumentedLinearScan.search(atomStore, vectorStore, qv, k, EVALUATION_THRESHOLD, false);
                        } else {
                            trace = InstrumentedLinearScan.search(atomStore, vectorStore, qv, k, EVALUATION_THRESHOLD, false);
                            prod.add(timeProduction(production, qv, k));
                        }
                        traces.add(trace);
                    }
                }
                results.add(new PointResult(
                        corpusSize, k, queryVectors.size(), prod.size(),
                        latency(prod),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::atomLoadNanos),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::vectorLoadNanos),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::scoringNanos),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::dedupNanos),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::sortNanos),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::truncateNanos),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::totalNanos),
                        phase(traces, InstrumentedLinearScan.PhaseTrace::sumOfPhasesNanos),
                        counts));
            }
        }

        var decision = decide(results);
        return new ScanPhaseBreakdownReport(datasetName, seed, DEFAULT_DIMENSIONS, scalePoints, topKs,
                warmupCount, repetitionCount, results, decision, rationale(decision, results));
    }

    private static long timeProduction(LinearScanResonanceIndex production, FrequencyVector qv, int k)
            throws IOException {
        long start = System.nanoTime();
        production.search(qv, k, EVALUATION_THRESHOLD);
        return System.nanoTime() - start;
    }

    private static PhaseLatency phase(
            List<InstrumentedLinearScan.PhaseTrace> traces, ToLongFunction<InstrumentedLinearScan.PhaseTrace> field) {
        var samples = new ArrayList<Long>(traces.size());
        for (var trace : traces) {
            samples.add(field.applyAsLong(trace));
        }
        return latency(samples);
    }

    /** Median and p95 of wall-clock samples; shared with {@link VectorLoadBreakdownRunner}. */
    static PhaseLatency latency(List<Long> samples) {
        var stats = ScaleTimingStatistics.from(samples, List.of(), 0, 0);
        return new PhaseLatency(stats.medianNanos(), stats.p95Nanos());
    }

    private static long log2Ceil(long n) {
        return n <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(n - 1);
    }

    /** Points at the largest scale, one per top-K arm. */
    private static List<PointResult> largestScale(List<PointResult> results) {
        int max = results.stream().mapToInt(PointResult::corpusSize).max().orElse(0);
        return results.stream().filter(r -> r.corpusSize() == max).toList();
    }

    static Decision decide(List<PointResult> results) {
        var largest = largestScale(results);
        if (largest.isEmpty() || largest.getFirst().corpusSize() < ScanPhaseBreakdownReport.MIN_DECISION_SCALE
                || largest.stream().anyMatch(r -> r.sampleCount() == 0 || r.sumOfPhases().p50Nanos() == 0)) {
            return Decision.INCONCLUSIVE;
        }
        boolean material = largest.stream()
                .allMatch(r -> r.share(r.sort()) >= ScanPhaseBreakdownReport.SORT_MATERIAL_SHARE);
        return material ? Decision.BOUNDED_TOP_K_PROTOTYPE_JUSTIFIED
                : Decision.BOUNDED_TOP_K_PROTOTYPE_NOT_JUSTIFIED;
    }

    static String rationale(Decision decision, List<PointResult> results) {
        if (decision == Decision.INCONCLUSIVE) {
            return "Insufficient evidence: need timing samples and a largest scale point >= "
                    + ScanPhaseBreakdownReport.MIN_DECISION_SCALE + ".";
        }
        var sb = new StringBuilder();
        for (var r : largestScale(results)) {
            double sortShare = r.share(r.sort());
            double ceiling = sortShare >= 1.0 ? Double.POSITIVE_INFINITY : 1.0 / (1.0 - sortShare);
            sb.append(String.format(java.util.Locale.ROOT,
                    "N=%d K=%d: full sort %.1f%%, vector load %.1f%%, scoring %.1f%%, dedup %.1f%% of the median"
                            + " sum of phases (Amdahl ceiling for removing the whole sort: %.2fx). ",
                    r.corpusSize(), r.topK(), sortShare * 100, r.share(r.vectorLoad()) * 100,
                    r.share(r.scoring()) * 100, r.share(r.dedup()) * 100, ceiling));
        }
        sb.append(decision == Decision.BOUNDED_TOP_K_PROTOTYPE_JUSTIFIED
                ? "Sorting is at least " + (int) (ScanPhaseBreakdownReport.SORT_MATERIAL_SHARE * 100)
                + "% of query-time work for every K at the largest scale, so an exact bounded top-K prototype is justified"
                + " (it still must preserve score desc / id asc ties and dedup-by-id)."
                : "Sorting is below " + (int) (ScanPhaseBreakdownReport.SORT_MATERIAL_SHARE * 100)
                + "% at the largest scale for at least one K, so an exact bounded top-K prototype alone cannot recover"
                + " most of the latency; the dominant phases above should be addressed first.");
        sb.append(" Shares come from an instrumented equivalent with timer and split-pass overhead; treat as exploratory.");
        return sb.toString();
    }
}
