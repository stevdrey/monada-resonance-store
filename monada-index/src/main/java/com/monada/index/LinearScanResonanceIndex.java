package com.monada.index;

import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.storage.AtomStore;
import com.monada.storage.FrequencyStore;
import com.monada.storage.StoredVector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LinearScanResonanceIndex implements ResonanceIndex {

    private final AtomStore atomStore;
    private final FrequencyStore frequencyStore;

    public LinearScanResonanceIndex(AtomStore atomStore, FrequencyStore frequencyStore) {
        this.atomStore = atomStore;
        this.frequencyStore = frequencyStore;
    }

    @Override
    public List<ResonanceResult> search(FrequencyVector queryVector, int topK, double threshold) throws IOException {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }
        Map<String, KnowledgeAtom> atomsById = new HashMap<>();
        for (KnowledgeAtom atom : atomStore.findAll()) {
            atomsById.put(atom.id(), atom);
        }
        List<ResonanceResult> results = new ArrayList<>();
        for (StoredVector storedVector : frequencyStore.findAll()) {
            KnowledgeAtom atom = atomsById.get(storedVector.atomId());
            if (atom == null) {
                continue;
            }
            double score = cosineSimilarity(queryVector, storedVector.vector());
            if (score >= threshold) {
                results.add(new ResonanceResult(atom, score));
            }
        }
        results.sort(Comparator.comparingDouble(ResonanceResult::score).reversed());
        if (results.size() > topK) {
            return results.subList(0, topK);
        }
        return results;
    }

    private double cosineSimilarity(FrequencyVector left, FrequencyVector right) {
        float[] leftValues = left.values();
        float[] rightValues = right.values();
        if (leftValues.length != rightValues.length) {
            throw new IllegalArgumentException("Vectors must have the same dimensions");
        }

        double dot = 0.0;
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        for (int i = 0; i < leftValues.length; i++) {
            dot += leftValues[i] * rightValues[i];
            leftMagnitude += leftValues[i] * leftValues[i];
            rightMagnitude += rightValues[i] * rightValues[i];
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }
}
