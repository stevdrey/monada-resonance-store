package com.monada.evaluation.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.BillingMode;
import com.monada.core.execution.EventId;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.UsageDeclaration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hypothetical cost of one effective {@code STAGE_RECORDED} event. {@code sequence} is the ledger sequence of
 * the latest revision, {@code originalSequence} that of the first revision (it decides chain membership).
 * A stage is {@code covered} when it has no gap reason; a declared zero is covered and visibly declared.
 */
public record StageUsageCost(EventId event, AttemptId attempt, String stage, long sequence, long originalSequence,
                             boolean postAcceptance, BillingMode billingMode, UsageDeclaration declaration,
                             Optional<PriceSnapshot> priceSnapshot, List<CostLine> lines,
                             List<CostReason> reasons, boolean covered) {
    public StageUsageCost {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(billingMode, "billingMode");
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(priceSnapshot, "priceSnapshot");
        lines = List.copyOf(lines);
        reasons = List.copyOf(reasons);
    }
}
