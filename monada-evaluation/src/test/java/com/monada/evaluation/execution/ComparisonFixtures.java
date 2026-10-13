package com.monada.evaluation.execution;

import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.BillingMode;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.PriceLine;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.monada.evaluation.execution.Run.*;

/**
 * Builds genuine recorded executions (through {@code ExecutionMemory} in temporary directories) for the
 * comparison tests. Prices (USD per 1M tokens): input 2, output 10, so {@code usage(10_000, 1_000)} costs
 * {@code 0.02 + 0.01 = 0.03}.
 */
final class ComparisonFixtures {
    static final SourceProvenance PROV = new SourceProvenance("rev-1", "ctx-1", "constraints-1");
    static final EvaluationPolicy POLICY = new EvaluationPolicy("policy", "1", Set.of(QualityDimension.TESTS));
    static final LocalDate PRICED = LocalDate.parse("2025-12-01");

    private final Path root;
    private int dirs;

    ComparisonFixtures(Path root) {
        this.root = root;
    }

    static UsageCounter[] usage(long input, long output) {
        return new UsageCounter[]{in(input), cached(0), out(output)};
    }

    static RouteDescriptor route(String provider, String model) {
        return new RouteDescriptor("worker", Optional.of(provider), Optional.of(model), Optional.empty(),
                BillingMode.API_METERED);
    }

    /** Same price lines as {@link Run#usd()} for another provider/model, currency and assumptions text. */
    static PriceSnapshot priced(String provider, String model, String currency, String assumptions) {
        return new PriceSnapshot("prices-" + model, "1", provider, model, PriceSnapshot.currency(currency), PRICED,
                "caller fixture", assumptions, List.of(
                PriceLine.of(UsageKind.INPUT_TOKENS, new BigDecimal("2"), 1_000_000),
                new PriceLine(UsageKind.CACHED_INPUT_TOKENS, new BigDecimal("0.5"), 1_000_000,
                        Optional.of(UsageKind.INPUT_TOKENS)),
                PriceLine.of(UsageKind.OUTPUT_TOKENS, new BigDecimal("10"), 1_000_000)));
    }

    Run run(ScopeId scope, TaskId task, String exec, SourceProvenance provenance, EvaluationPolicy policy) {
        try {
            return new Run(Files.createDirectories(root.resolve("run-" + dirs++)), scope, task,
                    ExecutionId.of(exec), provenance, policy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Run run(String exec) {
        return run(SCOPE, TASK, exec, PROV, POLICY);
    }

    /** One attempt, one stage ({@code seconds} long), TESTS PASS, completed and validated-accepted. */
    Run valid(String exec, long input, long output, long seconds) {
        Run r = run(exec);
        r.initial(A1);
        r.stage(A1, "work", 0, seconds, usage(input, output));
        r.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        r.finish(A1, AttemptResult.COMPLETED);
        r.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        return r;
    }

    /** Failed first attempt, then an accepted repair: 10 s + 30 s of work, 0.03 + 0.06 = 0.09 USD. */
    Run repaired(String exec) {
        Run r = run(exec);
        r.initial(A1);
        r.stage(A1, "implement", 0, 10, usage(10_000, 1_000));
        r.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
        r.finish(A1, AttemptResult.FAILED);
        r.follow(A2, 2, AttemptReason.REPAIR, A1);
        r.stage(A2, "repair", 20, 50, usage(20_000, 2_000));
        r.evidence(A2, QualityDimension.TESTS, ObservationState.PASS);
        r.finish(A2, AttemptResult.COMPLETED);
        r.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
        return r;
    }

    /** Cheap, but its mandatory TESTS evidence is {@code state}; the caller still records it as accepted. */
    Run cheapWithTests(String exec, ObservationState state) {
        Run r = run(exec);
        r.initial(A1);
        r.stage(A1, "work", 0, 5, usage(1_000, 100));
        r.evidence(A1, QualityDimension.TESTS, state);
        r.finish(A1, AttemptResult.COMPLETED);
        r.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        return r;
    }

    static ComparisonCandidate candidate(Run run, PriceBook prices) {
        return candidate(run, run.scope(), prices);
    }

    static ComparisonCandidate candidate(Run run, ScopeId scope, PriceBook prices) {
        try (run) {
            return ComparisonCandidate.of(scope, run.view(), prices, List.of());
        }
    }

    static ComparisonCandidate candidate(Run run) {
        return candidate(run, book(usd()));
    }
}
