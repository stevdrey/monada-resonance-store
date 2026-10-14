package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeEvaluationReport.fmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The result of one multi-attempt evaluation (issue #121): attempt-level joins of recalled hits to exported samples,
 * hard gates, a decision and, separately, nondeterministic elapsed timing.
 *
 * <p>Only the gates decide. The hits are lexical neighbours listed as join evidence; no retrieval-quality metric is
 * computed or claimed. {@link #render()} is deterministic for fixed input; {@link #renderTiming()} is not.
 */
public record MultiAttemptEvaluationReport(String fixtureVersion, String provenance, int attemptCount,
                                           int executionCount, int projectCount, int pageSize, String cutoffScope,
                                           String cutoffEvent, long cutoffSequence, List<AttemptLine> latest,
                                           List<AttemptLine> atCutoff, List<QueryLine> queries, List<ForgeGate> gates,
                                           Map<String, Long> elapsedNanos) {

    public static final String EXACT_ATTEMPT_RESOLUTION = "MA1-EXACT-ATTEMPT-RESOLUTION";
    public static final String ATTEMPT_RESULT_JOIN = "MA2-ATTEMPT-RESULT-JOIN";
    public static final String EXPORT_ATTEMPTS = "MA3-EXPORT-ATTEMPTS";
    public static final String CUTOFF = "MA4-CUTOFF";
    public static final String SCOPE_ISOLATION = "MA5-SCOPE-ISOLATION";
    public static final String DETERMINISM = "MA6-DETERMINISM";

    public enum Decision { ATTEMPT_JOINS_VERIFIED, GAPS_REQUIRE_FOLLOW_UP }

    /**
     * One attempt as an export reports it: what the attempt did ({@code attemptResult}, finish {@code revision}),
     * whether the outcome names it ({@code markedAccepted}) and whether it is the validated accepted attempt, beside
     * the raw label and acceptance of its execution and the exact finish reference ({@code -} when unfinished).
     */
    public record AttemptLine(String experienceId, String scopeKey, String attemptResult, int revision,
                              boolean markedAccepted, boolean validatedAcceptedAttempt, String label, String acceptance,
                              String finishRef) {
    }

    /** One recalled hit joined to the latest export and, in the cutoff scope, to the cutoff export. */
    public record HitLine(int rank, double similarity, AttemptJoin.Row latest, String cutoffStatus, boolean relevant) {
    }

    public record QueryLine(String id, String scopeKey, String text, Set<String> relevant, String judgment,
                            List<HitLine> hits) {
        public QueryLine {
            hits = List.copyOf(hits);
        }
    }

    public MultiAttemptEvaluationReport {
        Objects.requireNonNull(fixtureVersion, "fixtureVersion");
        latest = List.copyOf(latest);
        atCutoff = List.copyOf(atCutoff);
        queries = List.copyOf(queries);
        gates = List.copyOf(gates);
        elapsedNanos = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(elapsedNanos));
    }

    public Decision decision() {
        return gates.stream().allMatch(ForgeGate::passed) ? Decision.ATTEMPT_JOINS_VERIFIED
                : Decision.GAPS_REQUIRE_FOLLOW_UP;
    }

    public boolean allGatesPassed() {
        return decision() == Decision.ATTEMPT_JOINS_VERIFIED;
    }

    /** Names of the failed gates, in gate order. */
    public List<String> failedGates() {
        List<String> failed = new ArrayList<>();
        gates.stream().filter(g -> !g.passed()).forEach(g -> failed.add(g.id()));
        return failed;
    }

    /** The gate ids a complete evaluation reports. */
    public static Set<String> expectedGateIds() {
        return Set.of(EXACT_ATTEMPT_RESOLUTION, ATTEMPT_RESULT_JOIN, EXPORT_ATTEMPTS, CUTOFF, SCOPE_ISOLATION,
                DETERMINISM);
    }

    /** Deterministic report text; contains no timing. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("Forge Multi-Attempt Evaluation (issue #121)\n");
        out.append("Fixture: forge-multi-attempt v").append(fixtureVersion).append(" — ").append(provenance)
                .append('\n');
        out.append(fmt("Corpus: %d attempts in %d executions over %d projects; %d judged queries; export page size %d\n",
                attemptCount, executionCount, projectCount, queries.size(), pageSize));
        out.append(fmt("Cutoff export: %s, ledger prefix ending with %s (sequence %d)\n", cutoffScope, cutoffEvent,
                cutoffSequence));
        out.append("Reading guide: label/acceptance are the raw values of the attempt's EXECUTION (contract section 8, "
                + "unchanged); attempt/rev say what the ATTEMPT did; outcome-names says whether the recorded outcome "
                + "names the attempt; validated-accepted is true only for the COMPLETED attempt a validated acceptance "
                + "names. Gates decide; hits are lexical neighbours listed as join evidence, not a quality claim.\n");

        out.append("\n== Attempts in the latest export (one row per attempt) ==\n");
        appendAttempts(out, latest);
        out.append("\n== Attempts in the cutoff export (").append(cutoffScope).append(", up to sequence ")
                .append(cutoffSequence).append(") ==\n");
        appendAttempts(out, atCutoff);

        out.append("\n== Recalled hits joined to exported samples by execution id ==\n");
        for (QueryLine q : queries) {
            out.append(fmt("\nQuery %s [%s] \"%s\"\n", q.id(), q.scopeKey(), q.text()));
            out.append("  Judgment: ").append(q.judgment()).append('\n');
            out.append("  Relevant: ").append(String.join(", ", q.relevant().stream().sorted().toList())).append('\n');
            out.append("  Hits: ").append(q.hits().size()).append('\n');
            for (HitLine h : q.hits()) {
                AttemptJoin.Row r = h.latest();
                out.append(fmt("  #%-2d %-15s sim=%.4f ref=%s r%d @%d %s attempt=%-9s validated-accepted=%-3s "
                                + "execution=%s/%s cutoff=%s%s\n",
                        h.rank(), r.experienceId(), h.similarity(), r.eventId(), r.revision(), r.sequence(),
                        r.status(), r.attemptResult(), yes(r.validatedAcceptedAttempt()), r.executionLabel(),
                        r.executionAcceptance(), h.cutoffStatus(), h.relevant() ? " RELEVANT" : ""));
            }
        }

        out.append("\n== Hard gates (any failure blocks the decision) ==\n");
        for (ForgeGate g : gates) {
            out.append(fmt("  [%s] %s — %s\n", g.passed() ? "PASS" : "FAIL", g.id(), g.name()));
            for (String d : g.details()) {
                out.append("        ").append(d).append('\n');
            }
        }

        out.append("\n== Decision ==\n");
        Decision d = decision();
        out.append("  ").append(d).append('\n');
        out.append(d == Decision.ATTEMPT_JOINS_VERIFIED
                ? "  Every gate passed: each hit resolves to its exact attempt event and joins to its execution's "
                + "sample without turning an execution label into an attempt verdict. No export schema, label or "
                + "acceptance semantics changed, and no quality or routing benefit is claimed.\n"
                : "  At least one gate failed: fix the listed gaps before relying on attempt-level joins.\n");
        return out.toString();
    }

    private static void appendAttempts(StringBuilder out, List<AttemptLine> lines) {
        out.append(fmt("  %-15s %-5s %-10s %-3s %-13s %-18s %-9s %-20s %s\n", "attempt", "scope", "result", "rev",
                "outcome-names", "validated-accepted", "label", "acceptance", "finish ref"));
        for (AttemptLine a : lines) {
            out.append(fmt("  %-15s %-5s %-10s %-3s %-13s %-18s %-9s %-20s %s\n", a.experienceId(), a.scopeKey(),
                    a.attemptResult(), a.revision() == 0 ? "-" : "r" + a.revision(), yes(a.markedAccepted()),
                    yes(a.validatedAcceptedAttempt()), a.label(), a.acceptance(), a.finishRef()));
        }
    }

    private static String yes(boolean value) {
        return value ? "yes" : "no";
    }

    /** Elapsed wall-clock timing. Nondeterministic by nature and never part of {@link #render()}. */
    public String renderTiming() {
        StringBuilder out = new StringBuilder("== Elapsed timing (nondeterministic; excluded from every gate) ==\n");
        new LinkedHashMap<>(elapsedNanos).forEach((phase, nanos) -> out.append(String.format(Locale.ROOT,
                "  %-24s %.1f ms\n", phase, nanos / 1_000_000.0)));
        return out.toString();
    }
}
