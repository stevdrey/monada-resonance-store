package com.monada.evaluation.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.QualityDimension;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A preference between two executions supplied by an evaluator (a human, a tool or a model judge). It is
 * reported next to the measured comparison with its judge, version, source, rubric and evidence, and it never
 * changes gate eligibility, objective results or the outcome: a judgment is not a correctness gate. Store
 * invents no judgments and calls no judge.
 *
 * @param preferred the preferred execution; empty when the judge found no difference
 */
public record PairwiseJudgment(ExecutionRef first, ExecutionRef second, QualityDimension dimension,
                               Optional<ExecutionRef> preferred, String judgeId, String judgeVersion,
                               ObservationSource source, String rubric, List<ArtifactRef> evidence)
        implements Comparable<PairwiseJudgment> {

    private static final Comparator<PairwiseJudgment> ORDER = Comparator
            .comparing(PairwiseJudgment::first)
            .thenComparing(PairwiseJudgment::second)
            .thenComparing(PairwiseJudgment::dimension)
            .thenComparing(PairwiseJudgment::judgeId)
            .thenComparing(PairwiseJudgment::judgeVersion)
            .thenComparing(PairwiseJudgment::source)
            .thenComparing(PairwiseJudgment::rubric)
            .thenComparing(PairwiseJudgment::preferred, Comparator.comparing(
                    (Optional<ExecutionRef> o) -> o.isPresent()).thenComparing(o -> o.orElse(null),
                    Comparator.nullsFirst(Comparator.<ExecutionRef>naturalOrder())))
            .thenComparing(PairwiseJudgment::evidence, PairwiseJudgment::compareEvidence);

    private static final Comparator<ArtifactRef> EVIDENCE_ORDER = Comparator.comparing(ArtifactRef::kind)
            .thenComparing(ArtifactRef::reference).thenComparing(a -> a.digest().orElse(""));

    private static int compareEvidence(List<ArtifactRef> a, List<ArtifactRef> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = EVIDENCE_ORDER.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    public PairwiseJudgment {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(preferred, "preferred");
        Objects.requireNonNull(source, "source");
        evidence = List.copyOf(evidence);
        if (first.equals(second)) {
            throw new IllegalArgumentException("a judgment compares two different executions");
        }
        if (preferred.isPresent() && !preferred.get().equals(first) && !preferred.get().equals(second)) {
            throw new IllegalArgumentException("the preferred execution must be one of the judged pair");
        }
        requireText(judgeId, "judgeId");
        requireText(judgeVersion, "judgeVersion");
        requireText(rubric, "rubric");
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("a judgment requires at least one evidence reference");
        }
    }

    private static void requireText(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    boolean judges(ExecutionRef a, ExecutionRef b) {
        return (first.equals(a) && second.equals(b)) || (first.equals(b) && second.equals(a));
    }

    @Override
    public int compareTo(PairwiseJudgment other) {
        return ORDER.compare(this, other);
    }
}
