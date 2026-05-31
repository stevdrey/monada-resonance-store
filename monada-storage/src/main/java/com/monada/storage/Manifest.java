package com.monada.storage;

import com.monada.storage.feedback.FeedbackStore;

import java.util.Objects;

public record Manifest(String version, int dimensions, String vectorSegment, String atomSegment, String feedbackSegment, EncodingProfile encodingProfile) {
    public Manifest {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(vectorSegment, "vectorSegment");
        Objects.requireNonNull(atomSegment, "atomSegment");
        Objects.requireNonNull(feedbackSegment, "feedbackSegment");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero");
        }
    }

    public Manifest(String version, int dimensions, String vectorSegment, String atomSegment, String feedbackSegment) {
        this(version, dimensions, vectorSegment, atomSegment, feedbackSegment, null);
    }

    public Manifest(String version, int dimensions, String vectorSegment, String atomSegment) {
        this(version, dimensions, vectorSegment, atomSegment, FeedbackStore.DEFAULT_SEGMENT, null);
    }
}
