package com.monada.index;

import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.storage.AtomStore;
import com.monada.storage.FrequencyStore;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
        return frequencyStore.findAll().stream()
                .map(storedVector -> resultFor(queryVector, storedVector.atomId(), storedVector.vector()))
                .flatMap(Optional::stream)
                .filter(result -> result.score() >= threshold)
                .sorted(Comparator.comparingDouble(ResonanceResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private Optional<ResonanceResult> resultFor(FrequencyVector queryVector, String atomId, FrequencyVector storedVector) {
        try {
            Optional<KnowledgeAtom> atom = atomStore.findById(atomId);
            return atom.map(knowledgeAtom -> new ResonanceResult(knowledgeAtom, cosineSimilarity(queryVector, storedVector)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
