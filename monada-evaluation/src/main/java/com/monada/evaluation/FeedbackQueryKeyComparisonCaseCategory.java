package com.monada.evaluation;

/** Semantic role of one deterministic feedback query-key comparison case. */
public enum FeedbackQueryKeyComparisonCaseCategory {
    EXACT_CONTROL(true),
    NORMALIZATION_EQUIVALENT(true),
    LEXICAL_EQUIVALENCE(true),
    CONFUSABLE_NEIGHBOR(false),
    SHARED_GENERIC_PHRASE(false),
    ORDERED_TERMS_DIFFERENT_INTENT(false);

    private final boolean intendedTransfer;

    FeedbackQueryKeyComparisonCaseCategory(boolean intendedTransfer) {
        this.intendedTransfer = intendedTransfer;
    }

    public boolean intendedTransfer() {
        return intendedTransfer;
    }
}
