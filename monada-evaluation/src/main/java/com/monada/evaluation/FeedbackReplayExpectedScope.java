package com.monada.evaluation;

/** Declares whether a persisted feedback key is expected to match an evaluation query. */
public enum FeedbackReplayExpectedScope {
    MATCHING_EVALUATION_QUERY,
    UNMATCHED_EVALUATION_QUERY
}
