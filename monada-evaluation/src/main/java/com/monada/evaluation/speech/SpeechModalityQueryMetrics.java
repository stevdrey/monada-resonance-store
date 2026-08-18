package com.monada.evaluation.speech;

/** Metrics for one modality and one paired query. */
public record SpeechModalityQueryMetrics(
        int retrievedCount,
        int relevantRetrievedCount,
        boolean hitAtK,
        double precisionAtK,
        double recallAtK,
        double reciprocalRank,
        int firstRelevantRank
) {
    public SpeechModalityQueryMetrics {
        if (retrievedCount < 0) {
            throw new IllegalArgumentException("retrievedCount must be non-negative");
        }
        if (relevantRetrievedCount < 0 || relevantRetrievedCount > retrievedCount) {
            throw new IllegalArgumentException("relevantRetrievedCount must be between 0 and retrievedCount");
        }
        if (!Double.isFinite(precisionAtK) || !Double.isFinite(recallAtK) || !Double.isFinite(reciprocalRank)) {
            throw new IllegalArgumentException("metrics must be finite");
        }
        if (firstRelevantRank < 0) {
            throw new IllegalArgumentException("firstRelevantRank must be non-negative");
        }
        if (hitAtK != (relevantRetrievedCount > 0)) {
            throw new IllegalArgumentException("hitAtK must equal whether relevantRetrievedCount is positive");
        }
        if (firstRelevantRank == 0 && reciprocalRank != 0.0) {
            throw new IllegalArgumentException("reciprocalRank must be 0.0 when firstRelevantRank is 0");
        }
        if (firstRelevantRank > 0 && reciprocalRank == 0.0) {
            throw new IllegalArgumentException("reciprocalRank must be non-zero when firstRelevantRank is positive");
        }
    }
}
