package com.monada.evaluation;

/** Full-corpus position and score of one feedback target for one evaluation query. */
public record FeedbackQueryKeyTargetRank(int fullCorpusRank, double score) {
    public FeedbackQueryKeyTargetRank {
        if (fullCorpusRank <= 0) {
            throw new IllegalArgumentException("fullCorpusRank must be positive: " + fullCorpusRank);
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite: " + score);
        }
    }
}
