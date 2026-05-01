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
        var atomsById = new HashMap<String, KnowledgeAtom>();
        for (var atom : atomStore.findAll()) {
            atomsById.put(atom.id(), atom);
        }
        var results = new ArrayList<ResonanceResult>();
        for (var storedVector : frequencyStore.findAll()) {
            var atom = atomsById.get(storedVector.atomId());
            if (atom == null) {
                continue;
            }
            var score = cosineSimilarity(queryVector, storedVector.vector());
            if (score >= threshold) {
                results.add(new ResonanceResult(atom, score));
            }
        }
        // Deterministic ordering: primary by score desc, secondary by atom id asc.
        // The secondary criterion guarantees stable top-K output when two or more
        // atoms share the same resonance score, independent of storage load order.
        results.sort(
                Comparator.comparingDouble(ResonanceResult::score).reversed()
                        .thenComparing(result -> result.atom().id())
        );
        if (results.size() > topK) {
            return List.copyOf(results.subList(0, topK));
        }
        return List.copyOf(results);
    }

    private double cosineSimilarity(FrequencyVector left, FrequencyVector right) {
        var leftValues = left.values();
        var rightValues = right.values();
        if (leftValues.length != rightValues.length) {
            throw new IllegalArgumentException("Vectors must have the same dimensions");
        }

        var dot = 0.0;
        var leftMagnitude = 0.0;
        var rightMagnitude = 0.0;
        for (var i = 0; i < leftValues.length; i++) {
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
