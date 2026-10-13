package com.monada.evaluation.execution;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic report over a set of executions. Participants and pairs are listed in {@link ExecutionRef}
 * order, so the input order never changes the report. Pareto analysis is made per group of mutually
 * comparable executions; executions with different starting conditions are never put on one front.
 */
public record ComparisonReport(String interpretation, ComparisonPolicy policy, List<Participant> participants,
                               List<PairwiseComparison> pairs, List<ParetoGroup> groups) {

    /** Pareto diagnostics of one group of mutually comparable executions. */
    public record ParetoGroup(Comparability.Key key, List<ExecutionRef> members, List<ExecutionRef> front,
                              List<Dominated> dominated, List<Unassessed> unassessed, List<Excluded> excluded) {
        public ParetoGroup {
            members = List.copyOf(members);
            front = List.copyOf(front);
            dominated = List.copyOf(dominated);
            unassessed = List.copyOf(unassessed);
            excluded = List.copyOf(excluded);
        }
    }

    /** Dominated on every declared objective (within tolerance) by each of {@code dominatedBy}. */
    public record Dominated(ExecutionRef execution, List<ExecutionRef> dominatedBy) {
        public Dominated {
            dominatedBy = List.copyOf(dominatedBy);
        }
    }

    /** Eligible, but front membership cannot be established: incomplete or incommensurable measurements. */
    public record Unassessed(ExecutionRef execution, List<ObjectiveResult.Reason> reasons) {
        public Unassessed {
            reasons = List.copyOf(reasons);
        }
    }

    /** Not eligible: kept out of the front whatever its cost. */
    public record Excluded(ExecutionRef execution, List<Eligibility.Exclusion> reasons) {
        public Excluded {
            reasons = List.copyOf(reasons);
        }
    }

    public ComparisonReport {
        Objects.requireNonNull(interpretation, "interpretation");
        Objects.requireNonNull(policy, "policy");
        participants = List.copyOf(participants);
        pairs = List.copyOf(pairs);
        groups = List.copyOf(groups);
    }
}
