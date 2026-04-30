package com.monada.evaluation;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import com.monada.core.MonadaRecall;
import com.monada.core.ResonanceResult;

import java.nio.file.Path;
import java.util.ArrayList;
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
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(memoryPath, "memoryPath");

        var memory = MonadaMemory.open(memoryPath);

        // internal atom id -> label (for translating ranked results back to labels)
        var idToLabel = new HashMap<String, String>();

        for (DatasetAtom atom : dataset.atoms()) {
            KnowledgeAtom stored = memory.remember(atom.content());
            idToLabel.put(stored.id(), atom.label());
        }

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
        double reciprocalRankSum = 0.0;

        for (EvaluationQuery query : dataset.queries()) {
            MonadaRecall recall = memory.resonate(query.text()).topK(maxK).execute();

            var rankedLabels = new ArrayList<String>(recall.results().size());
            for (ResonanceResult result : recall.results()) {
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

            queryResults.add(new QueryEvaluation(
                    query.text(),
                    query.expectedLabels(),
                    rankedLabels,
                    precisionByK,
                    recallByK,
                    hitByK,
                    rr));
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
