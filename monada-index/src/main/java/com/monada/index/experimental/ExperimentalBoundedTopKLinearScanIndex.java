package com.monada.index.experimental;

import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.index.ResonanceIndex;
import com.monada.storage.AtomStore;
import com.monada.storage.FrequencyStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

/**
 * Experimental linear-scan candidate that replaces the full sort with
 * {@link ExactBoundedTopKSelector} (Issue #120).
 *
 * <p>Scanning, cosine scoring, inclusive threshold filtering, orphan skipping and the
 * per-atom max-score deduplication are identical to {@link LinearScanResonanceIndex};
 * only the final sort-and-truncate step differs. Deduplication completes before
 * selection, so a later higher-scoring vector for the same atom still replaces the
 * earlier one.
 *
 * <p><strong>Experimental — not used by any default path.</strong> It is only
 * constructed explicitly by tests and evaluation tooling; {@link LinearScanResonanceIndex}
 * remains the production linear-scan index.
 */
public final class ExperimentalBoundedTopKLinearScanIndex implements ResonanceIndex {

    private final AtomStore atomStore;
    private final FrequencyStore frequencyStore;

    public ExperimentalBoundedTopKLinearScanIndex(AtomStore atomStore, FrequencyStore frequencyStore) {
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
        var resultsByAtomId = new HashMap<String, ResonanceResult>();
        for (var storedVector : frequencyStore.findAll()) {
            var atom = atomsById.get(storedVector.atomId());
            if (atom == null) {
                continue;
            }
            var score = cosineSimilarity(queryVector, storedVector.vector());
            if (score >= threshold) {
                var current = resultsByAtomId.get(atom.id());
                if (current == null || score > current.score()) {
                    resultsByAtomId.put(atom.id(), new ResonanceResult(atom, score));
                }
            }
        }
        return ExactBoundedTopKSelector.select(resultsByAtomId.values(), topK).results();
    }

    /** Verbatim copy of the {@link LinearScanResonanceIndex} cosine formula (float products). */
    private static double cosineSimilarity(FrequencyVector left, FrequencyVector right) {
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
