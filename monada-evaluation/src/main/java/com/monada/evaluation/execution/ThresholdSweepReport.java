package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeEvaluationReport.fmt;
import static com.monada.evaluation.execution.RecallStressReport.counts;
import static com.monada.evaluation.execution.RecallStressReport.metric;

import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressReport.Hit;
import com.monada.evaluation.execution.RecallStressReport.Neighbour;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The result of the host-owned relevance threshold sweep (issue #123): per threshold and per group, precision among
 * returned hits, recall among judged positives, top-K coverage and abstention of queries with relevant experiences;
 * separately, false returns and abstention of content-bearing queries judged to have no relevant experience; the relevant
 * hits each threshold loses and the wrong matches it keeps; a few hard checks and, separately, elapsed timing.
 *
 * <p>Every metric is EXPLORATORY and host-owned: no threshold is recommended, none becomes a default and none is a
 * protected baseline. Only the hard checks decide the exit code. {@link #render()} is deterministic for fixed input;
 * {@link #renderTiming()} is not.
 */
public record ThresholdSweepReport(RecallStressReport stress, List<Double> grid, List<QuerySweep> rows,
                                   List<ForgeGate> gates, Map<String, Long> elapsedNanos)
        implements RecallStressEvaluationMain.Report {

    public static final String STRESS_CHECKS = "TS1-STRESS-CHECKS";
    public static final String FILTER_SEMANTICS = "TS2-FILTER-SEMANTICS";
    public static final String MONOTONIC = "TS3-MONOTONIC-PREFIX";

    public enum Decision { SWEEP_CHARACTERIZED, CHECKS_FAILED }

    /** The threshold conclusion of this sweep; it is never a recommendation. */
    public static final String THRESHOLD_CONCLUSION = "UNDECIDED (host-owned; no production threshold without "
            + "cross-dataset evidence)";

    /**
     * How a query enters the metrics: {@code SCORED} (has relevant experiences), {@code NO_RELEVANT} (a content query
     * judged to have no answer, whose returns are false matches) or {@code EMPTY} (a blank query or an
     * {@link Category#EMPTY_QUERY} probe without content, such as punctuation or stopwords only). EMPTY queries enter
     * no denominator: they return nothing at every threshold, so counting them would inflate no-relevant abstention.
     */
    public enum Kind {
        SCORED, NO_RELEVANT, EMPTY;

        static Kind of(QueryOutcome q) {
            if (q.scored()) {
                return SCORED;
            }
            boolean empty = q.query().text().isBlank() || q.query().category() == Category.EMPTY_QUERY;
            return empty ? EMPTY : NO_RELEVANT;
        }
    }

    /** One query with the hits a host keeps at each threshold of the grid, in grid order. */
    public record QuerySweep(QueryOutcome outcome, List<List<Hit>> kept) {
        public QuerySweep {
            kept = kept.stream().map(List::copyOf).toList();
        }

        public String id() {
            return outcome.query().id();
        }

        public Kind kind() {
            return Kind.of(outcome);
        }

        public int relevantKept(int point) {
            return (int) kept.get(point).stream().filter(h -> h.neighbour() == Neighbour.RELEVANT).count();
        }

        public boolean abstains(int point) {
            return kept.get(point).isEmpty();
        }

        /** Relevant hits kept at the 0.0 baseline but dropped at {@code point}. */
        public List<Hit> lostRelevant(int point) {
            return kept.get(0).stream().filter(h -> h.neighbour() == Neighbour.RELEVANT)
                    .filter(h -> !kept.get(point).contains(h)).toList();
        }
    }

    /**
     * Metrics of one group at one threshold. Scored side: {@code kept} hits, {@code relevantKept} of them relevant,
     * {@code judgedPositives} relevant experiences in total; precision = relevantKept / kept (NaN when nothing is
     * kept), recall = relevantKept / judgedPositives, coverage = share of scored queries with a relevant hit kept,
     * hitAt1 = share whose first kept hit is relevant, abstention = share that keep nothing. No-relevant side, never
     * mixed into the scored denominators: queries returning at least one hit, false hits kept, abstention.
     * {@code wrongKept} counts kept non-relevant hits of scored queries by neighbour category.
     */
    public record PointSummary(String group, double threshold, int scored, int kept, int relevantKept,
                               int judgedPositives, double precision, double recall, double coverage, double hitAt1,
                               double abstention, int noRelevant, int falseReturnQueries, int falseHits,
                               double noRelevantAbstention, Map<Neighbour, Integer> wrongKept) {
        public PointSummary {
            wrongKept = Collections.unmodifiableMap(wrongKept.isEmpty() ? new EnumMap<>(Neighbour.class)
                    : new EnumMap<>(wrongKept));
        }
    }

    public ThresholdSweepReport {
        Objects.requireNonNull(stress, "stress");
        grid = List.copyOf(grid);
        rows = List.copyOf(rows);
        for (QuerySweep row : rows) {
            if (row.kept().size() != grid.size()) {
                throw new IllegalArgumentException(row.id() + ": " + row.kept().size() + " kept lists for a grid of "
                        + grid.size() + " thresholds");
            }
        }
        gates = List.copyOf(gates);
        elapsedNanos = Collections.unmodifiableMap(new LinkedHashMap<>(elapsedNanos));
    }

    ThresholdSweepReport withElapsed(Map<String, Long> elapsed) {
        return new ThresholdSweepReport(stress, grid, rows, gates, elapsed);
    }

    public Decision decision() {
        return gates.stream().allMatch(ForgeGate::passed) ? Decision.SWEEP_CHARACTERIZED : Decision.CHECKS_FAILED;
    }

    @Override
    public boolean allGatesPassed() {
        return decision() == Decision.SWEEP_CHARACTERIZED;
    }

    public List<String> failedGates() {
        return gates.stream().filter(g -> !g.passed()).map(ForgeGate::id).toList();
    }

    public static Set<String> expectedGateIds() {
        return Set.of(STRESS_CHECKS, FILTER_SEMANTICS, MONOTONIC);
    }

    public QuerySweep row(String queryId) {
        return rows.stream().filter(r -> r.id().equals(queryId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown query " + queryId));
    }

    public int point(double threshold) {
        int i = grid.indexOf(threshold);
        if (i < 0) {
            throw new IllegalArgumentException("threshold " + threshold + " is not in the declared grid");
        }
        return i;
    }

    /** Group names in report order: BASELINE, every stress category, ALL_STRESS, ALL. */
    public List<String> groupNames() {
        List<String> names = new ArrayList<>();
        for (Category c : Category.values()) {
            names.add(c.name());
        }
        names.add("ALL_STRESS");
        names.add("ALL");
        return names;
    }

    private static Predicate<QuerySweep> member(String group) {
        return switch (group) {
            case "ALL" -> r -> true;
            case "ALL_STRESS" -> r -> r.outcome().query().category() != Category.BASELINE;
            default -> r -> r.outcome().query().category() == Category.valueOf(group);
        };
    }

    public PointSummary summary(String group, int point) {
        List<QuerySweep> members = rows.stream().filter(member(group)).toList();
        List<QuerySweep> scored = members.stream().filter(r -> r.kind() == Kind.SCORED).toList();
        List<QuerySweep> none = members.stream().filter(r -> r.kind() == Kind.NO_RELEVANT).toList();
        int kept = scored.stream().mapToInt(r -> r.kept().get(point).size()).sum();
        int relevantKept = scored.stream().mapToInt(r -> r.relevantKept(point)).sum();
        int positives = scored.stream().mapToInt(r -> r.outcome().query().relevant().size()).sum();
        Map<Neighbour, Integer> wrong = new EnumMap<>(Neighbour.class);
        scored.forEach(r -> r.kept().get(point).stream().filter(h -> h.neighbour().falseNeighbour())
                .forEach(h -> wrong.merge(h.neighbour(), 1, Integer::sum)));
        int falseReturns = (int) none.stream().filter(r -> !r.abstains(point)).count();
        return new PointSummary(group, grid.get(point), scored.size(), kept, relevantKept, positives,
                ratio(relevantKept, kept), ratio(relevantKept, positives),
                share(scored, r -> r.relevantKept(point) > 0),
                share(scored, r -> !r.abstains(point) && r.kept().get(point).get(0).neighbour() == Neighbour.RELEVANT),
                share(scored, r -> r.abstains(point)), none.size(), falseReturns,
                none.stream().mapToInt(r -> r.kept().get(point).size()).sum(),
                share(none, r -> r.abstains(point)), wrong);
    }

    /** A ratio, or NaN (printed {@code n/a}) for an empty denominator; never a silent zero. */
    static double ratio(int numerator, int denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / denominator;
    }

    private static double share(List<QuerySweep> rows, Predicate<QuerySweep> p) {
        return ratio((int) rows.stream().filter(p).count(), rows.size());
    }

    /** Lowest declared threshold at which every no-relevant content query abstains, if any. */
    public Optional<Integer> firstFullNoRelevantAbstention() {
        for (int i = 0; i < grid.size(); i++) {
            int point = i;
            if (rows.stream().filter(r -> r.kind() == Kind.NO_RELEVANT).allMatch(r -> r.abstains(point))) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /**
     * Highest declared threshold that loses no relevant hit with a positive similarity. Zero-similarity relevant
     * hits are limit fill (a tie-order artefact, not a match), so losing them is reported but not counted here.
     */
    public int lastWithoutPositiveRelevantLoss() {
        int last = 0;
        for (int i = 1; i < grid.size(); i++) {
            int point = i;
            boolean lossless = rows.stream().flatMap(r -> r.lostRelevant(point).stream())
                    .allMatch(h -> h.similarity() == 0.0);
            if (!lossless) {
                break;
            }
            last = i;
        }
        return last;
    }

    /** Deterministic report text; contains no timing. */
    @Override
    public String render() {
        StringBuilder out = new StringBuilder();
        int k = stress.deepestK();
        out.append("Forge Recall Host-Owned Threshold Sweep (issue #123)\n");
        out.append(fmt("Input: the judged stress set forge-recall-stress v%s (issue #122) over forge-experience-v1: "
                + "%d experiences, %d projects, %d queries; recall limit %d; top K = %d\n", stress.fixtureVersion(),
                stress.experienceCount(), stress.projectCount(), rows.size(), stress.recallLimit(), k));
        out.append("Recall under test: ExecutionMemory.recall with production defaults (summary-text-v1, default "
                + "lexical encoder, exact query keys, threshold 0.0), unchanged. The threshold below is a host-side "
                + "post-filter applied by this evaluation only.\n");
        out.append(fmt("Declared grid: %s (0.00 = current behaviour, zero-similarity fill included)\n",
                grid.stream().map(ThresholdSweepReport::thr).collect(Collectors.joining(", "))));
        out.append(fmt("Filter: keep a hit when similarity >= threshold (inclusive, exact comparison), then read the "
                + "first %d kept hits in rank order; ties at the threshold are kept or dropped together.\n", k));
        out.append("Reading guide: every metric is EXPLORATORY and host-owned; similarity is not confidence or "
                + "validated quality. Scored columns use only queries with relevant experiences: prec = relevant "
                + "kept / kept, recall = relevant kept / judged positives, cover = queries with a relevant hit kept, "
                + "abst = queries keeping nothing. No-relevant content queries are reported separately (ret = "
                + "queries still returning a hit, false = hits kept) and never enter a scored denominator. Empty "
                + "queries (blank, or EMPTY_QUERY probes without content) enter no denominator and are listed only "
                + "in the per-query table. n/a = empty denominator.\n");

        out.append("\n== Sweep per group ==\n");
        for (String group : groupNames()) {
            appendGroup(out, group);
        }

        out.append("\n== Costs per candidate threshold (all queries, relative to 0.00) ==\n");
        for (int i = 1; i < grid.size(); i++) {
            appendCosts(out, i);
        }

        out.append("\n== Per-query kept hits (relevant/kept; '-' = abstains) ==\n");
        StringBuilder header = new StringBuilder(fmt("  %-4s %-18s %-7s", "id", "category", "kind"));
        grid.forEach(t -> header.append(fmt(" %5s", thr(t))));
        out.append(header).append('\n');
        for (QuerySweep r : rows) {
            out.append(fmt("  %-4s %-18s %-7s", r.id(), r.outcome().query().category(), kindLabel(r.kind())));
            for (int i = 0; i < grid.size(); i++) {
                out.append(fmt(" %5s", r.abstains(i) ? "-" : r.relevantKept(i) + "/" + r.kept().get(i).size()));
            }
            out.append('\n');
        }

        out.append("\n== Hard checks (any failure blocks the decision) ==\n");
        for (ForgeGate g : gates) {
            out.append(fmt("  [%s] %s — %s\n", g.passed() ? "PASS" : "FAIL", g.id(), g.name()));
            for (String d : g.details()) {
                out.append("        ").append(d).append('\n');
            }
        }

        appendOperatingPoints(out);

        out.append("\n== Decision ==\n");
        Decision d = decision();
        out.append("  ").append(d).append('\n');
        out.append("  Threshold: ").append(THRESHOLD_CONCLUSION).append('\n');
        out.append(d == Decision.SWEEP_CHARACTERIZED
                ? "  Every hard check passed: the sweep reads the deterministic, in-scope #122 rankings, 0.00 equals "
                + "the current recall and every candidate only removes hits. The tradeoffs above characterize one "
                + "small judged set (" + stress.experienceCount() + " experiences); they are exploratory evidence for "
                + "a host decision, not a quality claim, a default or a gate.\n"
                : "  At least one hard check failed: the sweep is not trustworthy until it is fixed.\n");
        return out.toString();
    }

    private void appendGroup(StringBuilder out, String group) {
        PointSummary base = summary(group, 0);
        out.append(fmt("\n  %s (scored %d, judged positives %d; no-relevant %d)\n", group, base.scored(),
                base.judgedPositives(), base.noRelevant()));
        out.append(fmt("    %5s %5s %8s %5s %8s %6s %5s %5s %5s | %6s %5s %5s\n", "thr", "kept", "rel/kept", "prec",
                "rel/pos", "recall", "cover", "Hit@1", "abst", "ret", "false", "abst"));
        for (int i = 0; i < grid.size(); i++) {
            PointSummary s = summary(group, i);
            out.append(fmt("    %5s %5d %8s %5s %8s %6s %5s %5s %5s | %6s %5s %5s\n", thr(s.threshold()), s.kept(),
                    s.relevantKept() + "/" + s.kept(), metric(s.precision()),
                    s.relevantKept() + "/" + s.judgedPositives(), metric(s.recall()), metric(s.coverage()),
                    metric(s.hitAt1()), metric(s.abstention()), s.falseReturnQueries() + "/" + s.noRelevant(),
                    s.falseHits(), metric(s.noRelevantAbstention())));
        }
    }

    private void appendCosts(StringBuilder out, int point) {
        out.append(fmt("\n  threshold %s\n", thr(grid.get(point))));
        List<String> lost = new ArrayList<>();
        for (QuerySweep r : rows) {
            for (Hit h : r.lostRelevant(point)) {
                lost.add(fmt("%s %s sim=%.4f%s", r.id(), h.experienceId(), h.similarity(),
                        h.similarity() == 0.0 ? " (fill)" : ""));
            }
        }
        out.append(fmt("    lost relevant (%d): %s\n", lost.size(), lost.isEmpty() ? "none" : String.join("; ", lost)));
        List<String> lostCoverage = rows.stream().filter(r -> r.kind() == Kind.SCORED)
                .filter(r -> r.relevantKept(0) > 0 && r.relevantKept(point) == 0).map(QuerySweep::id).toList();
        out.append(fmt("    scored queries losing every relevant hit: %s\n", list(lostCoverage)));
        List<String> abstaining = rows.stream().filter(r -> r.kind() == Kind.SCORED && r.abstains(point))
                .map(QuerySweep::id).toList();
        out.append(fmt("    scored queries abstaining: %s\n", list(abstaining)));
        out.append(fmt("    wrong matches kept by scored queries: %s\n", counts(summary("ALL", point).wrongKept())));
        List<String> falseReturns = rows.stream().filter(r -> r.kind() == Kind.NO_RELEVANT && !r.abstains(point))
                .map(r -> fmt("%s(%d, best %.4f)", r.id(), r.kept().get(point).size(),
                        r.kept().get(point).get(0).similarity()))
                .toList();
        out.append(fmt("    no-relevant queries still returning: %s\n", list(falseReturns)));
        List<String> abstainingNone = rows.stream().filter(r -> r.kind() == Kind.NO_RELEVANT && r.abstains(point))
                .map(QuerySweep::id).toList();
        out.append(fmt("    no-relevant queries abstaining: %s\n", list(abstainingNone)));
    }

    private void appendOperatingPoints(StringBuilder out) {
        out.append("\n== Operating points (EXPLORATORY, host-owned; illustrations, not recommendations) ==\n");
        int lossless = lastWithoutPositiveRelevantLoss();
        PointSummary keep = summary("ALL", lossless);
        out.append(fmt("  Highest threshold losing no positive-similarity relevant hit: %s — recall %s, prec %s, "
                + "no-relevant still returning %d/%d (%d false hits)\n", thr(grid.get(lossless)),
                metric(keep.recall()), metric(keep.precision()), keep.falseReturnQueries(), keep.noRelevant(),
                keep.falseHits()));
        Optional<Integer> silent = firstFullNoRelevantAbstention();
        if (silent.isEmpty()) {
            out.append("  No declared threshold makes every no-relevant content query abstain.\n");
        } else {
            int point = silent.get();
            PointSummary s = summary("ALL", point);
            long lost = rows.stream().mapToLong(r -> r.lostRelevant(point).size()).sum();
            long uncovered = rows.stream().filter(r -> r.kind() == Kind.SCORED)
                    .filter(r -> r.relevantKept(0) > 0 && r.relevantKept(point) == 0).count();
            out.append(fmt("  Lowest threshold at which every no-relevant content query abstains: %s — recall %s, "
                    + "prec %s, cover %s, %d relevant hits lost, %d scored queries lose every relevant hit, scored "
                    + "abstention %s\n", thr(grid.get(point)), metric(s.recall()), metric(s.precision()),
                    metric(s.coverage()), lost, uncovered, metric(s.abstention())));
        }
        boolean separable = silent.isPresent() && silent.get() <= lossless;
        out.append(separable
                ? "  Tradeoff: on this set one declared threshold silences every no-relevant query without losing a "
                + "positive-similarity relevant hit."
                : "  Tradeoff: on this set no declared threshold both silences every no-relevant query and keeps every "
                + "positive-similarity relevant hit; in between, each step trades wrong matches for lost relevant "
                + "ones.");
        out.append(" The operating point is the host's choice (cost of a wrong match vs a lost relevant match) and "
                + "stays undecided here: one small synthetic corpus, one encoder, no cross-dataset evidence.\n");
    }

    private static String kindLabel(Kind kind) {
        return switch (kind) {
            case SCORED -> "scored";
            case NO_RELEVANT -> "no-rel";
            case EMPTY -> "empty";
        };
    }

    static String thr(double threshold) {
        return fmt("%.2f", threshold);
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    /** Elapsed wall-clock timing. Nondeterministic by nature and never part of {@link #render()}. */
    @Override
    public String renderTiming() {
        return RecallStressReport.renderTiming(elapsedNanos);
    }
}
