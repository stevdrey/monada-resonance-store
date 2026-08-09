package com.monada.evaluation;

/** The three existing feedback query-key strategies compared by the experiment. */
public enum FeedbackQueryKeyComparisonStrategy {
    EXACT("exact"),
    NORMALIZED("normalized"),
    LEXICALLY_ENRICHED("lexically-enriched");

    private final String directoryName;

    FeedbackQueryKeyComparisonStrategy(String directoryName) {
        this.directoryName = directoryName;
    }

    String directoryName() {
        return directoryName;
    }
}
