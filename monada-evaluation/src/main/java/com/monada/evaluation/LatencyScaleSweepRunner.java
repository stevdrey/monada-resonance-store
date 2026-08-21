package com.monada.evaluation;

import com.monada.api.ExactQueryKeyStrategy;
import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.FrequencyEncoder;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.TextNormalizer;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Harness for running scaled latency and recall evaluation across multiple corpus sizes
 * and top-K configurations.
 *
 * <p>Preserves deterministic linear-scan baseline while capturing structural scan counts,
 * best-effort timing percentiles, retrieval quality, ranking stability, and an optimization
 * decision gate signal.
 */
public final class LatencyScaleSweepRunner {

    public static final List<Integer> DEFAULT_SCALE_POINTS = List.of(100, 1_000, 5_000, 10_000);
    public static final List<Integer> DEFAULT_TOP_KS = List.of(1, 5);
    public static final int DEFAULT_WARMUP_COUNT = 2;
    public static final int DEFAULT_REPETITION_COUNT = 5;
    private static final double EVALUATION_THRESHOLD = Double.NEGATIVE_INFINITY;
    private static final int DEFAULT_DIMENSIONS = 128;

    private final EvaluationDataset seedDataset;
    private final List<Integer> scalePoints;
    private final List<Integer> topKs;
    private final int warmupCount;
    private final int repetitionCount;
    private final long seed;
    private final DeterministicScaleCorpusGenerator generator;
    private final EvaluationRunner evaluationRunner;
    private final EvaluationComparator comparator;

    public LatencyScaleSweepRunner() {
        this(ExpandedTechnologyDataset.get(),
                DEFAULT_SCALE_POINTS,
                DEFAULT_TOP_KS,
                DEFAULT_WARMUP_COUNT,
                DEFAULT_REPETITION_COUNT,
                DeterministicScaleCorpusGenerator.DEFAULT_SEED);
    }

    public LatencyScaleSweepRunner(
            EvaluationDataset seedDataset,
            List<Integer> scalePoints,
            List<Integer> topKs,
            int warmupCount,
            int repetitionCount,
            long seed) {
        this.seedDataset = Objects.requireNonNull(seedDataset, "seedDataset");
        Objects.requireNonNull(scalePoints, "scalePoints");
        Objects.requireNonNull(topKs, "topKs");
        if (scalePoints.isEmpty()) {
            throw new IllegalArgumentException("scalePoints must not be empty");
        }
        if (topKs.isEmpty()) {
            throw new IllegalArgumentException("topKs must not be empty");
        }
        for (int sp : scalePoints) {
            if (sp < seedDataset.atoms().size()) {
                throw new IllegalArgumentException(
                        "scale point " + sp + " must be >= seed dataset size " + seedDataset.atoms().size());
            }
        }
        for (int k : topKs) {
            if (k <= 0) {
                throw new IllegalArgumentException("topK must be > 0, got: " + k);
            }
        }
        if (warmupCount < 0) {
            throw new IllegalArgumentException("warmupCount must be >= 0, got: " + warmupCount);
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0, got: " + repetitionCount);
        }
        this.scalePoints = List.copyOf(scalePoints);
        this.topKs = List.copyOf(topKs);
        this.warmupCount = warmupCount;
        this.repetitionCount = repetitionCount;
        this.seed = seed;
        this.generator = new DeterministicScaleCorpusGenerator(seed);
        this.evaluationRunner = new EvaluationRunner(EvaluationRunner.DEFAULT_KS);
        this.comparator = new EvaluationComparator();
    }

    public LatencyScaleSweepReport run(Path rootTempDir) throws IOException {
        Objects.requireNonNull(rootTempDir, "rootTempDir");
        var memoryOptions = new MonadaMemoryOptions(new LexicalEnrichmentPipeline(), false);
        var textNormalizer = memoryOptions.textNormalizer();
        var encoder = new SimpleFrequencyEncoder(DEFAULT_DIMENSIONS);

        // Run seed baseline without distractors to compare ranking stability.
        var seedBaselineDir = rootTempDir.resolve("seed-baseline");
        Files.createDirectories(seedBaselineDir);
        var seedMemory = MonadaMemory.open(seedBaselineDir, memoryOptions);
        var seedSeeding = evaluationRunner.seedAtoms(seedDataset, seedMemory);
        var seedBaselineReport = evaluationRunner.evaluate(seedDataset, seedMemory, seedSeeding.idToLabel(), memoryOptions);

        var pointResults = new ArrayList<LatencyScalePointResult>();

        for (int corpusSize : scalePoints) {
            var scaledDataset = generator.generate(seedDataset, corpusSize);
            var scaleDir = rootTempDir.resolve("scale-" + corpusSize);
            Files.createDirectories(scaleDir);

            var memory = MonadaMemory.open(scaleDir, memoryOptions);
            var seeding = evaluationRunner.seedAtoms(scaledDataset, memory);
            var idToLabel = seeding.idToLabel();

            for (int k : topKs) {
                var pointResult = evaluateScalePoint(
                        scaledDataset,
                        memory,
                        idToLabel,
                        corpusSize,
                        k,
                        textNormalizer,
                        encoder,
                        memoryOptions,
                        seedBaselineReport
                );
                pointResults.add(pointResult);
            }
        }

        var decision = evaluateDecision(pointResults);
        var rationale = buildDecisionRationale(decision, pointResults);

        return new LatencyScaleSweepReport(
                "expanded-technology",
                seed,
                scalePoints,
                topKs,
                warmupCount,
                repetitionCount,
                pointResults,
                decision,
                rationale
        );
    }

    private LatencyScalePointResult evaluateScalePoint(
            EvaluationDataset dataset,
            MonadaMemory memory,
            Map<String, String> idToLabel,
            int corpusSize,
            int topK,
            TextNormalizer textNormalizer,
            FrequencyEncoder encoder,
            MonadaMemoryOptions memoryOptions,
            EvaluationReport seedBaselineReport) {

        int queryCount = dataset.queries().size();

        // 1. Warm-up iterations (unmeasured)
        for (int w = 0; w < warmupCount; w++) {
            for (var query : dataset.queries()) {
                memory.resonate(query.text())
                        .topK(topK)
                        .threshold(EVALUATION_THRESHOLD)
                        .execute();
            }
        }

        // 2. Measured repetitions
        var totalLatencies = new ArrayList<Long>(queryCount * Math.max(1, repetitionCount));
        var encodeLatencies = new ArrayList<Long>(queryCount * Math.max(1, repetitionCount));

        int effectiveReps = Math.max(1, repetitionCount);
        for (int rep = 0; rep < effectiveReps; rep++) {
            for (var query : dataset.queries()) {
                // Standalone query encoding diagnostic pass (independent from memory execution)
                var startEncode = System.nanoTime();
                var normalized = textNormalizer.normalize(query.text());
                if (!normalized.enrichedText().isBlank()) {
                    encoder.encode(normalized.toWeightedText(memoryOptions.expansionOptions()));
                }
                var encodeNanos = System.nanoTime() - startEncode;

                // End-to-end memory recall query pass
                var startQuery = System.nanoTime();
                memory.resonate(query.text())
                        .topK(topK)
                        .threshold(EVALUATION_THRESHOLD)
                        .execute();
                var queryNanos = System.nanoTime() - startQuery;

                if (repetitionCount > 0) {
                    totalLatencies.add(queryNanos);
                    encodeLatencies.add(encodeNanos);
                }
            }
        }

        var timing = ScaleTimingStatistics.from(
                totalLatencies,
                encodeLatencies,
                queryCount,
                repetitionCount
        );

        // 3. Evaluation quality report & structural metrics
        var queryEvaluations = new ArrayList<QueryEvaluation>(queryCount);
        var precisionSums = new TreeMap<Integer, Double>();
        var recallSums = new TreeMap<Integer, Double>();
        var hitSums = new TreeMap<Integer, Double>();
        for (var kVal : EvaluationRunner.DEFAULT_KS) {
            precisionSums.put(kVal, 0.0);
            recallSums.put(kVal, 0.0);
            hitSums.put(kVal, 0.0);
        }
        var reciprocalRankSum = 0.0;
        long totalScanned = 0;
        long totalReturned = 0;

        for (var query : dataset.queries()) {
            var recall = memory.resonate(query.text())
                    .topK(topK)
                    .threshold(EVALUATION_THRESHOLD)
                    .execute();

            var rankedLabels = new ArrayList<String>(recall.results().size());
            for (var res : recall.results()) {
                rankedLabels.add(idToLabel.getOrDefault(res.atom().id(), res.atom().id()));
            }

            var isBlank = textNormalizer.normalize(query.text()).enrichedText().isBlank();
            int scannedForQuery = isBlank ? 0 : corpusSize;
            totalScanned += scannedForQuery;
            totalReturned += recall.results().size();

            var precisionByK = new TreeMap<Integer, Double>();
            var recallByK = new TreeMap<Integer, Double>();
            var hitByK = new TreeMap<Integer, Double>();
            for (var kVal : EvaluationRunner.DEFAULT_KS) {
                var p = PrecisionAtK.compute(query.expectedLabels(), rankedLabels, kVal);
                var r = RecallAtK.compute(query.expectedLabels(), rankedLabels, kVal);
                var h = HitAtK.compute(query.expectedLabels(), rankedLabels, kVal);
                precisionByK.put(kVal, p);
                recallByK.put(kVal, r);
                hitByK.put(kVal, h);
                precisionSums.merge(kVal, p, Double::sum);
                recallSums.merge(kVal, r, Double::sum);
                hitSums.merge(kVal, h, Double::sum);
            }
            double rr = ReciprocalRank.compute(query.expectedLabels(), rankedLabels);
            reciprocalRankSum += rr;

            queryEvaluations.add(new QueryEvaluation(
                    query.text(),
                    query.expectedLabels(),
                    rankedLabels,
                    precisionByK,
                    recallByK,
                    hitByK,
                    rr,
                    QueryKeyDiagnostic.withoutFeedback(query.text(), ExactQueryKeyStrategy.class.getSimpleName())));
        }

        var avgPrecision = average(precisionSums, queryCount);
        var avgRecall = average(recallSums, queryCount);
        var avgHit = average(hitSums, queryCount);
        var mrr = queryCount == 0 ? 0.0 : reciprocalRankSum / queryCount;
        var evalReport = new EvaluationReport(queryEvaluations, avgPrecision, avgRecall, avgHit, mrr);

        double scanFraction = (corpusSize == 0 || queryCount == 0)
                ? 0.0
                : (double) totalScanned / ((long) corpusSize * queryCount);

        // 4. Compare ranking changes vs unexpanded seed baseline
        int improved = 0;
        int maintained = 0;
        int degraded = 0;
        var shifts = new ArrayList<ScaleRankingShift>();
        if (seedBaselineReport != null && seedBaselineReport.queryResults().size() == queryCount) {
            for (int i = 0; i < queryCount; i++) {
                var baseQuery = seedBaselineReport.queryResults().get(i);
                var candidateQuery = evalReport.queryResults().get(i);
                int baseRank = firstExpectedRank(baseQuery.expectedLabels(), baseQuery.returnedLabels());
                int candidateRank = firstExpectedRank(candidateQuery.expectedLabels(), candidateQuery.returnedLabels());
                var change = classifyQuery(baseQuery, candidateQuery);
                switch (change) {
                    case IMPROVED -> {
                        improved++;
                        shifts.add(new ScaleRankingShift(baseQuery.queryText(), baseQuery.expectedLabels(),
                                baseRank, candidateRank, change));
                    }
                    case MAINTAINED -> maintained++;
                    case DEGRADED -> {
                        degraded++;
                        shifts.add(new ScaleRankingShift(baseQuery.queryText(), baseQuery.expectedLabels(),
                                baseRank, candidateRank, change));
                    }
                }
            }
        } else {
            maintained = queryCount;
        }

        return new LatencyScalePointResult(
                corpusSize,
                queryCount,
                topK,
                warmupCount,
                repetitionCount,
                totalScanned,
                scanFraction,
                totalReturned,
                timing,
                evalReport,
                improved,
                maintained,
                degraded,
                shifts
        );
    }

    private RankingChange classifyQuery(QueryEvaluation before, QueryEvaluation after) {
        if (before.returnedLabels().equals(after.returnedLabels())) {
            return RankingChange.MAINTAINED;
        }
        int beforeRank = firstExpectedRank(before.expectedLabels(), before.returnedLabels());
        int afterRank = firstExpectedRank(after.expectedLabels(), after.returnedLabels());
        if (afterRank < beforeRank) {
            return RankingChange.IMPROVED;
        }
        if (afterRank > beforeRank) {
            return RankingChange.DEGRADED;
        }
        return RankingChange.MAINTAINED;
    }

    private static int firstExpectedRank(Set<String> expected, List<String> returned) {
        for (int i = 0; i < returned.size(); i++) {
            if (expected.contains(returned.get(i))) {
                return i + 1;
            }
        }
        return Integer.MAX_VALUE;
    }

    private Map<Integer, Double> average(Map<Integer, Double> sums, int n) {
        var averages = new TreeMap<Integer, Double>();
        for (var e : sums.entrySet()) {
            averages.put(e.getKey(), n == 0 ? 0.0 : e.getValue() / n);
        }
        return averages;
    }

    /**
     * Evaluates the optimization decision gate strictly from structural scan invariants
     * and relative scale-curve behavior.
     */
    ScaleOptimizationDecision evaluateDecision(List<LatencyScalePointResult> results) {
        if (results.isEmpty()) {
            return ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE;
        }

        int maxScale = results.stream().mapToInt(LatencyScalePointResult::corpusSize).max().orElse(0);
        int minScale = results.stream().mapToInt(LatencyScalePointResult::corpusSize).min().orElse(0);

        // 1. Scale threshold check: multi-thousand corpus sizes required
        if (maxScale < 1_000 || maxScale == minScale) {
            return ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE;
        }

        // 2. Structural scan invariant: full corpus scan hypothesis
        boolean fullScanAcrossAll = results.stream().allMatch(r -> r.scanFraction() >= 0.99);
        if (!fullScanAcrossAll) {
            return ScaleOptimizationDecision.OPTIMIZATION_NOT_YET_JUSTIFIED;
        }

        // 3. Relative scale-curve check: query latency should scale with corpus size
        var largest = results.stream()
                .filter(r -> r.corpusSize() == maxScale)
                .max(Comparator.comparingLong(r -> r.timing().avgNanos()))
                .orElse(null);

        var smallest = results.stream()
                .filter(r -> r.corpusSize() == minScale)
                .min(Comparator.comparingLong(r -> r.timing().avgNanos()))
                .orElse(null);

        if (largest == null || smallest == null) {
            return ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE;
        }

        long largestLatency = largest.timing().avgNanos();
        long smallestLatency = smallest.timing().avgNanos();
        long encodeDiagnostic = largest.timing().avgEncodeNanos();

        // If latency didn't grow or regressed despite larger corpus, timing evidence is contradictory/noisy
        if (largestLatency <= smallestLatency) {
            return ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE;
        }

        // If largest scale latency remains small and comparable to standalone encode time (less than 2x),
        // scan is not yet a dominating bottleneck
        if (encodeDiagnostic > 0 && largestLatency <= 2 * encodeDiagnostic) {
            return ScaleOptimizationDecision.OPTIMIZATION_NOT_YET_JUSTIFIED;
        }

        // Relative scale growth confirmed with full structural scan
        return ScaleOptimizationDecision.BOUNDED_EXACT_TOP_K_EXPERIMENT_JUSTIFIED;
    }

    private String buildDecisionRationale(
            ScaleOptimizationDecision decision,
            List<LatencyScalePointResult> results) {
        int maxScale = results.stream().mapToInt(LatencyScalePointResult::corpusSize).max().orElse(0);
        return switch (decision) {
            case INCONCLUSIVE_NEEDS_LARGER_SCALE ->
                    "Evaluated scale points (max N=" + maxScale
                            + ") are below the multi-thousand scale threshold or have ambiguous curve data to confirm bottlenecks.";
            case OPTIMIZATION_NOT_YET_JUSTIFIED ->
                    "Structural scan fraction did not match full scan or end-to-end query latency remained comparable to standalone encoding.";
            case BOUNDED_EXACT_TOP_K_EXPERIMENT_JUSTIFIED ->
                    "Structural scan fraction is 1.0 (scanned candidates = N) regardless of requested top-K. "
                            + "At scale (N=" + maxScale + "), candidate scoring and full sorting dominate query latency. "
                            + "An exact bounded top-K scan experiment (e.g. bounded priority queue / threshold bounding) is justified.";
        };
    }
}
