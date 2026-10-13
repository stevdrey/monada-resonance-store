package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.ComparisonFixtures.*;
import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The comparison re-implements two things it cannot call (ledger ordering rules and acceptance derivation).
 * This test keeps the copies honest: whatever the real ledger accepts must be accepted by
 * {@code ComparisonCandidate.of} with an identical acceptance, and whatever the ledger rejects, appended to a
 * copy of the history, must be rejected by it.
 */
class ViewLedgerParityTest {
    @TempDir
    Path root;

    private record Ids(EventId stage, EventId evidence, EventId finish, EventId outcome) {
    }

    private record Case(String name, Function<Ids, List<ExecutionEvent>> events) {
    }

    private int counter;

    private EventId id() {
        return EventId.of("p-" + (++counter));
    }

    private EventEnvelope env(Optional<AttemptId> attempt) {
        return attempt.map(a -> EventEnvelope.original(id(), SCOPE, TASK, EXEC, a, T0, T0))
                .orElseGet(() -> EventEnvelope.original(id(), SCOPE, TASK, EXEC, T0, T0));
    }

    private EventEnvelope orig(AttemptId a, boolean stageTimes) {
        var t = stageTimes ? at(5) : T0;
        return EventEnvelope.original(id(), SCOPE, TASK, EXEC, a, t, t);
    }

    private StageRecorded stage(EventEnvelope e) {
        return StageRecorded.measured(e, "extra", ROUTE, at(0), at(5), List.of(in(1), out(1)), List.of());
    }

    private StageRecorded stage(AttemptId a) {
        return stage(orig(a, true));
    }

    private EvidenceRecorded evidence(EventEnvelope e, ObservationState state) {
        return new EvidenceRecorded(e,
                List.of(observation(QualityDimension.TESTS, state, OptionalDouble.empty(), Optional.empty())), List.of());
    }

    private EvidenceRecorded evidence(AttemptId a, ObservationState state) {
        return evidence(orig(a, false), state);
    }

    private AttemptStarted started(AttemptId a, int ordinal, Optional<AttemptId> previous) {
        return new AttemptStarted(env(Optional.of(a)), ordinal, previous,
                previous.isPresent() ? AttemptReason.RETRY : AttemptReason.INITIAL);
    }

    private AttemptFinished finished(EventEnvelope e, AttemptResult r) {
        return new AttemptFinished(e, r, Optional.empty(), Optional.empty());
    }

    private AttemptFinished finished(AttemptId a, AttemptResult r) {
        return finished(orig(a, false), r);
    }

    /** A correction: the replacement carries the same event id as the correcting envelope. */
    private Correction correct(EventId target, int revision, AttemptId a, boolean stageTimes,
                               Function<EventEnvelope, ExecutionEvent> replacement) {
        EventEnvelope same = orig(a, stageTimes);
        EventEnvelope outer = new EventEnvelope(same.eventId(), SCOPE, TASK, EXEC, Optional.of(a), revision,
                Optional.of(target), same.occurredAt(), same.recordedAt());
        return new Correction(outer, replacement.apply(same), "re-assessed");
    }

    private List<Case> cases() {
        AttemptId ghost = AttemptId.of("a9");
        return List.of(
                new Case("stage for an unstarted attempt", i -> List.of(stage(ghost))),
                new Case("evidence for an unstarted attempt", i -> List.of(evidence(ghost, ObservationState.PASS))),
                new Case("finish for an unstarted attempt", i -> List.of(finished(ghost, AttemptResult.COMPLETED))),
                new Case("stage after the attempt finished", i -> List.of(stage(A1))),
                new Case("evidence after the attempt finished", i -> List.of(evidence(A1, ObservationState.FAIL))),
                new Case("second finish", i -> List.of(finished(A1, AttemptResult.FAILED))),
                new Case("attempt started twice", i -> List.of(started(A1, 1, Optional.empty()))),
                new Case("duplicate ordinal", i -> List.of(started(A2, 1, Optional.empty()))),
                new Case("dangling previous attempt", i -> List.of(started(A2, 2, Optional.of(ghost)))),
                new Case("valid follow-up attempt", i -> List.of(started(A2, 2, Optional.of(A1)))),
                new Case("accepted attempt unknown", i -> List.of(ExecutionEvent.outcomeRecorded(id(), SCOPE, TASK, EXEC,
                        Outcome.accepted(OutcomeOrigin.VALIDATED, ghost), T0))),
                new Case("second EXECUTION_STARTED", i -> List.of(ExecutionEvent.executionStarted(id(), SCOPE, TASK, EXEC,
                        PROV, POLICY, "again", T0))),
                new Case("correction with a skipped revision", i -> List.of(correct(i.stage(), 3, A1, true, this::stage))),
                new Case("correction of a missing event", i -> List.of(correct(EventId.of("nope"), 2, A1, true, this::stage))),
                new Case("correction of a superseded revision", i -> List.of(correct(i.stage(), 2, A1, true, this::stage),
                        correct(i.stage(), 2, A1, true, this::stage))),
                new Case("correction changing the kind", i -> List.of(correct(i.stage(), 2, A1, true,
                        e -> evidence(e, ObservationState.PASS)))),
                new Case("correction for another attempt", i -> List.of(started(A2, 2, Optional.of(A1)),
                        correct(i.stage(), 2, A2, true, this::stage))),
                new Case("valid stage correction after finish", i -> List.of(correct(i.stage(), 2, A1, true, this::stage))),
                new Case("second revision of a correction", i -> {
                    Correction first = correct(i.stage(), 2, A1, true, this::stage);
                    return List.of(first, correct(first.eventId(), 3, A1, true, this::stage));
                }),
                // acceptance parity: every derived status must equal the facade's
                new Case("evidence corrected to FAIL", i -> List.of(correct(i.evidence(), 2, A1, false,
                        e -> evidence(e, ObservationState.FAIL)))),
                new Case("finish corrected to FAILED", i -> List.of(correct(i.finish(), 2, A1, false,
                        e -> finished(e, AttemptResult.FAILED)))),
                new Case("outcome rejected", i -> List.of(ExecutionEvent.outcomeRecorded(id(), SCOPE, TASK, EXEC,
                        Outcome.rejected(OutcomeOrigin.VALIDATED), T0))),
                new Case("outcome pending", i -> List.of(ExecutionEvent.outcomeRecorded(id(), SCOPE, TASK, EXEC,
                        Outcome.pending(OutcomeOrigin.VALIDATED), T0))),
                new Case("imported claim", i -> List.of(ExecutionEvent.outcomeRecorded(id(), SCOPE, TASK, EXEC,
                        Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM, A1), T0))),
                new Case("unfinished accepted attempt", i -> List.of(started(A2, 2, Optional.of(A1)),
                        ExecutionEvent.outcomeRecorded(id(), SCOPE, TASK, EXEC,
                                Outcome.accepted(OutcomeOrigin.VALIDATED, A2), T0))));
    }

    private Run base(String dir, Ids[] out) throws java.io.IOException {
        Run run = new Run(Files.createDirectories(root.resolve(dir)), java.util.Set.of(QualityDimension.TESTS));
        run.initial(A1);
        EventId stage = run.stage(A1, "work", 0, 10, usage(10_000, 1_000));
        EventId evidence = run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        EventId finish = run.finishEvent(A1, AttemptResult.COMPLETED);
        run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        out[0] = new Ids(stage, evidence, finish, null);
        return run;
    }

    @Test
    void theComparisonAcceptsExactlyWhatTheLedgerAcceptsWithTheSameAcceptance() throws Exception {
        int rejected = 0;
        int accepted = 0;
        for (Case c : cases()) {
            Ids[] ids = new Ids[1];
            try (Run run = base("case-" + c.name().hashCode(), ids)) {
                List<ExecutionEvent> events = c.events().apply(ids[0]);
                ExecutionEvent refused = null;
                for (ExecutionEvent event : events) {
                    if (run.tryRecord(event).isEmpty()) {
                        refused = event;
                        break;
                    }
                }
                ExecutionView view = run.view();
                if (refused == null) {
                    accepted++;
                    assertDoesNotThrow(() -> ComparisonCandidate.of(run.scope(), view, book(usd()), List.of()),
                            c.name() + ": the ledger accepted it, so must the comparison");
                } else {
                    rejected++;
                    List<HistoryEntry> forged = new ArrayList<>(view.history());
                    forged.add(new HistoryEntry(forged.get(forged.size() - 1).sequence() + 1, refused));
                    ExecutionView forgedView = new ExecutionView(view.id(), view.task(), view.taskSummary(),
                            view.provenance(), view.policy(), view.currentOutcome(), view.acceptance(),
                            view.attempts(), forged);
                    assertThrows(IllegalArgumentException.class,
                            () -> ComparisonCandidate.of(run.scope(), forgedView, book(usd()), List.of()),
                            c.name() + ": the ledger rejected it, so must the comparison");
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(rejected >= 10 && accepted >= 8,
                "the table must exercise both sides: rejected=" + rejected + " accepted=" + accepted);
    }
}
