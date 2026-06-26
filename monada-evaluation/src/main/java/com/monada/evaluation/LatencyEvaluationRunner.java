package com.monada.evaluation;

import com.monada.api.FeedbackQueryKeyStrategy;
import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.LexicalEnrichmentPipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs text-recall evaluation while capturing latency and scan diagnostics.
 *
 * <p>This runner is intentionally separate from {@link EvaluationRunner} so that
 * the profiling mode is opt-in and cannot silently alter the protected quality
 * baseline. It mirrors the same seeding, ranking, and metric computation, then
 * adds deterministic structural scan metrics and best-effort timing.
 *
 * <p>Timing is measured with {@link System#nanoTime()} around the full
 * {@code memory.resonate(...).execute()} call, including encoding and search.
 * It is reported, not used as a strict CI threshold.
 */
public final class LatencyEvaluationRunner {

    public static final List<Integer> DEFAULT_KS = EvaluationRunner.DEFAULT_KS;
    private static final double EVALUATION_THRESHOLD = Double.NEGATIVE_INFINITY;

    private final EvaluationRunner evaluationRunner;
    private final List<Integer> ks;

    public LatencyEvaluationRunner() {
        this(new EvaluationRunner(), DEFAULT_KS);
    }

    public LatencyEvaluationRunner(EvaluationRunner evaluationRunner) {
        this(evaluationRunner, DEFAULT_KS);
    }

    public LatencyEvaluationRunner(EvaluationRunner evaluationRunner, List<Integer> ks) {
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
        Objects.requireNonNull(ks, "ks");
        if (ks.isEmpty()) {
            throw new IllegalArgumentException("ks must not be empty");
        }
        Set<Integer> seen = new HashSet<>();
        for (int k : ks) {
            if (k <= 0) {
                throw new IllegalArgumentException("k values must be > 0, got: " + k);
            }
            if (!seen.add(k)) {
                throw new IllegalArgumentException("k values must be unique, duplicate: " + k);
            }
        }
        this.ks = List.copyOf(ks);
    }

    public LatencyEvaluationReport run(EvaluationDataset dataset, Path memoryPath) {
        return run(dataset, memoryPath, new MonadaMemoryOptions(new LexicalEnrichmentPipeline(), false));
    }

    /**
     * Evaluates {@code dataset} with the given options and captures latency/scan
     * diagnostics for every query.
     *
     * <p>Feedback-aware options are rejected: this runner does not seed feedback events,
     * so running with {@code feedbackAwareRanking=true} would measure non-feedback latency
     * while reporting feedback-aware ranking — a misleading combination. Use
     * {@link EvaluationProfileRunner} when feedback seeding is required.
     *
     * @throws IllegalArgumentException if {@code options.feedbackAwareRanking()} is {@code true}
     */
    public LatencyEvaluationReport run(EvaluationDataset dataset, Path memoryPath, MonadaMemoryOptions options) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(memoryPath, "memoryPath");
        Objects.requireNonNull(options, "options");
        if (options.feedbackAwareRanking()) {
            throw new IllegalArgumentException(
                    "LatencyEvaluationRunner does not seed feedback events. "
                    + "Running with feedbackAwareRanking=true would measure non-feedback latency "
                    + "under feedback-aware ranking, producing misleading diagnostics. "
                    + "Use EvaluationProfileRunner for feedback-aware profiles.");
        }

        var memory = MonadaMemory.open(memoryPath, options);
        var seeding = evaluationRunner.seedAtoms(dataset, memory);
        return evaluateWithLatency(dataset, memory, seeding.idToLabel(),
                options.feedbackQueryKeyStrategy(), options.feedbackAwareRanking());
    }

    private LatencyEvaluationReport evaluateWithLatency(
            EvaluationDataset dataset,
            MonadaMemory memory,
            Map<String, String> idToLabel,
            FeedbackQueryKeyStrategy queryKeyStrategy,
            boolean feedbackAware) {
        var maxK = ks.stream().mapToInt(Integer::intValue).max().orElse(1);
        var corpusSize = dataset.atoms().size();

        var queryEvaluations = new ArrayList<QueryEvaluation>(dataset.queries().size());
        var queryMetrics = new ArrayList<QueryLatencyMetrics>(dataset.queries().size());
        var precisionSums = new TreeMap<Integer, Double>();
        var recallSums = new TreeMap<Integer, Double>();
        var hitSums = new TreeMap<Integer, Double>();
        for (var k : ks) {
            precisionSums.put(k, 0.0);
            recallSums.put(k, 0.0);
            hitSums.put(k, 0.0);
        }
        var reciprocalRankSum = 0.0;

        for (var query : dataset.queries()) {
            var start = System.nanoTime();
            var recall = memory.resonate(query.text())
                    .topK(maxK)
                    .threshold(EVALUATION_THRESHOLD)
                    .execute();
            var elapsed = System.nanoTime() - start;

            var rankedLabels = new ArrayList<String>(recall.results().size());
            for (var result : recall.results()) {
                rankedLabels.add(idToLabel.getOrDefault(result.atom().id(), result.atom().id()));
            }

            var precisionByK = new TreeMap<Integer, Double>();
            var recallByK = new TreeMap<Integer, Double>();
            var hitByK = new TreeMap<Integer, Double>();
            for (var k : ks) {
                var p = PrecisionAtK.compute(query.expectedLabels(), rankedLabels, k);
                var r = RecallAtK.compute(query.expectedLabels(), rankedLabels, k);
                var h = HitAtK.compute(query.expectedLabels(), rankedLabels, k);
                precisionByK.put(k, p);
                recallByK.put(k, r);
                hitByK.put(k, h);
                precisionSums.merge(k, p, Double::sum);
                recallSums.merge(k, r, Double::sum);
                hitSums.merge(k, h, Double::sum);
            }
            double rr = ReciprocalRank.compute(query.expectedLabels(), rankedLabels);
            reciprocalRankSum += rr;

            QueryKeyDiagnostic diagnostic = null;
            if (queryKeyStrategy != null) {
                var rawKey = queryKeyStrategy.keyFor(query.text());
                var effectiveKey = (rawKey == null || rawKey.isBlank()) ? query.text() : rawKey;
                var simpleName = queryKeyStrategy.getClass().getSimpleName();
                var strategyName = (simpleName == null || simpleName.isBlank())
                        ? queryKeyStrategy.getClass().getName()
                        : simpleName;
                diagnostic = feedbackAware
                        ? QueryKeyDiagnostic.feedbackAwareNoSeed(effectiveKey, strategyName)
                        : QueryKeyDiagnostic.withoutFeedback(effectiveKey, strategyName);
            }

            queryEvaluations.add(new QueryEvaluation(
                    query.text(),
                    query.expectedLabels(),
                    rankedLabels,
                    precisionByK,
                    recallByK,
                    hitByK,
                    rr,
                    diagnostic));

            var isBlank = query.text().isBlank();
            queryMetrics.add(new QueryLatencyMetrics(
                    query.text(),
                    maxK,
                    EVALUATION_THRESHOLD,
                    corpusSize,
                    isBlank ? 0 : corpusSize,
                    recall.results().size(),
                    elapsed,
                    isBlank));
        }

        var n = dataset.queries().size();
        var averagePrecision = average(precisionSums, n);
        var averageRecall = average(recallSums, n);
        var averageHit = average(hitSums, n);
        var mrr = n == 0 ? 0.0 : reciprocalRankSum / n;

        var evaluationReport = new EvaluationReport(
                queryEvaluations, averagePrecision, averageRecall, averageHit, mrr);
        var summary = LatencySummary.from(queryMetrics, maxK, corpusSize);

        return new LatencyEvaluationReport(evaluationReport, queryMetrics, summary);
    }

    private Map<Integer, Double> average(Map<Integer, Double> sums, int n) {
        var averages = new TreeMap<Integer, Double>();
        for (var e : sums.entrySet()) {
            averages.put(e.getKey(), n == 0 ? 0.0 : e.getValue() / n);
        }
        return averages;
    }
}
