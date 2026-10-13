package com.monada.evaluation.execution;

import com.monada.api.execution.ExperienceSample;
import java.util.List;
import java.util.Objects;

/**
 * An exported {@link ExperienceSample} with evaluation-side annotations (contract v1, section 12). The sample is
 * the unchanged export record; the annotations are separate fields so they can never alter its label, digest or
 * eligibility. {@code cost} is the hypothetical, API-equivalent cost to the accepted outcome as of the export
 * cutoff (section 8): it is {@code UNAVAILABLE}, never zero, for a sample without validated acceptance, and low
 * cost never makes a sample better. {@code comparison} is observational Pareto evidence for the exported corpus,
 * not a ranking of models.
 */
public record AnnotatedSample(ExperienceSample sample, CostToAcceptedOutcome cost, Placement comparison) {

    public enum PlacementStatus { NOT_REQUESTED, FRONT, DOMINATED, UNASSESSED, EXCLUDED }

    /** Where the execution sits in the Pareto diagnostics of its comparable group; reasons are verbatim. */
    public record Placement(PlacementStatus status, List<String> details) {
        public Placement {
            Objects.requireNonNull(status, "status");
            details = List.copyOf(details);
        }

        static Placement notRequested() {
            return new Placement(PlacementStatus.NOT_REQUESTED, List.of());
        }
    }

    public AnnotatedSample {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(comparison, "comparison");
    }
}
