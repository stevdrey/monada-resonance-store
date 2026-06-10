package com.monada.evaluation;

import com.monada.api.FeedbackQueryKeyStrategy;
import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loads a dataset into a {@link MonadaMemory} instance, runs each evaluation query,
 * and produces an {@link EvaluationReport} with Precision@K metrics.
 */
public final class EvaluationRunner {

    public static final List<Integer> DEFAULT_KS = List.of(1, 3, 5);
    private static final double EVALUATION_THRESHOLD = Double.NEGATIVE_INFINITY;

    private final List<Integer> ks;

    public EvaluationRunner() {
        this(DEFAULT_KS);
    }

    public EvaluationRunner(List<Integer> ks) {
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

    public EvaluationReport run(EvaluationDataset dataset, Path memoryPath) {
        return run(dataset, memoryPath, MonadaMemoryOptions.defaults());
    }

    /**
     * Evaluates {@code dataset} using a {@link MonadaMemory} opened with the
     * given {@link MonadaMemoryOptions}. This overload is required by the
     * profile-aware A/B harness to guarantee that both atom encoding and
     * query execution use the same configured options end-to-end.
     *
     * <p>Query-key diagnostics are captured automatically from {@code options}
     * so that callers using a custom {@code feedbackQueryKeyStrategy} see the
     * diagnostics block in the rendered {@link EvaluationReport}.
     */
    public EvaluationReport run(EvaluationDataset dataset, Path memoryPath, MonadaMemoryOptions options) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(memoryPath, "memoryPath");
        Objects.requireNonNull(options, "options");

        var memory = MonadaMemory.open(memoryPath, options);
        var seeding = seedAtoms(dataset, memory);
        return evaluate(dataset, memory, seeding.idToLabel(),
                options.feedbackQueryKeyStrategy(), options.feedbackAwareRanking());
    }

    /**
     * Appends every atom in {@code dataset} to {@code memory} exactly once and
     * returns a {@link DatasetSeeding} carrying both the {@code atomId -> label}
     * and {@code label -> atomId} mappings. Package-private so that
     * {@link EvaluationProfileRunner} can seed atoms with profile-specific
     * options and then seed feedback before evaluating.
     *
     * @throws IllegalArgumentException if the dataset contains a duplicate label, or
     *                                  if two atoms with different labels collide on
     *                                  the same content-derived atom id
     */
    DatasetSeeding seedAtoms(EvaluationDataset dataset, MonadaMemory memory) {
        var idToLabel = new HashMap<String, String>();
        var labelToAtomId = new HashMap<String, String>();
        for (var atom : dataset.atoms()) {
            var label = atom.label();
            if (labelToAtomId.containsKey(label)) {
                throw new IllegalArgumentException("duplicate dataset label: " + label);
            }
            var stored = memory.remember(atom.content(), atom.aliases());
            var id = stored.id();
            var priorLabel = idToLabel.put(id, label);
            if (priorLabel != null && !priorLabel.equals(label)) {
                throw new IllegalArgumentException(
                        "two dataset atoms share content-derived id '" + id
                                + "': labels=[" + priorLabel + ", " + label + "]");
            }
            labelToAtomId.put(label, id);
        }
        return new DatasetSeeding(
                Collections.unmodifiableMap(idToLabel),
                Collections.unmodifiableMap(labelToAtomId));
    }

    /**
     * Runs every query in {@code dataset} against an already-seeded
     * {@code memory} and aggregates metrics. Package-private so that
     * {@link EvaluationProfileRunner} can reuse the query phase without
     * reopening memory with default options.
     */
    EvaluationReport evaluate(EvaluationDataset dataset, MonadaMemory memory, Map<String, String> idToLabel) {
        return evaluate(dataset, memory, idToLabel, null, false);
    }

    /**
     * Runs every query in {@code dataset} against an already-seeded
     * {@code memory} and aggregates metrics, capturing query-key diagnostics
     * for each query using the provided strategy.
     *
     * @param queryKeyStrategy  the strategy to derive query keys; if null, no diagnostics are captured
     * @param feedbackAware     whether feedback-aware ranking is enabled
     */
    EvaluationReport evaluate(
            EvaluationDataset dataset,
            MonadaMemory memory,
            Map<String, String> idToLabel,
            FeedbackQueryKeyStrategy queryKeyStrategy,
            boolean feedbackAware) {
        var maxK = ks.stream().mapToInt(Integer::intValue).max().orElse(1);

        var queryResults = new ArrayList<QueryEvaluation>(dataset.queries().size());
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
            var recall = memory.resonate(query.text())
                    .topK(maxK)
                    .threshold(EVALUATION_THRESHOLD)
                    .execute();

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

            // Capture a query-key diagnostic whenever a deterministic strategy is available,
            // regardless of whether feedback-aware ranking is enabled.  This gives the report
            // visibility into how query keys are derived for all profiles.
            //
            // When feedbackAware=true, the diagnostic is created with withFeedback(), using
            // effectiveKey as both the evaluation key and the seed key.  This mirrors the real
            // MonadaMemory/MonadaQuery behavior: the same strategy-derived key is used for
            // both feedback lookup and query evaluation on the direct run(...) path.
            //
            // EvaluationProfileRunner overrides this by calling enhanceWithSeedQueryKeys()
            // after the explicit seeding phase, replacing the seed key with the one actually
            // used during feedback.feedback() calls for profile-level comparisons.
            QueryKeyDiagnostic diagnostic = null;
            if (queryKeyStrategy != null) {
                var rawKey = queryKeyStrategy.keyFor(query.text());
                var effectiveKey = (rawKey == null || rawKey.isBlank()) ? query.text() : rawKey;
                var simpleName = queryKeyStrategy.getClass().getSimpleName();
                var strategyName = (simpleName == null || simpleName.isBlank())
                        ? queryKeyStrategy.getClass().getName()
                        : simpleName;
                diagnostic = feedbackAware
                        ? QueryKeyDiagnostic.withFeedback(effectiveKey, strategyName, effectiveKey)
                        : QueryKeyDiagnostic.withoutFeedback(effectiveKey, strategyName);
            }

            queryResults.add(new QueryEvaluation(
                    query.text(),
                    query.expectedLabels(),
                    rankedLabels,
                    precisionByK,
                    recallByK,
                    hitByK,
                    rr,
                    diagnostic));
        }

        var n = dataset.queries().size();
        var averagePrecision = average(precisionSums, n);
        var averageRecall = average(recallSums, n);
        var averageHit = average(hitSums, n);
        var mrr = n == 0 ? 0.0 : reciprocalRankSum / n;

        return new EvaluationReport(queryResults, averagePrecision, averageRecall, averageHit, mrr);
    }

    private static Map<Integer, Double> average(Map<Integer, Double> sums, int n) {
        var averages = new TreeMap<Integer, Double>();
        for (var e : sums.entrySet()) {
            averages.put(e.getKey(), n == 0 ? 0.0 : e.getValue() / n);
        }
        return averages;
    }
}
