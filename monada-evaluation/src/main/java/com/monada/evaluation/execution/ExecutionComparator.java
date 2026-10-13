package com.monada.evaluation.execution;

import com.monada.evaluation.execution.ComparisonPolicy.Objective;
import com.monada.evaluation.execution.ComparisonReport.Dominated;
import com.monada.evaluation.execution.ComparisonReport.Excluded;
import com.monada.evaluation.execution.ComparisonReport.ParetoGroup;
import com.monada.evaluation.execution.ComparisonReport.Unassessed;
import com.monada.evaluation.execution.Measurements.Reading;
import com.monada.evaluation.execution.ObjectiveResult.Code;
import com.monada.evaluation.execution.ObjectiveResult.Reason;
import com.monada.evaluation.execution.ObjectiveResult.Side;
import com.monada.evaluation.execution.ObjectiveResult.Verdict;
import com.monada.evaluation.execution.PairwiseComparison.Basis;
import com.monada.evaluation.execution.PairwiseComparison.Conclusion;
import com.monada.evaluation.execution.PairwiseComparison.Winner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Evidence-gated, evaluation-only comparison of recorded executions (contract section 20). Pure: no clock,
 * file, network, provider or judge, and the result does not depend on the order of the input. It selects no
 * model, routes nothing, feeds nothing back into retrieval and reads no execution conclusion as a quality score.
 *
 * <p>Results are observational evidence about the supplied executions only; they are not causal and not a
 * general claim about any model.
 */
public final class ExecutionComparator {

    public static final String INTERPRETATION = "Observational evidence about the supplied executions only; "
            + "no causal claim and no general model-superiority claim. Costs are hypothetical API-equivalent "
            + "amounts. A tradeoff has no automatic winner.";

    private ExecutionComparator() {
    }

    /** Compares two executions directly, {@code left} and {@code right} in the order given. */
    public static PairwiseComparison compare(ComparisonCandidate left, ComparisonCandidate right,
                                             ComparisonPolicy policy, List<PairwiseJudgment> judgments) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(judgments, "judgments");
        if (left.ref().equals(right.ref())) {
            throw new IllegalArgumentException("cannot compare an execution with itself: " + left.ref());
        }
        requireKnown(judgments, Set.of(left.ref(), right.ref()));
        return pair(evaluate(left, policy), evaluate(right, policy), policy, judgments);
    }

    public static PairwiseComparison compare(ComparisonCandidate left, ComparisonCandidate right,
                                             ComparisonPolicy policy) {
        return compare(left, right, policy, List.of());
    }

    /** Compares every pair and builds the Pareto diagnostics of each group of mutually comparable executions. */
    public static ComparisonReport report(List<ComparisonCandidate> candidates, ComparisonPolicy policy,
                                          List<PairwiseJudgment> judgments) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(judgments, "judgments");
        List<Evaluated> all = candidates.stream().map(c -> evaluate(c, policy))
                .sorted(Comparator.comparing(e -> e.participant().ref())).toList();
        Set<ExecutionRef> known = new HashSet<>();
        for (Evaluated e : all) {
            if (!known.add(e.participant().ref())) {
                throw new IllegalArgumentException("duplicate execution: " + e.participant().ref());
            }
        }
        requireKnown(judgments, known);

        List<PairwiseComparison> pairs = new ArrayList<>();
        Map<Key, PairwiseComparison> byPair = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                PairwiseComparison p = pair(all.get(i), all.get(j), policy, judgments);
                pairs.add(p);
                byPair.put(new Key(p.left().ref(), p.right().ref()), p);
            }
        }

        Map<Comparability.Key, List<Evaluated>> grouped = new TreeMap<>();
        for (Evaluated e : all) {
            grouped.computeIfAbsent(e.participant().key(), k -> new ArrayList<>()).add(e);
        }
        List<ParetoGroup> groups = grouped.entrySet().stream()
                .map(g -> pareto(g.getKey(), g.getValue(), policy, byPair)).toList();
        return new ComparisonReport(INTERPRETATION, policy, all.stream().map(Evaluated::participant).toList(), pairs,
                groups);
    }

    public static ComparisonReport report(List<ComparisonCandidate> candidates, ComparisonPolicy policy) {
        return report(candidates, policy, List.of());
    }

    // --- evaluation of one execution ------------------------------------------------------------------

    private record Evaluated(Participant participant, List<Reading> readings) {
    }

    private record Key(ExecutionRef left, ExecutionRef right) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int c = left.compareTo(other.left);
            return c != 0 ? c : right.compareTo(other.right);
        }
    }

    private static Evaluated evaluate(ComparisonCandidate c, ComparisonPolicy policy) {
        ExecutionSummary s = c.summary();
        Participant participant = new Participant(c.ref(), Comparability.Key.of(c), s.acceptance(),
                Eligibility.assess(s, policy.mandatoryGates()), Participant.ResourceCoverage.of(s), s.gates());
        List<Reading> readings = policy.objectives().stream().map(o -> Measurements.read(s, o.metric())).toList();
        return new Evaluated(participant, readings);
    }

    private static void requireKnown(List<PairwiseJudgment> judgments, Set<ExecutionRef> known) {
        for (PairwiseJudgment j : judgments) {
            if (!known.contains(j.first()) || !known.contains(j.second())) {
                throw new IllegalArgumentException("judgment names an execution that is not compared: "
                        + j.first() + " vs " + j.second());
            }
        }
    }

    // --- pairwise ---------------------------------------------------------------------------------------

    private static PairwiseComparison pair(Evaluated l, Evaluated r, ComparisonPolicy policy,
                                           List<PairwiseJudgment> judgments) {
        Participant left = l.participant();
        Participant right = r.participant();
        Comparability comparability = Comparability.of(left.key(), right.key());
        List<PairwiseJudgment> mine = judgments.stream().filter(j -> j.judges(left.ref(), right.ref()))
                .sorted().toList();
        if (!comparability.isComparable()) {
            return new PairwiseComparison(left, right, comparability, Conclusion.NOT_COMPARABLE, Optional.empty(),
                    List.of(), mine, INTERPRETATION);
        }
        List<ObjectiveResult> objectives = new ArrayList<>();
        for (int i = 0; i < policy.objectives().size(); i++) {
            objectives.add(ObjectiveResult.of(policy.objectives().get(i), l.readings().get(i), r.readings().get(i)));
        }
        Conclusion conclusion;
        Optional<Winner> winner = Optional.empty();
        if (!left.eligible() && !right.eligible()) {
            conclusion = Conclusion.NO_ELIGIBLE_EXECUTION;
        } else if (left.eligible() != right.eligible()) {
            boolean leftOnly = left.eligible();
            conclusion = leftOnly ? Conclusion.ONLY_LEFT_ELIGIBLE : Conclusion.ONLY_RIGHT_ELIGIBLE;
            winner = Optional.of(new Winner((leftOnly ? left : right).ref(), Basis.ONLY_ELIGIBLE));
        } else {
            conclusion = dominance(objectives);
            if (conclusion == Conclusion.LEFT_DOMINATES) {
                winner = Optional.of(new Winner(left.ref(), Basis.DOMINANCE));
            } else if (conclusion == Conclusion.RIGHT_DOMINATES) {
                winner = Optional.of(new Winner(right.ref(), Basis.DOMINANCE));
            }
        }
        return new PairwiseComparison(left, right, comparability, conclusion, winner, objectives, mine, INTERPRETATION);
    }

    /** A tradeoff stands even if another objective is inconclusive; dominance needs every objective conclusive. */
    private static Conclusion dominance(List<ObjectiveResult> objectives) {
        if (objectives.isEmpty()) {
            return Conclusion.NO_OBJECTIVES;
        }
        boolean left = objectives.stream().anyMatch(o -> o.verdict() == Verdict.LEFT_BETTER);
        boolean right = objectives.stream().anyMatch(o -> o.verdict() == Verdict.RIGHT_BETTER);
        boolean inconclusive = objectives.stream().anyMatch(o -> o.verdict() == Verdict.INCONCLUSIVE);
        if (left && right) {
            return Conclusion.TRADEOFF;
        }
        if (inconclusive) {
            return Conclusion.INCONCLUSIVE;
        }
        return left ? Conclusion.LEFT_DOMINATES : right ? Conclusion.RIGHT_DOMINATES : Conclusion.EQUIVALENT;
    }

    // --- Pareto -----------------------------------------------------------------------------------------

    private static ParetoGroup pareto(Comparability.Key key, List<Evaluated> members, ComparisonPolicy policy,
                                      Map<Key, PairwiseComparison> byPair) {
        List<ExecutionRef> refs = members.stream().map(e -> e.participant().ref()).toList();
        List<Excluded> excluded = new ArrayList<>();
        List<Unassessed> unassessed = new ArrayList<>();
        List<Evaluated> assessable = new ArrayList<>();
        for (Evaluated e : members) {
            Participant p = e.participant();
            if (!p.eligible()) {
                excluded.add(new Excluded(p.ref(), p.eligibility().exclusions()));
                continue;
            }
            List<Reason> own = ownGaps(e, policy);
            if (own.isEmpty()) {
                assessable.add(e);
            } else {
                unassessed.add(new Unassessed(p.ref(), own));
            }
        }

        List<ExecutionRef> front = new ArrayList<>();
        List<Dominated> dominated = new ArrayList<>();
        for (Evaluated e : assessable) {
            ExecutionRef ref = e.participant().ref();
            List<ExecutionRef> dominators = new ArrayList<>();
            List<Reason> undecided = new ArrayList<>();
            for (Evaluated other : assessable) {
                if (other == e) {
                    continue;
                }
                PairwiseComparison p = lookup(byPair, ref, other.participant().ref());
                if (p.dominates(other.participant().ref())) {
                    dominators.add(other.participant().ref());
                } else {
                    // Also when the pair is already a tradeoff: front members must be conclusive on every objective.
                    p.objectives().stream().filter(o -> o.verdict() == Verdict.INCONCLUSIVE)
                            .forEach(o -> o.reasons().forEach(undecided::add));
                }
            }
            if (!dominators.isEmpty()) {
                dominated.add(new Dominated(ref, dominators, undecided));
            } else if (!undecided.isEmpty()) {
                unassessed.add(new Unassessed(ref, undecided));
            } else {
                front.add(ref);
            }
        }
        unassessed.sort(Comparator.comparing(Unassessed::execution));
        return new ParetoGroup(key, refs, front, dominated, unassessed, excluded);
    }

    private static List<Reason> ownGaps(Evaluated e, ComparisonPolicy policy) {
        List<Reason> reasons = new ArrayList<>();
        if (policy.objectives().isEmpty()) {
            reasons.add(new Reason(Side.BOTH, Code.NO_OBJECTIVES_DECLARED, "the policy declares no objective"));
        }
        for (int i = 0; i < policy.objectives().size(); i++) {
            Objective o = policy.objectives().get(i);
            for (Measurements.Gap gap : e.readings().get(i).gaps()) {
                reasons.add(new Reason(Side.BOTH, gap.code(), o.id() + ": " + gap.detail()));
            }
        }
        return reasons;
    }

    private static PairwiseComparison lookup(Map<Key, PairwiseComparison> byPair, ExecutionRef a, ExecutionRef b) {
        return byPair.get(a.compareTo(b) < 0 ? new Key(a, b) : new Key(b, a));
    }
}
