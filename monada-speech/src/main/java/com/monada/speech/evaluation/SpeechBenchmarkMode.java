package com.monada.speech.evaluation;

/**
 * Distinguishes the two protected speech-benchmark workflows.
 *
 * <p>{@link #PROTECTED} runs against small, deterministic, generated fixtures and is the
 * regression gate enforced in CI. {@link #EXPLORATORY} runs against a local real corpus,
 * renders the same metrics for comparison over time, but is never enforced in CI.
 */
public enum SpeechBenchmarkMode {

    /** Deterministic generated fixtures with protected expected metrics; enforced in CI. */
    PROTECTED,

    /** Local real-data run; metrics are rendered for inspection but not enforced in CI. */
    EXPLORATORY
}
