package com.monada.evaluation.execution;

import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.PriceLine;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageDeclaration;
import com.monada.core.execution.UsageKind;
import com.monada.core.execution.UsageProvenance;
import com.monada.evaluation.execution.CostReason.Code;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Prices one effective stage and decides, per stage, whether its usage is covered (contract section 9). */
final class UsageCostCalculator {

    private UsageCostCalculator() {
    }

    static StageUsageCost cost(ChainResolver.Effective effective, StageRecorded stage, PriceBook book,
                               boolean postAcceptance) {
        List<CostReason> reasons = new ArrayList<>();
        List<CostLine> lines = new ArrayList<>();

        if (stage.usageDeclaration() == UsageDeclaration.NO_BILLABLE_USAGE) {
            reasons.add(CostReason.forStage(Code.DECLARED_NO_BILLABLE_USAGE, effective.id(), stage.stage(), null,
                    stage.justification().orElse("")));
            return build(effective, stage, postAcceptance, Optional.empty(), lines, reasons);
        }

        PriceBook.Match match = book.lookup(stage.route().provider(), stage.route().model(), stage.endedAt());
        if (match.status() != PriceBook.MatchStatus.FOUND) {
            Code code = match.status() == PriceBook.MatchStatus.AMBIGUOUS ? Code.AMBIGUOUS_PRICE : Code.MISSING_PRICE;
            reasons.add(CostReason.forStage(code, effective.id(), stage.stage(), null,
                    "no unambiguous price snapshot for provider/model at " + stage.endedAt()));
            return build(effective, stage, postAcceptance, Optional.empty(), lines, reasons);
        }
        PriceSnapshot snapshot = match.snapshot().orElseThrow();

        Map<UsageKind, PriceLine> priced = new EnumMap<>(UsageKind.class);
        snapshot.lines().forEach(l -> priced.put(l.kind(), l));
        Map<UsageKind, UsageCounter> counters = new EnumMap<>(UsageKind.class);
        stage.usage().forEach(c -> counters.put(c.kind(), c));

        Set<UsageKind> valued = EnumSet.noneOf(UsageKind.class);
        for (UsageKind kind : priced.keySet()) {
            UsageCounter counter = counters.get(kind);
            if (counter == null) {
                reasons.add(CostReason.forStage(Code.MISSING_USAGE, effective.id(), stage.stage(), kind,
                        "counter not measured"));
            } else if (counter.provenance() == UsageProvenance.UNKNOWN) {
                reasons.add(CostReason.forStage(Code.UNKNOWN_USAGE, effective.id(), stage.stage(), kind,
                        "counter provenance is unknown"));
            } else {
                valued.add(kind);
            }
        }

        for (UsageKind kind : priced.keySet()) {
            if (!valued.contains(kind)) {
                continue;
            }
            List<UsageKind> children = priced.values().stream()
                    .filter(l -> l.includedIn().filter(kind::equals).isPresent())
                    .map(PriceLine::kind).toList();
            if (!valued.containsAll(children)) {
                continue; // the overlap cannot be removed; the child's own gap is already a reason
            }
            long billable = counters.get(kind).value().orElseThrow();
            UsageProvenance provenance = counters.get(kind).provenance();
            for (UsageKind child : children) {
                billable = subtract(billable, counters.get(child).value().orElseThrow());
                if (counters.get(child).provenance() == UsageProvenance.ESTIMATED) {
                    provenance = UsageProvenance.ESTIMATED;
                }
            }
            if (billable < 0) {
                reasons.add(CostReason.forStage(Code.INCONSISTENT_USAGE, effective.id(), stage.stage(), kind,
                        "included kinds exceed the counter value"));
                continue;
            }
            PriceLine line = priced.get(kind);
            lines.add(new CostLine(kind, billable, provenance, line.price(), line.unitSize(),
                    CostArithmetic.lineCost(billable, line)));
        }
        return build(effective, stage, postAcceptance, Optional.of(snapshot), lines, reasons);
    }

    private static long subtract(long value, long other) {
        try {
            return Math.subtractExact(value, other);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("usage quantity overflows long", e);
        }
    }

    private static StageUsageCost build(ChainResolver.Effective effective, StageRecorded stage,
                                        boolean postAcceptance, Optional<PriceSnapshot> snapshot,
                                        List<CostLine> lines, List<CostReason> reasons) {
        return new StageUsageCost(effective.id(), stage.envelope().attemptId().orElseThrow(), stage.stage(),
                effective.sequence(), effective.originalSequence(), postAcceptance, stage.route().billingMode(),
                stage.usageDeclaration(), snapshot, lines, reasons);
    }
}
