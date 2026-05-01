package com.monada.storage;

import java.util.Objects;

public record Manifest(String version, int dimensions, String vectorSegment, String atomSegment, String feedbackSegment) {
    public static final String DEFAULT_FEEDBACK_SEGMENT = "feedback/feedback-000001.log";

    public Manifest {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(vectorSegment, "vectorSegment");
        Objects.requireNonNull(atomSegment, "atomSegment");
        Objects.requireNonNull(feedbackSegment, "feedbackSegment");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero");
        }
    }

    /**
     * Backward-compatible convenience constructor that applies the default
     * feedback segment path. Prefer the canonical constructor in new code.
     */
    public Manifest(String version, int dimensions, String vectorSegment, String atomSegment) {
        this(version, dimensions, vectorSegment, atomSegment, DEFAULT_FEEDBACK_SEGMENT);
    }
}
