package com.monada.api.execution;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link ExecutionMemory#recall}: at most {@code limit} hits ordered by similarity descending, then
 * atom ID ascending, then canonical experience ref ascending, plus the projection status they came from.
 * When the status is {@code MISSING} or {@code INCOMPATIBLE} there are no hits.
 */
public record ExperienceRecall(ProjectionStatus status, List<ExperienceHit> hits) {
    /** Largest accepted recall limit. */
    public static final int MAX_LIMIT = 50;

    public ExperienceRecall {
        Objects.requireNonNull(status, "status");
        hits = List.copyOf(hits);
    }
}
