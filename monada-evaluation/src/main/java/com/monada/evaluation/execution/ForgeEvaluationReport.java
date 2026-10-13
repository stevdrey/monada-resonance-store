package com.monada.evaluation.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The result of one Forge experience evaluation (issue #102): hard correctness gates, exploratory retrieval
 * quality, a decision and, separately, nondeterministic elapsed timing.
 *
 * <p>Only the gates decide. Quality values are EXPLORATORY: they are reported, never enforced and never frozen as
 * a protected baseline. {@link #render()} is deterministic for fixed input; {@link #renderTiming()} is not.
 */
public record ForgeEvaluationReport(String fixtureVersion, String provenance, int experienceCount, int projectCount,
                                    List<ForgeQueryResult> queries, List<ForgeGate> gates,
                                    List<ForgeCorpusLine> corpus, Map<String, Long> elapsedNanos) {

    /** Documented decision criteria (issue #102): only hard correctness and isolation gates count. */
    public enum Decision { READY_FOR_HOST_ADAPTER_EXPERIMENT, GAPS_REQUIRE_FOLLOW_UP }

    /** One experience with its judged label, for the report's corpus section. */
    public record ForgeCorpusLine(String experienceId, String scopeKey, String attemptResult, String label,
                                  String acceptance, String cost) {
    }

    /** One ranked hit of a query. */
    public record RankedHit(int rank, String experienceId, double similarity, String attemptResult, String label,
                            int revision, boolean relevant) {
    }

    public ForgeEvaluationReport {
        Objects.requireNonNull(fixtureVersion, "fixtureVersion");
        queries = List.copyOf(queries);
        gates = List.copyOf(gates);
        corpus = List.copyOf(corpus);
        elapsedNanos = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(elapsedNanos));
    }

    public Decision decision() {
        return gates.stream().allMatch(ForgeGate::passed) ? Decision.READY_FOR_HOST_ADAPTER_EXPERIMENT
                : Decision.GAPS_REQUIRE_FOLLOW_UP;
    }

    public boolean allGatesPassed() {
        return decision() == Decision.READY_FOR_HOST_ADAPTER_EXPERIMENT;
    }

    /** Queries that enter the quality averages: a non-empty relevant set. */
    public List<ForgeQueryResult> scoredQueries() {
        return queries.stream().filter(q -> !q.query().relevant().isEmpty()).toList();
    }

    /** Deterministic report text; contains no timing. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("Forge Experience Evaluation (issue #102)\n");
        out.append("Fixture: forge-experience v").append(fixtureVersion).append(" — ").append(provenance).append('\n');
        out.append("Corpus: ").append(experienceCount).append(" experiences in ").append(projectCount)
                .append(" projects; ").append(queries.size()).append(" judged queries\n");
        out.append("Reading guide: gates are hard correctness/isolation checks and decide the outcome; quality "
                + "values are EXPLORATORY (not enforced, not a protected baseline).\n\n");

        out.append("== Corpus labels (attempt = what this attempt did; label/acceptance/cost = its execution, "
                + "contract section 8) ==\n");
        for (ForgeCorpusLine c : corpus) {
            out.append(String.format(Locale.ROOT, "  %-12s %-5s attempt=%-9s label=%-9s acceptance=%-20s cost=%s%n",
                    c.experienceId(), c.scopeKey(), c.attemptResult(), c.label(), c.acceptance(), c.cost()));
        }

        out.append("\n== Hard correctness gates (protected semantics: any failure blocks the decision) ==\n");
        for (ForgeGate g : gates) {
            out.append(String.format(Locale.ROOT, "  [%s] %s — %s%n", g.passed() ? "PASS" : "FAIL", g.id(), g.name()));
            for (String d : g.details()) {
                out.append("        ").append(d).append('\n');
            }
        }

        out.append("\n== Exploratory retrieval quality (NOT enforced, NOT frozen as a baseline) ==\n");
        out.append("Relevance semantics: Precision/Recall/Hit/MRR are averaged over queries with a non-empty judged "
                + "relevant set. NO_RELEVANT and BLANK queries are reported but excluded from every denominator; a "
                + "blank query must return no hits. A failed experience may be relevant; it is then labelled "
                + "FAILURE and is never a success.\n");
        for (ForgeQueryResult q : queries) {
            appendQuery(out, q);
        }
        appendAggregates(out);

        out.append("\n== Decision ==\n");
        Decision d = decision();
        out.append("  ").append(d).append('\n');
        out.append(d == Decision.READY_FOR_HOST_ADAPTER_EXPERIMENT
                ? "  Every hard gate passed. This only authorizes a bounded host-adapter experiment; it does not "
                + "claim any routing benefit, and exploratory quality did not influence the decision.\n"
                : "  At least one hard gate failed: fix the listed gaps before any host-adapter experiment.\n");
        return out.toString();
    }

    private void appendQuery(StringBuilder out, ForgeQueryResult q) {
        ForgeFixture.Query query = q.query();
        out.append(String.format(Locale.ROOT, "%nQuery %s [%s, %s] \"%s\"%n", query.id(), query.kind(),
                query.scopeKey(), query.text().isBlank() ? "<blank>" : query.text()));
        out.append("  Judgment: ").append(query.judgment()).append('\n');
        out.append("  Relevant: ").append(query.relevant().isEmpty() ? "(none)" : String.join(", ",
                query.relevant().stream().sorted().toList())).append('\n');
        out.append("  Projection: ").append(q.projectionState()).append("; hits=").append(q.ranked().size())
                .append('\n');
        int shown = Math.min(q.ranked().size(), 5);
        for (int i = 0; i < shown; i++) {
            RankedHit h = q.ranked().get(i);
            out.append(String.format(Locale.ROOT, "  #%d %-12s sim=%.4f attempt=%-9s execution=%-9s rev=%d%s%n",
                    h.rank(), h.experienceId(), h.similarity(), h.attemptResult(), h.label(), h.revision(),
                    h.relevant() ? " RELEVANT" : ""));
        }
        if (query.relevant().isEmpty()) {
            out.append(q.ranked().isEmpty() ? "  Excluded from averages: no relevant set, no hits returned.\n"
                    : "  Excluded from averages: empty relevant set (nothing is relevant by judgment); the hits above "
                    + "are lexical neighbours only.\n");
            return;
        }
        for (int k : q.metricKs()) {
            out.append(String.format(Locale.ROOT, "  @%d precision=%.4f recall=%.4f hit=%.0f%n", k,
                    q.precision().get(k), q.recall().get(k), q.hit().get(k)));
        }
        out.append(String.format(Locale.ROOT, "  Reciprocal rank=%.4f%n", q.reciprocalRank()));
        if (q.missing().isEmpty()) {
            out.append("  Missing relevant (outside top ").append(q.metricKs().get(q.metricKs().size() - 1))
                    .append("): none\n");
        } else {
            out.append("  Missing relevant (outside top ").append(q.metricKs().get(q.metricKs().size() - 1))
                    .append("):\n");
            q.missing().forEach((id, diagnosis) -> out.append("    ").append(id).append(": ").append(diagnosis)
                    .append('\n'));
        }
    }

    private void appendAggregates(StringBuilder out) {
        List<ForgeQueryResult> scored = scoredQueries();
        out.append(String.format(Locale.ROOT, "%nAggregates over %d scored queries (%d excluded: no relevant set)%n",
                scored.size(), queries.size() - scored.size()));
        if (scored.isEmpty()) {
            return;
        }
        List<Integer> ks = scored.get(0).metricKs();
        for (int k : ks) {
            out.append(String.format(Locale.ROOT, "  @%d  avg precision=%.4f  avg recall=%.4f  avg hit=%.4f%n", k,
                    mean(scored, q -> q.precision().get(k)), mean(scored, q -> q.recall().get(k)),
                    mean(scored, q -> q.hit().get(k))));
        }
        out.append(String.format(Locale.ROOT, "  MRR=%.4f%n", mean(scored, ForgeQueryResult::reciprocalRank)));
        out.append("Worst cases (lowest value; first query in fixture order on ties):\n");
        for (int k : ks) {
            out.append(worst("Precision@" + k, scored, q -> q.precision().get(k)));
            out.append(worst("Recall@" + k, scored, q -> q.recall().get(k)));
            out.append(worst("Hit@" + k, scored, q -> q.hit().get(k)));
        }
        out.append(worst("ReciprocalRank", scored, ForgeQueryResult::reciprocalRank));
    }

    private static String worst(String metric, List<ForgeQueryResult> scored,
                                java.util.function.ToDoubleFunction<ForgeQueryResult> f) {
        ForgeQueryResult worst = scored.get(0);
        for (ForgeQueryResult q : scored) {
            if (f.applyAsDouble(q) < f.applyAsDouble(worst)) {
                worst = q;
            }
        }
        double best = scored.stream().mapToDouble(f).max().orElse(0);
        return String.format(Locale.ROOT, "  %-14s worst=%.4f (%s)  best=%.4f  gap=%.4f%n", metric,
                f.applyAsDouble(worst), worst.query().id(), best, best - f.applyAsDouble(worst));
    }

    private static double mean(List<ForgeQueryResult> queries,
                               java.util.function.ToDoubleFunction<ForgeQueryResult> f) {
        return queries.stream().mapToDouble(f).average().orElse(0);
    }

    /** Elapsed wall-clock timing. Nondeterministic by nature and never part of {@link #render()}. */
    public String renderTiming() {
        StringBuilder out = new StringBuilder("== Elapsed timing (nondeterministic; excluded from every gate) ==\n");
        new LinkedHashMap<>(elapsedNanos).forEach((phase, nanos) -> out.append(String.format(Locale.ROOT,
                "  %-24s %.1f ms%n", phase, nanos / 1_000_000.0)));
        return out.toString();
    }

    /** Names of the failed gates, in gate order. */
    public List<String> failedGates() {
        List<String> failed = new ArrayList<>();
        gates.stream().filter(g -> !g.passed()).forEach(g -> failed.add(g.id()));
        return failed;
    }

    /** The gate ids a complete evaluation reports. */
    public static Set<String> expectedGateIds() {
        return Set.of(ForgeGate.SCOPE_ISOLATION, ForgeGate.NO_INVALID_SUCCESS, ForgeGate.EXACT_REFERENCES,
                ForgeGate.REPLAY_IDEMPOTENCY, ForgeGate.RESOURCE_ACCOUNTING, ForgeGate.COMPARISON,
                ForgeGate.DETERMINISM);
    }
}
