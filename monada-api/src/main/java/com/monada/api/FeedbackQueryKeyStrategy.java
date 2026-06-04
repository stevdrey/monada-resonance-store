package com.monada.api;

/**
 * Derives the feedback lookup key used to read and write query-scoped feedback.
 */
public interface FeedbackQueryKeyStrategy {

    String keyFor(String query);
}
