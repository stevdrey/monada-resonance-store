package com.monada.evaluation.execution;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.core.execution.EventId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Everything the report keeps about one execution: derived acceptance, eligibility with its exclusion reasons,
 * the per-dimension gate evidence with evaluator, policy, source and artifacts, and how complete its
 * resource chain is. No score is attached.
 */
public record Participant(ExecutionRef ref, Comparability.Key key, DerivedAcceptance acceptance,
                          Eligibility eligibility, ResourceCoverage resources, List<GateEvidence> gates) {

    /** Distinct price snapshot a chain was priced with. */
    public record PricingBasis(String snapshotId, String version, String provider, String model, String currency,
                               String source, String assumptions) {
        private static final Comparator<PricingBasis> ORDER = Comparator.comparing(PricingBasis::snapshotId)
                .thenComparing(PricingBasis::version).thenComparing(PricingBasis::provider)
                .thenComparing(PricingBasis::model);
    }

    /** Coverage of the resource chain behind the cost: what was priced, what was not and why. */
    public record ResourceCoverage(ChainScope chainScope, CostToAcceptedOutcome.Status costStatus,
                                   List<CostReason> costReasons, int chainStages, int coveredStages,
                                   List<PricingBasis> pricing, List<EventId> ignoredDuplicateEvents) {
        public ResourceCoverage {
            costReasons = List.copyOf(costReasons);
            pricing = List.copyOf(pricing);
            ignoredDuplicateEvents = List.copyOf(ignoredDuplicateEvents);
        }

        static ResourceCoverage of(ExecutionSummary s) {
            List<PricingBasis> pricing = s.chainStages().stream().flatMap(st -> st.priceSnapshot().stream())
                    .map(p -> new PricingBasis(p.id(), p.version(), p.provider(), p.model(),
                            p.currency().getCurrencyCode(), p.source(), p.assumptions()))
                    .distinct().sorted(PricingBasis.ORDER).toList();
            return new ResourceCoverage(s.chainScope(), s.cost().status(), s.cost().reasons(),
                    s.chainStages().size(), (int) s.chainStages().stream().filter(StageUsageCost::covered).count(),
                    pricing, s.ignoredDuplicateEvents());
        }
    }

    public Participant {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(resources, "resources");
        gates = List.copyOf(gates);
    }

    public boolean eligible() {
        return eligibility.eligible();
    }
}
