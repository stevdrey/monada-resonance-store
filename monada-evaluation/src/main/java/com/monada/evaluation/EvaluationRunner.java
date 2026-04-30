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

        MonadaMemory memory = MonadaMemory.open(memoryPath);

        // internal atom id -> label (for translating ranked results back to labels)
        Map<String, String> idToLabel = new HashMap<>();

        for (DatasetAtom atom : dataset.atoms()) {
            KnowledgeAtom stored = memory.remember(atom.content());
            idToLabel.put(stored.id(), atom.label());
        }

        int maxK = ks.stream().mapToInt(Integer::intValue).max().orElse(1);

        List<QueryEvaluation> queryResults = new ArrayList<>(dataset.queries().size());
        Map<Integer, Double> precisionSums = new TreeMap<>();
        for (int k : ks) {
            precisionSums.put(k, 0.0);
        }

        for (EvaluationQuery query : dataset.queries()) {
            MonadaRecall recall = memory.resonate(query.text()).topK(maxK).execute();

            List<String> rankedLabels = new ArrayList<>(recall.results().size());
            for (ResonanceResult result : recall.results()) {
                rankedLabels.add(idToLabel.getOrDefault(result.atom().id(), result.atom().id()));
            }

            Map<Integer, Double> precisionByK = new TreeMap<>();
            for (int k : ks) {
                double p = PrecisionAtK.compute(query.expectedLabels(), rankedLabels, k);
                precisionByK.put(k, p);
                precisionSums.merge(k, p, Double::sum);
            }

            queryResults.add(new QueryEvaluation(
                    query.text(),
                    query.expectedLabels(),
                    rankedLabels,
                    precisionByK));
        }

        Map<Integer, Double> averages = new TreeMap<>();
        int n = dataset.queries().size();
        for (Map.Entry<Integer, Double> e : precisionSums.entrySet()) {
            averages.put(e.getKey(), e.getValue() / n);
        }

        return new EvaluationReport(queryResults, averages);
    }
}
