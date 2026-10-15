package com.monada.evaluation.execution;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.api.execution.ExperienceHit;
import com.monada.api.execution.ExperienceSample;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ExperienceRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Joins one recalled hit to the exported sample of its execution and reports what the attempt did separately from
 * what its execution is labelled (issue #121). Recall addresses an exact {@code ATTEMPT_FINISHED} fact while a sample
 * is labelled per execution, so the join keeps four dimensions apart:
 *
 * <ul>
 *   <li><b>execution label</b> and <b>execution acceptance</b>: the raw values of the sample, copied unchanged;</li>
 *   <li><b>attempt result</b>: the result of the joined attempt in the sample ({@code UNFINISHED} without a finish);</li>
 *   <li><b>validated accepted attempt</b>: true only when the execution is {@code VALIDATED_ACCEPTED}, the sample marks
 *       this attempt as the accepted one and the attempt is {@code COMPLETED}; a failed or cancelled attempt of a
 *       {@code SUCCESS} execution is never reported as accepted;</li>
 *   <li><b>reference provenance</b>: the hit's event id, revision and ledger sequence.</li>
 * </ul>
 *
 * <p>Pure and side-effect free: it reads nothing but its arguments, so the same hit and samples always join the same.
 */
final class AttemptJoin {
    /** Shown in a dimension the join could not establish. */
    static final String NONE = "-";

    enum Status {
        /** Joined to the sample's attempt whose finish is exactly the hit's reference. */
        JOINED,
        /** The hit's event lies after the export cutoff: it is not joined to an earlier state of its execution. */
        BEYOND_CUTOFF,
        /** No exported sample of the hit's scope and execution. */
        NO_SAMPLE,
        /** The sample exists but holds no attempt with the hit's attempt id (or the hit has no attempt). */
        ATTEMPT_NOT_IN_SAMPLE,
        /**
         * The sample's attempt has no finish, or a finish other than the hit's exact reference; the attempt result
         * and accepted flags are then left unestablished, never taken from the other finish.
         */
        FINISH_MISMATCH
    }

    /**
     * One joined hit; dimensions the join could not establish are {@link #NONE}. {@code sampleExecution} is the
     * execution id of the exported sample the hit was matched to ({@link #NONE} without one), recorded apart from the
     * hit's own reference so that a join to another execution's sample stays detectable.
     */
    record Row(String experienceId, String eventId, int revision, long sequence, Status status,
               String sampleExecution, String executionLabel, String executionAcceptance, String attemptResult, boolean markedAccepted,
               boolean validatedAcceptedAttempt) {
        Row {
            Objects.requireNonNull(experienceId, "experienceId");
            Objects.requireNonNull(status, "status");
        }

        boolean joined() {
            return status == Status.JOINED;
        }
    }

    private AttemptJoin() {
    }

    /**
     * Joins {@code hit} to {@code samples} (one export of one scope) as of {@code cutoffSequence}, the ledger sequence
     * the export was cut at. The sample is found by scope and execution id only, never by text or similarity.
     */
    static Row join(ExperienceHit hit, List<ExperienceSample> samples, long cutoffSequence) {
        ExperienceRef ref = hit.ref();
        String id = ForgeExperienceEvaluator.experienceId(ref);
        long sequence = hit.event().sequence();
        if (sequence > cutoffSequence) {
            return unjoined(id, ref, sequence, Status.BEYOND_CUTOFF);
        }
        Optional<ExperienceSample> sample = samples.stream()
                .filter(s -> s.executionId().equals(ref.execution()) && s.execution().scope().equals(ref.scope()))
                .findFirst();
        if (sample.isEmpty()) {
            return unjoined(id, ref, sequence, Status.NO_SAMPLE);
        }
        ExperienceSample s = sample.get();
        String matched = s.executionId().value();
        String label = s.label().name();
        String acceptance = s.acceptance().name();
        Optional<ExperienceSample.SampleAttempt> attempt = ref.attempt().flatMap(a -> s.attempts().stream()
                .filter(sa -> sa.id().equals(a)).findFirst());
        if (attempt.isEmpty()) {
            return new Row(id, ref.eventId().value(), ref.revision(), sequence, Status.ATTEMPT_NOT_IN_SAMPLE, matched,
                    label, acceptance, NONE, false, false);
        }
        ExperienceSample.SampleAttempt a = attempt.get();
        if (a.finish().filter(ref::equals).isEmpty()) {
            // the hit's fact is not the finish the sample exports: no attempt-level verdict is established
            return new Row(id, ref.eventId().value(), ref.revision(), sequence, Status.FINISH_MISMATCH, matched, label,
                    acceptance, NONE, false, false);
        }
        String result = a.result().map(Enum::name).orElse(MultiAttemptFixture.UNFINISHED);
        boolean validated = s.acceptance() == DerivedAcceptance.Status.VALIDATED_ACCEPTED && a.accepted()
                && a.result().filter(r -> r == AttemptResult.COMPLETED).isPresent();
        return new Row(id, ref.eventId().value(), ref.revision(), sequence, Status.JOINED, matched, label, acceptance,
                result, a.accepted(), validated);
    }

    private static Row unjoined(String id, ExperienceRef ref, long sequence, Status status) {
        return new Row(id, ref.eventId().value(), ref.revision(), sequence, status, NONE, NONE, NONE, NONE, false,
                false);
    }
}
