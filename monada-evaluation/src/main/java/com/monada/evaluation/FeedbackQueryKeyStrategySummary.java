package com.monada.evaluation;

/** Aggregate benefit and false-sharing risk counts for one strategy arm. */
public record FeedbackQueryKeyStrategySummary(
        int intendedTransferImproved,
        int intendedTransferMaintained,
        int intendedTransferDegraded,
        int intendedTransferIsolated,
        int negativeIsolated,
        int sharedNegativeWithoutMovement,
        int contaminationCount,
        int falseSharingCount,
        int negativeCaseCount
) {
    public FeedbackQueryKeyStrategySummary {
        int[] counts = {
                intendedTransferImproved,
                intendedTransferMaintained,
                intendedTransferDegraded,
                intendedTransferIsolated,
                negativeIsolated,
                sharedNegativeWithoutMovement,
                contaminationCount,
                falseSharingCount,
                negativeCaseCount
        };
        for (int count : counts) {
            if (count < 0) {
                throw new IllegalArgumentException("summary counts must be non-negative");
            }
        }
        if (falseSharingCount != sharedNegativeWithoutMovement + contaminationCount) {
            throw new IllegalArgumentException("falseSharingCount must equal shared negatives plus contamination");
        }
        if (negativeCaseCount != negativeIsolated + falseSharingCount) {
            throw new IllegalArgumentException("negativeCaseCount must partition isolated and false-sharing cases");
        }
    }

    public double falseSharingRate() {
        return negativeCaseCount == 0 ? 0.0 : (double) falseSharingCount / negativeCaseCount;
    }
}
