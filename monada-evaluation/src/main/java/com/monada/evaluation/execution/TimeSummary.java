package com.monada.evaluation.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Time of a set of stages. {@code effort} sums stage durations (parallel stages add up), {@code wallClock} is
 * first start to last end, {@code activeTime} is the union of the stage intervals and {@code parallelOverlap}
 * is {@code effort - activeTime}. Without stages everything is absent with a reason, never zero.
 */
public record TimeSummary(int stageCount, Optional<Instant> firstStart, Optional<Instant> lastEnd,
                          Optional<Duration> effort, Optional<Duration> wallClock, Optional<Duration> activeTime,
                          Optional<Duration> parallelOverlap, Optional<String> unavailableReason) {
    public TimeSummary {
        Objects.requireNonNull(firstStart, "firstStart");
        Objects.requireNonNull(lastEnd, "lastEnd");
        Objects.requireNonNull(effort, "effort");
        Objects.requireNonNull(wallClock, "wallClock");
        Objects.requireNonNull(activeTime, "activeTime");
        Objects.requireNonNull(parallelOverlap, "parallelOverlap");
        Objects.requireNonNull(unavailableReason, "unavailableReason");
    }
}
