package com.monada.core.execution;

import java.util.Objects;
import java.util.Optional;

/** Execution outcome. Distinct from quality evidence (no score) and from retrieval feedback. */
public record Outcome(OutcomeStatus status, OutcomeOrigin origin, Optional<AttemptId> acceptedAttempt) {
    public Outcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(acceptedAttempt, "acceptedAttempt");
        if (status == OutcomeStatus.ACCEPTED && acceptedAttempt.isEmpty()) {
            throw new IllegalArgumentException("ACCEPTED requires an accepted attempt");
        }
        if (status != OutcomeStatus.ACCEPTED && acceptedAttempt.isPresent()) {
            throw new IllegalArgumentException(status + " must not name an accepted attempt");
        }
    }

    public static Outcome accepted(OutcomeOrigin origin, AttemptId attempt) {
        return new Outcome(OutcomeStatus.ACCEPTED, origin, Optional.of(attempt));
    }

    public static Outcome rejected(OutcomeOrigin origin) {
        return new Outcome(OutcomeStatus.REJECTED, origin, Optional.empty());
    }

    public static Outcome failed(OutcomeOrigin origin) {
        return new Outcome(OutcomeStatus.FAILED, origin, Optional.empty());
    }

    public static Outcome cancelled(OutcomeOrigin origin) {
        return new Outcome(OutcomeStatus.CANCELLED, origin, Optional.empty());
    }

    public static Outcome pending(OutcomeOrigin origin) {
        return new Outcome(OutcomeStatus.PENDING, origin, Optional.empty());
    }
}
