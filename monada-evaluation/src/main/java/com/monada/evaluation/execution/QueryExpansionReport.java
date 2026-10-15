package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeEvaluationReport.fmt;
import static com.monada.evaluation.execution.RecallStressReport.metric;

import com.monada.evaluation.execution.QueryExpansion.Expansion;
import com.monada.evaluation.execution.QueryExpansion.Rule;
import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import com.monada.evaluation.execution.RecallStressReport.GroupSummary;
import com.monada.evaluation.execution.RecallStressReport.Hit;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The offline A/B comparison of issue #124: exact queries against a bounded query-side expansion, on the same
 * persisted projection, with per-query exact and candidate rankings, judged relevant and confusable ids, per-category
 * metrics, isolated per-rule effects, vetoes and an exploratory verdict per candidate; a few hard checks and,
 * separately, elapsed timing.
 *
 * <p>Only the hard checks decide the exit code. Verdicts are exploratory: the best one is
 * {@link Verdict#JUSTIFIED_FOR_FOLLOW_UP}, which changes no default and adopts nothing. {@link #render()} is
 * deterministic for fixed input; {@link #renderTiming()} is not.
 */
public record QueryExpansionReport(QueryExpansion table, RecallStressReport stress, List<CandidateResult> candidates,
                                   List<CandidateResult> rules, List<ForgeGate> gates, Map<String, Long> elapsedNanos)
        implements RecallStressEvaluationMain.Report {

    public static final String STRESS_CHECKS = "QX1-STRESS-CHECKS";
    public static final String EXACT_PARITY = "QX2-EXACT-PARITY";
    public static final String IDENTITY = "QX3-UNEXPANDED-IDENTITY";
    public static final String BOUNDS = "QX4-BOUNDED-EXPANSION";
    public static final String STORE_UNCHANGED = "QX5-STORE-UNCHANGED";
    public static final String DETERMINISM = "QX6-DETERMINISM";

    public enum Decision { COMPARISON_CHARACTERIZED, CHECKS_FAILED }

    /**
     * Exploratory verdict of one candidate. {@code REJECTED}: at least one veto. {@code NOT_JUSTIFIED}: no veto but no
     * scored query improved. {@code JUSTIFIED_FOR_FOLLOW_UP}: no veto and at least one improvement; it only justifies
     * a separate, explicitly approved follow-up and never changes a default. There is deliberately no adopt value.
     */
    public enum Verdict { REJECTED, NOT_JUSTIFIED, JUSTIFIED_FOR_FOLLOW_UP }

    /**
     * Effect on one scored query, from the deltas of Hit@1, RR and Recall@K (deepest K): {@code IMPROVED} (some up,
     * none down), {@code REGRESSED} (some down, none up), {@code MIXED}, {@code UNCHANGED}; {@code NOT_SCORED} for
     * queries with nothing relevant.
     */
    public enum Effect { IMPROVED, REGRESSED, MIXED, UNCHANGED, NOT_SCORED }

    /** A declared set of enabled rules. */
    public record Candidate(String name, String description, Set<String> ruleIds) {
        public Candidate {
            ruleIds = Collections.unmodifiableSet(new LinkedHashSet<>(ruleIds));
        }
    }

    /** A reason a candidate cannot be recommended, with the query that triggered it. */
    public record Veto(String code, String queryId, String detail) {
    }

    /** One query compared: its expansion, the exact and candidate outcomes (same judgments) and what changed. */
    public record QueryComparison(Expansion expansion, QueryOutcome exact, QueryOutcome candidate, Effect effect,
                                  List<Hit> falseSharing, List<String> confusableRises) {
        public QueryComparison {
            falseSharing = List.copyOf(falseSharing);
            confusableRises = List.copyOf(confusableRises);
        }

        public String id() {
            return exact.query().id();
        }

        /** Hit@1, RR and Recall@K deltas as text; only meaningful for scored queries. */
        public String deltas() {
            int first = exact.hitAt().keySet().iterator().next();
            int deepest = exact.recallAt().keySet().stream().reduce((a, b) -> b).orElseThrow();
            return fmt("dHit@%d=%+.2f dRR=%+.4f dR@%d=%+.2f", first,
                    candidate.hitAt().get(first) - exact.hitAt().get(first),
                    candidate.reciprocalRank() - exact.reciprocalRank(), deepest,
                    candidate.recallAt().get(deepest) - exact.recallAt().get(deepest));
        }
    }

    /** Every query of one candidate with its vetoes and verdict. */
    public record CandidateResult(Candidate candidate, List<QueryComparison> rows, List<Veto> vetoes,
                                  Verdict verdict) {
        public CandidateResult {
            rows = List.copyOf(rows);
            vetoes = List.copyOf(vetoes);
        }

        public QueryComparison row(String queryId) {
            return rows.stream().filter(r -> r.id().equals(queryId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown query " + queryId));
        }

        public List<QueryComparison> expanded() {
            return rows.stream().filter(r -> r.expansion().changed()).toList();
        }

        public List<String> idsWith(Effect effect) {
            return rows.stream().filter(r -> r.effect() == effect).map(QueryComparison::id).toList();
        }

        public int falseSharingHits() {
            return rows.stream().mapToInt(r -> r.falseSharing().size()).sum();
        }

        public Set<String> vetoCodes() {
            return vetoes.stream().map(Veto::code).collect(Collectors.toCollection(java.util.TreeSet::new));
        }
    }

    public QueryExpansionReport {
        candidates = List.copyOf(candidates);
        rules = List.copyOf(rules);
        gates = List.copyOf(gates);
        elapsedNanos = Collections.unmodifiableMap(new LinkedHashMap<>(elapsedNanos));
    }

    public Decision decision() {
        return gates.stream().allMatch(ForgeGate::passed) ? Decision.COMPARISON_CHARACTERIZED
                : Decision.CHECKS_FAILED;
    }

    @Override
    public boolean allGatesPassed() {
        return decision() == Decision.COMPARISON_CHARACTERIZED;
    }

    public List<String> failedGates() {
        return gates.stream().filter(g -> !g.passed()).map(ForgeGate::id).toList();
    }

    public static Set<String> expectedGateIds() {
        return Set.of(STRESS_CHECKS, EXACT_PARITY, IDENTITY, BOUNDS, STORE_UNCHANGED, DETERMINISM);
    }

    public CandidateResult candidate(String name) {
        return candidates.stream().filter(c -> c.candidate().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown candidate " + name));
    }

    public CandidateResult rule(String id) {
        return rules.stream().filter(c -> c.candidate().name().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown rule " + id));
    }

    /** Per-category metrics of a candidate's outcomes, computed exactly as the #122 report computes them. */
    List<GroupSummary> groups(CandidateResult c) {
        return new RecallStressReport(stress.fixtureVersion(), stress.provenance(), stress.judgmentProtocol(),
                stress.reviewer(), stress.experienceCount(), stress.projectCount(), stress.recallLimit(), stress.ks(),
                c.rows().stream().map(QueryComparison::candidate).toList(), List.of(), Map.of()).groups();
    }

    private int deepestK() {
        return stress.deepestK();
    }

    /** Deterministic report text; contains no timing. */
    @Override
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("Forge Query-Side Lexical Expansion Comparison (issue #124)\n");
        out.append("Expansion table: forge-query-expansion v").append(table.version()).append(" — ")
                .append(table.provenance()).append('\n');
        out.append(fmt("Bounds: %d rules (cap %d); at most %d terms per rule; at most %d terms added per query\n",
                table.rules().size(), table.maxRules(), table.maxTermsPerRule(), table.maxAddedTermsPerQuery()));
        out.append("Leakage: ").append(table.leakage()).append('\n');
        out.append(fmt("Judged set: forge-recall-stress v%s over forge-experience-v1: %d experiences, %d queries; "
                + "recall limit %d; K = %s\n", stress.fixtureVersion(), stress.experienceCount(),
                stress.queries().size(), stress.recallLimit(),
                stress.ks().stream().map(String::valueOf).collect(Collectors.joining(","))));
        out.append("Method: the corpus is recorded once into a scratch store; every query is recalled through "
                + "ExecutionMemory.recall (read-only, production defaults, unchanged) with its exact text and with "
                + "each candidate's expanded text on the SAME persisted projection. The expansion lives only in this "
                + "evaluation: stored atoms, summary-text-v1, the encoder and the query keys are untouched. "
                + "Judgments are those of the source query.\n");
        out.append(fmt("Reading guide: metrics and verdicts are EXPLORATORY; only the hard checks decide. Vetoes: V1 "
                + "a scored BASELINE/NEGATION/RELATION/DUPLICATE_SUMMARY query loses Hit@1, RR or R@%1$d; V2 any other "
                + "scored query loses RR or R@%1$d; V3 a judged confusable rises within the top %1$d; V4 a non-blank "
                + "query with nothing relevant gets a new top-%1$d neighbour or a top-%1$d wrong match gains "
                + "similarity. False "
                + "sharing = a non-relevant positive-similarity hit that enters the candidate top %1$d. The best "
                + "verdict is JUSTIFIED_FOR_FOLLOW_UP; nothing is ever adopted here.\n", deepestK()));

        out.append("\n== Term rules (risk and rationale declared before the comparison ran) ==\n");
        for (Rule r : table.rules()) {
            out.append(fmt("  %s %-6s %-12s -> %-22s %s\n", r.id(), r.risk(), r.source(), String.join(" ", r.added()),
                    r.rationale()));
        }

        out.append("\n== Candidate verdicts ==\n");
        for (CandidateResult c : candidates) {
            appendVerdict(out, c);
        }

        out.append("\n== Exploratory metrics per category: exact vs candidates (mean over scored queries) ==\n");
        List<GroupSummary> exactGroups = stress.groups();
        List<List<GroupSummary>> candidateGroups = candidates.stream().map(this::groups).toList();
        int first = stress.ks().get(0);
        int deepest = deepestK();
        StringBuilder header = new StringBuilder(fmt("  %-18s %6s", "group", "scored"));
        for (String side : sides()) {
            header.append(fmt(" | %-8s %6s %6s %6s", side, "Hit@" + first, "R@" + deepest, "MRR"));
        }
        out.append(header).append('\n');
        for (int i = 0; i < exactGroups.size(); i++) {
            GroupSummary g = exactGroups.get(i);
            out.append(fmt("  %-18s %6d", g.name(), g.scoredIds().size()));
            List<GroupSummary> row = new ArrayList<>();
            row.add(g);
            for (List<GroupSummary> cg : candidateGroups) {
                row.add(cg.get(i));
            }
            for (int s = 0; s < row.size(); s++) {
                GroupSummary x = row.get(s);
                out.append(fmt(" | %-8s %6s %6s %6s", "", metric(x.hitAt().get(first)),
                        metric(x.recallAt().get(deepest)), metric(x.mrr())));
            }
            out.append('\n');
        }

        out.append("\n== Isolated per-rule effects (each rule alone against exact) ==\n");
        out.append(fmt("  %-4s %-6s %-12s %-28s %-18s %-18s %5s %-11s %s\n", "rule", "risk", "source", "fired on",
                "improved", "regressed", "share", "vetoes", "verdict"));
        for (CandidateResult c : rules) {
            Rule r = table.rule(c.candidate().name());
            List<String> fired = c.rows().stream().filter(x -> x.expansion().fired().contains(r.id()))
                    .map(QueryComparison::id).toList();
            out.append(fmt("  %-4s %-6s %-12s %-28s %-18s %-18s %5d %-11s %s\n", r.id(), r.risk(), r.source(),
                    list(fired), list(c.idsWith(Effect.IMPROVED)), list(join(c.idsWith(Effect.REGRESSED),
                            c.idsWith(Effect.MIXED))), c.falseSharingHits(), list(List.copyOf(c.vetoCodes())),
                    c.verdict()));
        }
        for (CandidateResult c : rules) {
            if (!c.vetoes().isEmpty()) {
                out.append("  ").append(c.candidate().name()).append(" vetoes:");
                c.vetoes().forEach(v -> out.append(fmt(" %s %s (%s);", v.code(), v.queryId(), v.detail())));
                out.append('\n');
            }
        }

        out.append("\n== Per-query side-by-side rankings ==\n");
        for (QueryOutcome exact : stress.queries()) {
            appendQuery(out, exact.query().id());
        }

        out.append("\n== Hard checks (any failure blocks the decision) ==\n");
        for (ForgeGate g : gates) {
            out.append(fmt("  [%s] %s — %s\n", g.passed() ? "PASS" : "FAIL", g.id(), g.name()));
            for (String d : g.details()) {
                out.append("        ").append(d).append('\n');
            }
        }

        out.append("\n== Decision ==\n");
        Decision d = decision();
        out.append("  ").append(d).append('\n');
        if (d == Decision.COMPARISON_CHARACTERIZED) {
            out.append("  Every hard check passed: the exact side reproduces #122, unexpanded queries rank unchanged, "
                    + "expansions stay within their bounds, the store files are byte-identical and two fresh stores "
                    + "agree.\n");
            for (CandidateResult c : candidates) {
                out.append(fmt("  %s: %s\n", c.candidate().name(), conclusion(c)));
            }
            out.append("  No candidate is adopted: no encoder, projection, query key or default changes, and any "
                    + "follow-up needs its own approved Issue with held-out evidence.\n");
        } else {
            out.append("  At least one hard check failed: the comparison is not trustworthy until it is fixed.\n");
        }
        return out.toString();
    }

    private List<String> sides() {
        List<String> out = new ArrayList<>();
        out.add("EXACT");
        candidates.forEach(c -> out.add(c.candidate().name()));
        return out;
    }

    private void appendVerdict(StringBuilder out, CandidateResult c) {
        out.append(fmt("  %s — %s (%d rules): %s\n", c.candidate().name(), c.candidate().description(),
                c.candidate().ruleIds().size(), c.verdict()));
        out.append(fmt("    expanded queries: %d; improved: %s; regressed: %s; mixed: %s; false-sharing hits: %d\n",
                c.expanded().size(), list(c.idsWith(Effect.IMPROVED)), list(c.idsWith(Effect.REGRESSED)),
                list(c.idsWith(Effect.MIXED)), c.falseSharingHits()));
        if (c.vetoes().isEmpty()) {
            out.append("    vetoes: none\n");
        }
        for (Veto v : c.vetoes()) {
            out.append(fmt("    veto %s %s: %s\n", v.code(), v.queryId(), v.detail()));
        }
    }

    private static String conclusion(CandidateResult c) {
        return switch (c.verdict()) {
            case REJECTED -> "REJECTED by " + String.join(", ", c.vetoCodes()) + " (" + c.vetoes().size()
                    + " vetoes); the aggregate gain does not justify it";
            case NOT_JUSTIFIED -> "NOT_JUSTIFIED: no veto, but no scored query improved";
            case JUSTIFIED_FOR_FOLLOW_UP -> "JUSTIFIED_FOR_FOLLOW_UP on this in-sample set only (no veto; improved "
                    + String.join(", ", c.idsWith(Effect.IMPROVED)) + "); not adopted";
        };
    }

    private void appendQuery(StringBuilder out, String id) {
        QueryOutcome exact = stress.outcome(id);
        StressQuery s = exact.query();
        String kind = s.category() == Category.BASELINE ? "BASELINE/" + s.kind() : s.category().name();
        out.append(fmt("\nQuery %s [%s, %s] \"%s\"\n", s.id(), kind, s.scopeKey(), s.text().replace("\t", "\\t")));
        out.append("  Relevant: ").append(s.relevant().isEmpty() ? "- (none; excluded from metrics)"
                : String.join(", ", s.relevant())).append('\n');
        out.append("  Confusable: ").append(s.confusable().isEmpty() ? "-" : String.join(", ", s.confusable()))
                .append('\n');
        out.append("  EXACT").append(summary(exact)).append('\n');
        appendHits(out, exact);
        for (CandidateResult c : candidates) {
            QueryComparison r = c.row(id);
            Expansion e = r.expansion();
            if (!e.changed()) {
                out.append(fmt("  %s: not expanded (%s); ranking identical to EXACT\n", c.candidate().name(),
                        e.fired().isEmpty() ? "no rule fired" : "fired " + String.join(",", e.fired())
                                + " added nothing new"));
                continue;
            }
            out.append(fmt("  %s: \"%s\" (fired %s; added %s)\n", c.candidate().name(), e.expanded(),
                    String.join(",", e.fired()), String.join(" ", e.added())));
            out.append("  ").append(c.candidate().name()).append(summary(r.candidate()));
            out.append(fmt("; effect %s%s\n", r.effect(), r.exact().scored() ? " (" + r.deltas() + ")" : ""));
            appendHits(out, r.candidate());
            if (!r.falseSharing().isEmpty()) {
                out.append("    false sharing: ").append(r.falseSharing().stream()
                        .map(h -> fmt("%s %s sim=%.4f", h.experienceId(), h.neighbour(), h.similarity()))
                        .collect(Collectors.joining("; "))).append('\n');
            }
            if (!r.confusableRises().isEmpty()) {
                out.append("    confusable rises: ").append(String.join("; ", r.confusableRises())).append('\n');
            }
        }
    }

    private String summary(QueryOutcome q) {
        StringBuilder out = new StringBuilder(fmt(" reason %s; hits %d", q.reason(), q.hits().size()));
        if (q.scored()) {
            int first = stress.ks().get(0);
            out.append(fmt("; Hit@%d=%.2f R@%d=%.2f RR=%.4f", first, q.hitAt().get(first), deepestK(),
                    q.recallAt().get(deepestK()), q.reciprocalRank()));
        }
        return out.toString();
    }

    private void appendHits(StringBuilder out, QueryOutcome q) {
        for (Hit h : q.hits().stream().limit(deepestK()).toList()) {
            out.append(fmt("    #%-2d %-12s sim=%.4f%s %s\n", h.rank(), h.experienceId(), h.similarity(),
                    h.tie() ? "=" : " ", h.neighbour()));
        }
    }

    private static List<String> join(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String list(List<String> ids) {
        return ids.isEmpty() ? "-" : String.join(",", ids);
    }

    /** Elapsed wall-clock timing. Nondeterministic by nature and never part of {@link #render()}. */
    @Override
    public String renderTiming() {
        return RecallStressReport.renderTiming(elapsedNanos);
    }
}
