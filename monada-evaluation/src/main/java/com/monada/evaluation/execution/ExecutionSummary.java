package com.monada.evaluation.execution;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete, inspectable summary of one execution. {@code chain*} covers every stage up to the acceptance
 * cutoff (all recorded stages when there is no validated acceptance, so nothing is hidden); {@code
 * postAcceptance*} covers later stages and never enters {@link #cost()}. Hypothetical cost and {@code
 * actualBilled} are separate fields and are never merged. Gate evidence is per dimension, with no score.
 */
public record ExecutionSummary(ExecutionId execution, DerivedAcceptance acceptance, Optional<Long> acceptanceCutoff,
                               List<StageUsageCost> chainStages, UsageTotals chainUsage, TimeSummary chainTime,
                               List<StageUsageCost> postAcceptanceStages, UsageTotals postAcceptanceUsage,
                               TimeSummary postAcceptanceTime, CostToAcceptedOutcome cost,
                               List<ActualBilledAmount> actualBilled, List<GateEvidence> gates,
                               List<EventId> ignoredDuplicateEvents) {
    public ExecutionSummary {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(acceptanceCutoff, "acceptanceCutoff");
        Objects.requireNonNull(chainUsage, "chainUsage");
        Objects.requireNonNull(chainTime, "chainTime");
        Objects.requireNonNull(postAcceptanceUsage, "postAcceptanceUsage");
        Objects.requireNonNull(postAcceptanceTime, "postAcceptanceTime");
        Objects.requireNonNull(cost, "cost");
        chainStages = List.copyOf(chainStages);
        postAcceptanceStages = List.copyOf(postAcceptanceStages);
        actualBilled = List.copyOf(actualBilled);
        gates = List.copyOf(gates);
        ignoredDuplicateEvents = List.copyOf(ignoredDuplicateEvents);
    }
}
