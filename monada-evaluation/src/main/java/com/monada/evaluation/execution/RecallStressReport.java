package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeEvaluationReport.fmt;

import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The result of the judged Forge recall stress set (issue #122): per-query ranks, scores, misses, failure reason and
 * false-neighbour categories, EXPLORATORY metrics per category, a few hard checks and, separately, nondeterministic
 * elapsed timing.
 *
 * <p>Only the hard checks decide the exit code; no metric has a threshold and none is a protected baseline.
 * {@link #render()} is deterministic for fixed input; {@link #renderTiming()} is not.
 */
public record RecallStressReport(String fixtureVersion, String provenance, String judgmentProtocol, String reviewer,
                                 int experienceCount, int projectCount, int recallLimit, List<Integer> ks,
                                 List<QueryOutcome> queries, List<ForgeGate> gates, Map<String, Long> elapsedNanos) {

    public static final String SCOPED_RECALL = "RS1-SCOPED-RECALL";
    public static final String BLANK_QUERY = "RS2-BLANK-QUERY-NO-HITS";
    public static final String DETERMINISM = "RS3-DETERMINISM";
    public static final String BASELINE_PARITY = "RS4-BASELINE-102-PARITY";

    public enum Decision { STRESS_SET_CHARACTERIZED, CHECKS_FAILED }

    /**
     * What a recalled hit is with respect to the judgments of its query, in precedence order: judged relevant,
     * judged confusable, another attempt of an execution that has a relevant attempt, an attempt of another execution
     * of a relevant task, a positive-similarity neighbour nobody judged, or a zero-similarity fill (recall has no
     * relevance floor and fills up to the requested limit).
     */
    public enum Neighbour {
        RELEVANT, JUDGED_CONFUSABLE, SAME_EXECUTION, SAME_TASK, LEXICAL_NEIGHBOUR, ZERO_SIMILARITY;

        public boolean falseNeighbour() {
            return this != RELEVANT;
        }
    }

    /**
     * Per-query failure taxonomy, derived only from observed ranks and the hand judgments. With relevant
     * experiences: {@code RESOLVED} (a relevant hit at #1 and every relevant one within the deepest K),
     * {@code PARTIAL_RECALL} (relevant at #1, some relevant deeper or missing), {@code OUTRANKED} (first relevant
     * within the deepest K but below #1), {@code NOT_IN_TOP_K} (first relevant deeper than K),
     * {@code ZERO_SIMILARITY_ONLY} (relevant experiences appear only as zero-similarity limit fill, so their ranks
     * are tie-order artefacts, not lexical matches) and {@code NOT_RETRIEVED}. Without: {@code EMPTY_AS_EXPECTED} or
     * {@code FALSE_NEIGHBOURS_RETURNED}. Rank-based metrics still count fill hits, exactly as the #102 report does.
     */
    public enum Reason {
        RESOLVED, PARTIAL_RECALL, OUTRANKED, NOT_IN_TOP_K, ZERO_SIMILARITY_ONLY, NOT_RETRIEVED, EMPTY_AS_EXPECTED,
        FALSE_NEIGHBOURS_RETURNED
    }

    /** One ranked hit; {@code tie} marks a similarity equal to the previous hit's (order then follows atom id). */
    public record Hit(int rank, String experienceId, double similarity, boolean tie, Neighbour neighbour) {
    }

    /** Where a relevant experience landed; rank 0 means it was not retrieved within the recall limit. */
    public record RelevantPosition(String experienceId, int rank, double similarity) {
        public boolean retrieved() {
            return rank > 0;
        }
    }

    /** One query with its full ranking and, when its relevant set is non-empty, its metrics. */
    public record QueryOutcome(StressQuery query, String projectionState, List<Hit> hits,
                               List<RelevantPosition> relevantPositions, Map<Integer, Double> hitAt,
                               Map<Integer, Double> recallAt, double reciprocalRank, Reason reason,
                               Optional<Neighbour> leadNeighbour) {
        public QueryOutcome {
            hits = List.copyOf(hits);
            relevantPositions = List.copyOf(relevantPositions);
            hitAt = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(hitAt));
            recallAt = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(recallAt));
        }

        public boolean scored() {
            return !query.relevant().isEmpty();
        }

        public List<String> rankedIds() {
            return hits.stream().map(Hit::experienceId).toList();
        }
    }

    /** Metrics of one group of queries; queries with an empty relevant set are listed and excluded. */
    public record GroupSummary(String name, int queries, List<String> scoredIds, List<String> excludedIds,
                               Map<Integer, Double> hitAt, Map<Integer, Double> recallAt, double mrr,
                               Map<Reason, Integer> reasons, Map<Neighbour, Integer> falseNeighboursInTopK) {
    }

    public RecallStressReport {
        Objects.requireNonNull(fixtureVersion, "fixtureVersion");
        ks = List.copyOf(ks);
        queries = List.copyOf(queries);
        gates = List.copyOf(gates);
        elapsedNanos = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(elapsedNanos));
    }

    public Decision decision() {
        return gates.stream().allMatch(ForgeGate::passed) ? Decision.STRESS_SET_CHARACTERIZED : Decision.CHECKS_FAILED;
    }

    public boolean allGatesPassed() {
        return decision() == Decision.STRESS_SET_CHARACTERIZED;
    }

    public List<String> failedGates() {
        return gates.stream().filter(g -> !g.passed()).map(ForgeGate::id).toList();
    }

    public static Set<String> expectedGateIds() {
        return Set.of(SCOPED_RECALL, BLANK_QUERY, DETERMINISM, BASELINE_PARITY);
    }

    public QueryOutcome outcome(String queryId) {
        return queries.stream().filter(q -> q.query().id().equals(queryId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown query " + queryId));
    }

    int deepestK() {
        return ks.get(ks.size() - 1);
    }

    /** Groups in report order: BASELINE, every stress category, then all stress queries together. */
    public List<GroupSummary> groups() {
        List<GroupSummary> out = new ArrayList<>();
        for (Category c : Category.values()) {
            out.add(summarize(c.name(), queries.stream().filter(q -> q.query().category() == c).toList()));
        }
        out.add(summarize("ALL_STRESS", queries.stream()
                .filter(q -> q.query().category() != Category.BASELINE).toList()));
        return out;
    }

    GroupSummary summarize(String name, List<QueryOutcome> group) {
        List<QueryOutcome> scored = group.stream().filter(QueryOutcome::scored).toList();
        Map<Integer, Double> hit = new LinkedHashMap<>();
        Map<Integer, Double> recall = new LinkedHashMap<>();
        for (int k : ks) {
            hit.put(k, mean(scored.stream().map(q -> q.hitAt().get(k)).toList()));
            recall.put(k, mean(scored.stream().map(q -> q.recallAt().get(k)).toList()));
        }
        Map<Reason, Integer> reasons = new EnumMap<>(Reason.class);
        group.forEach(q -> reasons.merge(q.reason(), 1, Integer::sum));
        Map<Neighbour, Integer> neighbours = new EnumMap<>(Neighbour.class);
        group.forEach(q -> q.hits().stream().limit(deepestK()).filter(h -> h.neighbour().falseNeighbour())
                .forEach(h -> neighbours.merge(h.neighbour(), 1, Integer::sum)));
        return new GroupSummary(name, group.size(), scored.stream().map(q -> q.query().id()).toList(),
                group.stream().filter(q -> !q.scored()).map(q -> q.query().id()).toList(), hit, recall,
                mean(scored.stream().map(QueryOutcome::reciprocalRank).toList()), reasons, neighbours);
    }

    /** Arithmetic mean, or NaN (printed {@code n/a}) for an empty denominator; never a silent zero. */
    static double mean(List<Double> values) {
        return values.isEmpty() ? Double.NaN : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    /** Deterministic report text; contains no timing. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("Forge Recall Stress Set (issue #122)\n");
        out.append("Fixture: forge-recall-stress v").append(fixtureVersion).append(" — ").append(provenance)
                .append('\n');
        out.append(fmt("Corpus: forge-experience-v1 (unchanged, issue #102): %d experiences over %d projects; "
                + "%d judged queries; recall limit %d; K = %s\n", experienceCount, projectCount, queries.size(),
                recallLimit, ks.stream().map(String::valueOf).collect(Collectors.joining(","))));
        out.append("Recall under test: ExecutionMemory.recall with projection summary-text-v1, the default lexical "
                + "encoder and exact query keys (production defaults, unchanged).\n");
        out.append("Relevance judgments: ").append(judgmentProtocol).append('\n');
        out.append("Reviewer: ").append(reviewer).append('\n');
        out.append("Reading guide: metrics are EXPLORATORY (no threshold, not a protected baseline); only the hard "
                + "checks decide. Queries with an empty relevant set are reported but excluded from every metric "
                + "denominator. Neighbour categories: RELEVANT, JUDGED_CONFUSABLE, SAME_EXECUTION, SAME_TASK, "
                + "LEXICAL_NEIGHBOUR (positive similarity, unjudged), ZERO_SIMILARITY (limit fill: recall has no relevance "
                + "floor). '=' marks a similarity tie with the hit above, ordered by atom id; '(fill)' marks a "
                + "relevant experience reached only at similarity 0, which rank metrics still count.\n");

        out.append("\n== Exploratory metrics per category (mean over queries with relevant experiences) ==\n");
        StringBuilder header = new StringBuilder(fmt("  %-18s %3s %6s %8s", "group", "n", "scored", "excluded"));
        ks.forEach(k -> header.append(fmt(" %6s", "Hit@" + k)));
        ks.forEach(k -> header.append(fmt(" %6s", "R@" + k)));
        out.append(header).append(fmt(" %6s\n", "MRR"));
        List<GroupSummary> groups = groups();
        for (GroupSummary g : groups) {
            out.append(fmt("  %-18s %3d %6d %8d", g.name(), g.queries(), g.scoredIds().size(),
                    g.excludedIds().size()));
            ks.forEach(k -> out.append(fmt(" %6s", metric(g.hitAt().get(k)))));
            ks.forEach(k -> out.append(fmt(" %6s", metric(g.recallAt().get(k)))));
            out.append(fmt(" %6s\n", metric(g.mrr())));
        }
        out.append("\n  Excluded zero-relevant queries (not in any denominator):\n");
        for (GroupSummary g : groups) {
            if (!g.excludedIds().isEmpty() && !g.name().equals("ALL_STRESS")) {
                out.append(fmt("    %-18s %s\n", g.name(), String.join(", ", g.excludedIds())));
            }
        }

        out.append("\n== Failure taxonomy per category (query counts by reason) ==\n");
        for (GroupSummary g : groups) {
            out.append(fmt("  %-18s %s\n", g.name(), counts(g.reasons())));
        }
        out.append(fmt("\n== False neighbours within the top %d per category (hit counts) ==\n", deepestK()));
        for (GroupSummary g : groups) {
            out.append(fmt("  %-18s %s\n", g.name(), counts(g.falseNeighboursInTopK())));
        }

        out.append("\n== Per-query results ==\n");
        for (QueryOutcome q : queries) {
            appendQuery(out, q);
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
        out.append(d == Decision.STRESS_SET_CHARACTERIZED
                ? "  Every hard check passed: recall stayed in scope, blank queries returned nothing, two fresh stores "
                + "ranked identically and the #102 baseline rankings are reproduced. The metrics above characterize "
                + "the current exact lexical recall; they are exploratory evidence, not a quality claim or a gate.\n"
                : "  At least one hard check failed: the characterization is not trustworthy until it is fixed.\n");
        return out.toString();
    }

    private void appendQuery(StringBuilder out, QueryOutcome q) {
        StressQuery s = q.query();
        String kind = s.category() == Category.BASELINE ? "BASELINE/" + s.kind() : s.category().name();
        out.append(fmt("\nQuery %s [%s, %s] \"%s\"\n", s.id(), kind, s.scopeKey(), printable(s.text())));
        out.append("  Judgment: ").append(s.judgment()).append('\n');
        out.append("  Relevant: ").append(s.relevant().isEmpty() ? "- (none; excluded from metrics)"
                : String.join(", ", s.relevant())).append('\n');
        out.append("  Confusable: ").append(s.confusable().isEmpty() ? "-" : String.join(", ", s.confusable()))
                .append('\n');
        out.append(fmt("  Reason: %s%s; projection %s; hits %d\n", q.reason(),
                q.leadNeighbour().map(n -> " (rank #1 is " + n + ")").orElse(""), q.projectionState(),
                q.hits().size()));
        if (q.scored()) {
            StringBuilder m = new StringBuilder("  Metrics:");
            ks.forEach(k -> m.append(fmt(" Hit@%d=%.2f", k, q.hitAt().get(k))));
            ks.forEach(k -> m.append(fmt(" R@%d=%.2f", k, q.recallAt().get(k))));
            out.append(m).append(fmt(" RR=%.4f\n", q.reciprocalRank()));
            out.append("  Relevant positions:");
            for (RelevantPosition p : q.relevantPositions()) {
                out.append(p.retrieved() ? fmt(" %s #%d sim=%.4f%s;", p.experienceId(), p.rank(), p.similarity(),
                        p.similarity() == 0.0 ? " (fill)" : "")
                        : fmt(" %s not retrieved;", p.experienceId()));
            }
            out.append('\n');
            List<String> misses = q.relevantPositions().stream().filter(p -> !p.retrieved() || p.rank() > deepestK())
                    .map(p -> p.retrieved() ? fmt("%s at #%d", p.experienceId(), p.rank())
                            : p.experienceId() + " not retrieved within " + recallLimit)
                    .toList();
            out.append(fmt("  Misses outside top %d: %s\n", deepestK(), misses.isEmpty() ? "none"
                    : String.join("; ", misses)));
        } else if (!q.hits().isEmpty()) {
            out.append(fmt("  Best false-neighbour similarity: %.4f\n", q.hits().get(0).similarity()));
        }
        for (Hit h : q.hits().stream().limit(deepestK()).toList()) {
            out.append(fmt("  #%-2d %-12s sim=%.4f%s %s\n", h.rank(), h.experienceId(), h.similarity(),
                    h.tie() ? "=" : " ", h.neighbour()));
        }
    }

    private static String printable(String text) {
        return text.replace("\t", "\\t");
    }

    private static String metric(double value) {
        return Double.isNaN(value) ? "n/a" : fmt("%.2f", value);
    }

    private static <E extends Enum<E>> String counts(Map<E, Integer> counts) {
        if (counts.isEmpty()) {
            return "-";
        }
        return counts.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "));
    }

    /** Elapsed wall-clock timing. Nondeterministic by nature and never part of {@link #render()}. */
    public String renderTiming() {
        StringBuilder out = new StringBuilder("== Elapsed timing (nondeterministic; excluded from every check) ==\n");
        elapsedNanos.forEach((phase, nanos) -> out.append(String.format(Locale.ROOT, "  %-24s %.1f ms\n", phase,
                nanos / 1_000_000.0)));
        return out.toString();
    }
}
