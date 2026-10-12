package com.monada.evaluation.execution;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.api.execution.ExecutionView;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.OutcomeStatus;
import com.monada.core.execution.QualityDimension;
import com.monada.evaluation.execution.CostReason.Code;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pure calculators over a recorded {@link ExecutionView} (contract sections 8, 9 and 19). It reads no clock,
 * file or network, looks up no prices and ranks nothing: identical input gives an identical summary.
 */
public final class ExecutionSummaryCalculator {

    private ExecutionSummaryCalculator() {
    }

    public static ExecutionSummary summarize(ExecutionView view, PriceBook prices,
                                             List<ActualBilledAmount> actualBilled) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(actualBilled, "actualBilled");

        ChainResolver.Resolved resolved = ChainResolver.resolve(view.history());
        Optional<ChainResolver.Effective> outcomeEvent = resolved.effective().stream()
                .filter(e -> e.event() instanceof OutcomeRecorded)
                .max(Comparator.comparingLong(ChainResolver.Effective::sequence));
        Optional<AttemptId> acceptedAttempt = outcomeEvent
                .map(e -> ((OutcomeRecorded) e.event()).outcome())
                .filter(o -> o.status() == OutcomeStatus.ACCEPTED)
                .flatMap(o -> o.acceptedAttempt());
        var observed = GateEvidenceCollector.effective(resolved.effective(), acceptedAttempt, view.policy());
        List<GateEvidence> gates = GateEvidenceCollector.collect(observed, acceptedAttempt, view.policy());

        DerivedAcceptance acceptance = view.acceptance();
        Optional<Long> cutoff = Optional.empty();
        if (acceptance.isValidatedAcceptance() && outcomeEvent.isPresent() && acceptedAttempt.isPresent()) {
            long max = outcomeEvent.get().sequence();
            // Acceptance also requires the accepted attempt's effective ATTEMPT_FINISHED(COMPLETED) (section 8).
            Optional<ChainResolver.Effective> completion = resolved.effective().stream()
                    .filter(e -> e.event() instanceof AttemptFinished f
                            && f.envelope().attemptId().equals(acceptedAttempt)
                            && f.result() == AttemptResult.COMPLETED)
                    .max(Comparator.comparingLong(ChainResolver.Effective::sequence));
            boolean complete = completion.isPresent();
            if (complete) {
                max = Math.max(max, completion.get().sequence());
            }
            for (QualityDimension d : view.policy().mandatoryDimensions()) {
                var o = observed.get(d);
                if (o == null) {
                    complete = false;
                } else {
                    max = Math.max(max, o.sequence());
                }
            }
            cutoff = complete ? Optional.of(max) : Optional.empty();
        }

        List<StageUsageCost> chain = new ArrayList<>();
        List<StageUsageCost> post = new ArrayList<>();
        List<StageRecorded> chainEvents = new ArrayList<>();
        List<StageRecorded> postEvents = new ArrayList<>();
        for (ChainResolver.Effective e : resolved.effective()) {
            if (e.event() instanceof StageRecorded stage) {
                boolean after = cutoff.isPresent() && e.originalSequence() > cutoff.get();
                (after ? post : chain).add(UsageCostCalculator.cost(e, stage, prices, after));
                (after ? postEvents : chainEvents).add(stage);
            }
        }

        CostToAcceptedOutcome cost = costToAcceptedOutcome(acceptance, cutoff, chain);
        ChainScope scope = cutoff.isPresent() ? ChainScope.UP_TO_ACCEPTANCE : ChainScope.ALL_RECORDED_NO_ACCEPTANCE;
        return new ExecutionSummary(view.id(), acceptance, cutoff, scope, chain,
                UsageTotalsCalculator.totals(chainEvents, chain), TimeCalculator.of(chainEvents), post,
                UsageTotalsCalculator.totals(postEvents, post),
                TimeCalculator.of(postEvents), cost, actualBilled, gates, resolved.ignoredDuplicates());
    }

    private static CostToAcceptedOutcome costToAcceptedOutcome(DerivedAcceptance acceptance, Optional<Long> cutoff,
                                                               List<StageUsageCost> chain) {
        CostBasis basis = CostBasis.HYPOTHETICAL_API_EQUIVALENT;
        if (!acceptance.isValidatedAcceptance()) {
            return unavailable(basis, CostReason.forChain(Code.NO_VALIDATED_ACCEPTANCE,
                    acceptance.status() + (acceptance.reasons().isEmpty() ? "" : ": " + acceptance.reasons())));
        }
        if (cutoff.isEmpty()) {
            return unavailable(basis, CostReason.forChain(Code.CUTOFF_UNDERIVABLE,
                    "the accepted outcome, its completed attempt or its mandatory evidence is not in the supplied history"));
        }
        if (chain.isEmpty()) {
            return unavailable(basis, CostReason.forChain(Code.NO_USAGE_RECORDED, "no stage before the cutoff"));
        }
        Map<String, Totals> byCurrency = new TreeMap<>();
        List<CostReason> reasons = new ArrayList<>();
        boolean allCovered = true;
        for (StageUsageCost stage : chain) {
            reasons.addAll(stage.reasons());
            allCovered &= stage.covered();
            stage.priceSnapshot().ifPresent(s ->
                    byCurrency.computeIfAbsent(s.currency().getCurrencyCode(), k -> new Totals(s.currency())));
            for (CostLine line : stage.lines()) {
                byCurrency.get(stage.priceSnapshot().orElseThrow().currency().getCurrencyCode()).add(line);
            }
        }
        List<CurrencyCost> amounts = byCurrency.values().stream().map(Totals::toCost).toList();
        return new CostToAcceptedOutcome(allCovered ? CostToAcceptedOutcome.Status.AVAILABLE
                : CostToAcceptedOutcome.Status.PARTIAL, basis, amounts, reasons);
    }

    private static CostToAcceptedOutcome unavailable(CostBasis basis, CostReason reason) {
        return new CostToAcceptedOutcome(CostToAcceptedOutcome.Status.UNAVAILABLE, basis, List.of(), List.of(reason));
    }

    /** Exact running totals of one currency, split by usage provenance. */
    private static final class Totals {
        private final Currency currency;
        private BigDecimal reported = BigDecimal.ZERO;
        private BigDecimal estimated = BigDecimal.ZERO;

        Totals(Currency currency) {
            this.currency = currency;
        }

        void add(CostLine line) {
            switch (line.provenance()) {
                case REPORTED -> reported = reported.add(line.cost());
                case ESTIMATED -> estimated = estimated.add(line.cost());
                case UNKNOWN -> throw new IllegalStateException("unknown usage cannot be priced");
            }
        }

        CurrencyCost toCost() {
            return new CurrencyCost(currency, reported, estimated);
        }
    }
}
