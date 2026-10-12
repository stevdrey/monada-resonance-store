package com.monada.evaluation.execution;

import com.monada.core.execution.EventId;
import com.monada.core.execution.UsageKind;
import java.util.Objects;
import java.util.Optional;

/** One explicit reason why a cost is partial, unavailable or declared rather than measured. */
public record CostReason(Code code, Optional<EventId> stageEvent, Optional<String> stage,
                         Optional<UsageKind> kind, String detail) {

    public enum Code {
        MISSING_PRICE, AMBIGUOUS_PRICE, MISSING_USAGE, UNKNOWN_USAGE, INCONSISTENT_USAGE,
        DECLARED_NO_BILLABLE_USAGE, NO_USAGE_RECORDED, NO_VALIDATED_ACCEPTANCE, CUTOFF_UNDERIVABLE
    }

    public CostReason {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stageEvent, "stageEvent");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
    }

    static CostReason forStage(Code code, EventId event, String stage, UsageKind kind, String detail) {
        return new CostReason(code, Optional.of(event), Optional.of(stage), Optional.ofNullable(kind), detail);
    }

    static CostReason forChain(Code code, String detail) {
        return new CostReason(code, Optional.empty(), Optional.empty(), Optional.empty(), detail);
    }

    /** True for reasons that make a stage not covered; a declared zero is covered. */
    public boolean isGap() {
        return code != Code.DECLARED_NO_BILLABLE_USAGE;
    }
}
