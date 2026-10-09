package com.monada.api.execution;

import java.util.List;
import java.util.Objects;

/**
 * Acceptance derived on every read from outcome, origin and evidence (contract section 8); never stored.
 * {@code VALIDATED_ACCEPTED} is the only status that means "accepted and every mandatory gate passed".
 */
public record DerivedAcceptance(Status status, List<String> reasons) {

    public enum Status { VALIDATED_ACCEPTED, ACCEPTED_UNVALIDATED, NOT_ACCEPTED, PENDING }

    public DerivedAcceptance {
        Objects.requireNonNull(status, "status");
        reasons = List.copyOf(reasons);
    }

    public boolean isValidatedAcceptance() {
        return status == Status.VALIDATED_ACCEPTED;
    }
}
