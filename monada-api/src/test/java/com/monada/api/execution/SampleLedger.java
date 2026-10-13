package com.monada.api.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.BillingMode;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Records fixed, deterministic executions into one scope. Every event gets the next second after
 * 2026-01-01T00:00:00Z as occurredAt and recordedAt, so recordedAt cutoffs are predictable.
 */
final class SampleLedger {
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final RouteDescriptor CHEAP = new RouteDescriptor("worker-a", Optional.of("prov"),
            Optional.of("small"), Optional.of("low"), BillingMode.API_METERED);
    static final RouteDescriptor STRONG = new RouteDescriptor("worker-b", Optional.of("prov"),
            Optional.of("large"), Optional.of("high"), BillingMode.SUBSCRIPTION);

    final ExecutionMemory memory;
    final ScopeId scope;
    private final String prefix;
    private int tick;
    private int counter;

    SampleLedger(ExecutionMemory memory, ScopeId scope, String prefix) {
        this.memory = memory;
        this.scope = scope;
        this.prefix = prefix;
    }

    Instant now() {
        return T0.plusSeconds(tick);
    }

    private Instant advance() {
        return T0.plusSeconds(++tick);
    }

    private EventId id(String kind) {
        return EventId.of(prefix + "-" + kind + "-" + (++counter));
    }

    long record(ExecutionEvent event) {
        RecordResult r = memory.record(event);
        assertEquals(RecordResult.Status.APPENDED, r.status(), String.valueOf(r.reason()));
        return r.sequence();
    }

    /** One execution of task {@code task}; policy "gates" v1 with {@code mandatory}. */
    final class Exec {
        final TaskId task;
        final ExecutionId id;

        Exec(String task, String execution, Set<QualityDimension> mandatory, String policyVersion) {
            this.task = TaskId.of(task);
            this.id = ExecutionId.of(execution);
            record(ExecutionEvent.executionStarted(id("start"), scope, this.task, id,
                    new SourceProvenance("rev-1", "ctx-1", "constraints-1"),
                    new EvaluationPolicy("gates", policyVersion, mandatory), "Fix " + task, advance()));
        }

        Exec attempt(AttemptId attempt, int ordinal, Optional<AttemptId> previous) {
            Instant t = advance();
            record(new AttemptStarted(EventEnvelope.original(id("att"), scope, task, id, attempt, t, t), ordinal,
                    previous, ordinal == 1 ? AttemptReason.INITIAL : AttemptReason.REPAIR));
            return this;
        }

        EventId stage(AttemptId attempt, RouteDescriptor route, UsageCounter... usage) {
            Instant t = advance();
            EventId id = id("stage");
            record(StageRecorded.measured(EventEnvelope.original(id, scope, task, this.id, attempt, t, t), "implement",
                    route, t, t, List.of(usage), List.of(ArtifactRef.of("log", "file:///" + id.value()))));
            return id;
        }

        EventId noBillable(AttemptId attempt) {
            Instant t = advance();
            EventId id = id("stage");
            record(StageRecorded.noBillableUsage(EventEnvelope.original(id, scope, task, this.id, attempt, t, t),
                    "lint", RouteDescriptor.workerOnly("local"), t, t, "deterministic local check", List.of()));
            return id;
        }

        EventId evidence(AttemptId attempt, QualityDimension d, ObservationState state, String policyVersion) {
            Instant t = advance();
            EventId id = id("ev");
            record(new EvidenceRecorded(EventEnvelope.original(id, scope, task, this.id, attempt, t, t),
                    List.of(observation(d, state, policyVersion)), List.of()));
            return id;
        }

        EventId finish(AttemptId attempt, AttemptResult result) {
            Instant t = advance();
            EventId id = id("fin");
            record(new AttemptFinished(EventEnvelope.original(id, scope, task, this.id, attempt, t, t), result,
                    Optional.of("solution " + result), Optional.of("lesson " + result)));
            return id;
        }

        long outcome(Outcome outcome) {
            return record(ExecutionEvent.outcomeRecorded(id("out"), scope, task, id, outcome, advance()));
        }

        /** Correction (revision {@code revision}) of {@code target}, replacing an evidence event. */
        EventId correctEvidence(EventId target, int revision, AttemptId attempt, QualityDimension d,
                                ObservationState state, String policyVersion) {
            Instant t = advance();
            EventId id = id("corr");
            EventEnvelope outer = new EventEnvelope(id, scope, task, this.id, Optional.of(attempt), revision,
                    Optional.of(target), t, t);
            record(new Correction(outer, new EvidenceRecorded(EventEnvelope.original(id, scope, task, this.id,
                    attempt, t, t), List.of(observation(d, state, policyVersion)), List.of()), "re-run"));
            return id;
        }
    }

    Exec exec(String task, String execution, Set<QualityDimension> mandatory) {
        return new Exec(task, execution, mandatory, "1");
    }

    static QualityObservation observation(QualityDimension d, ObservationState state, String policyVersion) {
        return new QualityObservation(d, state, OptionalDouble.empty(), Optional.empty(), "junit", "5", "gates",
                policyVersion, ObservationSource.TOOL, List.of(ArtifactRef.of("report", "r-" + d)),
                state == ObservationState.NOT_APPLICABLE ? Optional.of("no tests apply") : Optional.empty());
    }

    static UsageCounter in(long v) {
        return UsageCounter.reported(UsageKind.INPUT_TOKENS, v, "provider");
    }

    static UsageCounter out(long v) {
        return UsageCounter.estimated(UsageKind.OUTPUT_TOKENS, v, "tokenizer");
    }

    static final AttemptId A1 = AttemptId.of("a1");
    static final AttemptId A2 = AttemptId.of("a2");
    static final Set<QualityDimension> TESTS = Set.of(QualityDimension.TESTS);

    /**
     * Seven executions covering every label:
     * validated after a failed first attempt, rejected, pending, accepted with an UNKNOWN gate, imported claim,
     * a second validated one and an execution whose first attempt is still running.
     */
    void standardRun() {
        Exec ok = exec("t-ok", "x-ok", TESTS).attempt(A1, 1, Optional.empty());
        ok.stage(A1, CHEAP, in(1000), out(200));
        ok.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL, "1");
        ok.finish(A1, AttemptResult.FAILED);
        ok.attempt(A2, 2, Optional.of(A1));
        ok.stage(A2, STRONG, in(5000), UsageCounter.unknown(UsageKind.OUTPUT_TOKENS, "provider"));
        ok.evidence(A2, QualityDimension.TESTS, ObservationState.PASS, "1");
        ok.finish(A2, AttemptResult.COMPLETED);
        ok.outcome(Outcome.accepted(com.monada.core.execution.OutcomeOrigin.VALIDATED, A2));

        Exec rejected = exec("t-rej", "x-rej", TESTS).attempt(A1, 1, Optional.empty());
        rejected.stage(rejected.id.equals(ExecutionId.of("x-rej")) ? A1 : A1, CHEAP, in(10));
        rejected.finish(A1, AttemptResult.COMPLETED);
        rejected.outcome(Outcome.rejected(com.monada.core.execution.OutcomeOrigin.VALIDATED));

        exec("t-pending", "x-pending", TESTS).attempt(A1, 1, Optional.empty());

        Exec unknown = exec("t-unk", "x-unk", TESTS).attempt(A1, 1, Optional.empty());
        unknown.noBillable(A1);
        unknown.evidence(A1, QualityDimension.TESTS, ObservationState.UNKNOWN, "1");
        unknown.finish(A1, AttemptResult.COMPLETED);
        unknown.outcome(Outcome.accepted(com.monada.core.execution.OutcomeOrigin.VALIDATED, A1));

        Exec claim = exec("t-claim", "x-claim", TESTS).attempt(A1, 1, Optional.empty());
        claim.evidence(A1, QualityDimension.TESTS, ObservationState.PASS, "1");
        claim.finish(A1, AttemptResult.COMPLETED);
        claim.outcome(Outcome.accepted(com.monada.core.execution.OutcomeOrigin.IMPORTED_CLAIM, A1));

        Exec second = exec("t-ok", "x-ok2", TESTS).attempt(A1, 1, Optional.empty());
        second.stage(A1, CHEAP, in(300), out(50));
        second.evidence(A1, QualityDimension.TESTS, ObservationState.PASS, "1");
        second.finish(A1, AttemptResult.COMPLETED);
        second.outcome(Outcome.accepted(com.monada.core.execution.OutcomeOrigin.VALIDATED, A1));

        Exec running = exec("t-run", "x-run", TESTS).attempt(A1, 1, Optional.empty());
        running.stage(A1, CHEAP, in(5));
    }
}
