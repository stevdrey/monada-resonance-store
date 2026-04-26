package com.monada.storage;

import java.util.Objects;

public record Manifest(String version, int dimensions, String vectorSegment, String atomSegment) {
    public Manifest {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(vectorSegment, "vectorSegment");
        Objects.requireNonNull(atomSegment, "atomSegment");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero");
        }
    }
}
