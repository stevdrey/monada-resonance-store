package com.monada.evaluation.execution;

import java.util.List;
import java.util.Objects;

/**
 * Hypothetical API-equivalent cost of everything recorded up to a validated acceptance.
 * {@code UNAVAILABLE} carries no amounts (never zero); {@code PARTIAL} amounts are lower bounds built from
 * covered lines only; {@code AVAILABLE} amounts are complete. An {@code AVAILABLE} result without amounts
 * means every stage declared no billable usage (see the {@code DECLARED_NO_BILLABLE_USAGE} reasons).
 */
public record CostToAcceptedOutcome(Status status, CostBasis basis, List<CurrencyCost> amounts,
                                    List<CostReason> reasons) {

    public enum Status { AVAILABLE, PARTIAL, UNAVAILABLE }

    public CostToAcceptedOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(basis, "basis");
        amounts = List.copyOf(amounts);
        reasons = List.copyOf(reasons);
        if (status == Status.UNAVAILABLE && !amounts.isEmpty()) {
            throw new IllegalArgumentException("an unavailable cost carries no amount");
        }
    }

    public boolean isLowerBound() {
        return status == Status.PARTIAL;
    }

    public boolean isDeclaredZero() {
        return status == Status.AVAILABLE && amounts.isEmpty();
    }
}
