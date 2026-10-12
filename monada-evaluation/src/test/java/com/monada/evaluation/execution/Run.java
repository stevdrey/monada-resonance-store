package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.RecordResult;
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
import com.monada.core.execution.PriceLine;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Records a real execution history in a temporary directory so the calculators run on genuine views. */
final class Run implements AutoCloseable {
    static final ScopeId SCOPE = ScopeId.of("scope-1");
    static final TaskId TASK = TaskId.of("task-1");
    static final ExecutionId EXEC = ExecutionId.of("exec-1");
    static final AttemptId A1 = AttemptId.of("a1");
    static final AttemptId A2 = AttemptId.of("a2");
    static final AttemptId A3 = AttemptId.of("a3");
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final RouteDescriptor ROUTE = new RouteDescriptor("worker", Optional.of("prov"), Optional.of("model-x"),
            Optional.empty(), BillingMode.API_METERED);

    private final ExecutionMemory memory;
    private int counter;

    Run(Path root, Set<QualityDimension> mandatory) {
        memory = ExecutionMemory.open(root, SCOPE);
        record(ExecutionEvent.executionStarted(id("start"), SCOPE, TASK, EXEC,
                new SourceProvenance("rev-1", "ctx-1", "constraints-1"),
                new EvaluationPolicy("policy", "1", mandatory), "Fix the bug", T0));
    }

    static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    static EventId id(String name) {
        return EventId.of(name);
    }

    EventId nextId(String prefix) {
        return id(prefix + "-" + (++counter));
    }

    long record(ExecutionEvent event) {
        RecordResult result = memory.record(event);
        assertEquals(RecordResult.Status.APPENDED, result.status(), String.valueOf(result.reason()));
        return result.sequence();
    }

    Run attempt(AttemptId attempt, int ordinal, AttemptReason reason, Optional<AttemptId> previous) {
        record(new AttemptStarted(EventEnvelope.original(nextId("att"), SCOPE, TASK, EXEC, attempt, T0, T0),
                ordinal, previous, reason));
        return this;
    }

    Run initial(AttemptId attempt) {
        return attempt(attempt, 1, AttemptReason.INITIAL, Optional.empty());
    }

    Run follow(AttemptId attempt, int ordinal, AttemptReason reason, AttemptId previous) {
        return attempt(attempt, ordinal, reason, Optional.of(previous));
    }

    /** Records a measured stage and returns its event id. */
    EventId stage(AttemptId attempt, String name, RouteDescriptor route, long start, long end,
                  UsageCounter... usage) {
        EventId id = nextId("stage");
        record(StageRecorded.measured(EventEnvelope.original(id, SCOPE, TASK, EXEC, attempt, at(end), at(end)),
                name, route, at(start), at(end), List.of(usage), List.of()));
        return id;
    }

    EventId stage(AttemptId attempt, String name, long start, long end, UsageCounter... usage) {
        return stage(attempt, name, ROUTE, start, end, usage);
    }

    EventId noBillable(AttemptId attempt, String name, long start, long end) {
        EventId id = nextId("stage");
        record(StageRecorded.noBillableUsage(EventEnvelope.original(id, SCOPE, TASK, EXEC, attempt, at(end), at(end)),
                name, RouteDescriptor.workerOnly("local"), at(start), at(end), "deterministic local check",
                List.of()));
        return id;
    }

    static QualityObservation observation(QualityDimension d, ObservationState state, OptionalDouble value,
                                          Optional<String> unit) {
        return new QualityObservation(d, state, value, unit, "junit", "5", "policy", "1", ObservationSource.TOOL,
                List.of(ArtifactRef.of("report", "r-" + d)),
                state == ObservationState.NOT_APPLICABLE ? Optional.of("not applicable here") : Optional.empty());
    }

    EventId evidence(AttemptId attempt, QualityDimension d, ObservationState state) {
        return evidence(attempt, observation(d, state, OptionalDouble.empty(), Optional.empty()));
    }

    EventId evidence(AttemptId attempt, QualityObservation... observations) {
        EventId id = nextId("evidence");
        record(new EvidenceRecorded(EventEnvelope.original(id, SCOPE, TASK, EXEC, attempt, T0, T0),
                List.of(observations), List.of()));
        return id;
    }

    Run finish(AttemptId attempt, AttemptResult result) {
        finishEvent(attempt, result);
        return this;
    }

    EventId finishEvent(AttemptId attempt, AttemptResult result) {
        EventId id = nextId("fin");
        record(new AttemptFinished(EventEnvelope.original(id, SCOPE, TASK, EXEC, attempt, T0, T0),
                result, Optional.empty(), Optional.empty()));
        return id;
    }

    /** Correction (revision {@code revision}) of {@code target}, replacing a finish with {@code result}. */
    EventId correctFinish(EventId target, int revision, AttemptId attempt, AttemptResult result) {
        EventId id = nextId("corr");
        EventEnvelope outer = new EventEnvelope(id, SCOPE, TASK, EXEC, Optional.of(attempt), revision,
                Optional.of(target), T0, T0);
        AttemptFinished replacement = new AttemptFinished(
                EventEnvelope.original(id, SCOPE, TASK, EXEC, attempt, T0, T0), result, Optional.empty(),
                Optional.empty());
        record(new Correction(outer, replacement, "attempt re-assessed"));
        return id;
    }

    long outcome(Outcome outcome) {
        return record(ExecutionEvent.outcomeRecorded(nextId("outcome"), SCOPE, TASK, EXEC, outcome, T0));
    }

    /** Correction (revision {@code revision}) of {@code target}, replacing a stage with {@code replacement}. */
    EventId correctStage(EventId target, int revision, AttemptId attempt, String name, long start, long end,
                      UsageCounter... usage) {
        EventId id = nextId("corr");
        EventEnvelope outer = new EventEnvelope(id, SCOPE, TASK, EXEC, Optional.of(attempt), revision,
                Optional.of(target), at(end), at(end));
        StageRecorded replacement = StageRecorded.measured(
                EventEnvelope.original(id, SCOPE, TASK, EXEC, attempt, at(end), at(end)), name, ROUTE, at(start),
                at(end), List.of(usage), List.of());
        record(new Correction(outer, replacement, "usage corrected"));
        return id;
    }

    EventId correctEvidence(EventId target, int revision, AttemptId attempt, QualityObservation... observations) {
        EventId id = nextId("corr");
        EventEnvelope outer = new EventEnvelope(id, SCOPE, TASK, EXEC, Optional.of(attempt), revision,
                Optional.of(target), T0, T0);
        EvidenceRecorded replacement = new EvidenceRecorded(
                EventEnvelope.original(id, SCOPE, TASK, EXEC, attempt, T0, T0), List.of(observations), List.of());
        record(new Correction(outer, replacement, "re-run"));
        return id;
    }

    ExecutionView view() {
        return memory.loadExecution(SCOPE, EXEC).orElseThrow();
    }

    long sequenceOf(EventId id) {
        return memory.loadEvent(SCOPE, id).orElseThrow().sequence();
    }

    @Override
    public void close() {
        memory.close();
    }

    // --- shared pricing -------------------------------------------------------------------------------

    static UsageCounter in(long v) {
        return UsageCounter.reported(UsageKind.INPUT_TOKENS, v, "provider");
    }

    static UsageCounter cached(long v) {
        return UsageCounter.reported(UsageKind.CACHED_INPUT_TOKENS, v, "provider");
    }

    static UsageCounter out(long v) {
        return UsageCounter.reported(UsageKind.OUTPUT_TOKENS, v, "provider");
    }

    static UsageCounter outEstimated(long v) {
        return UsageCounter.estimated(UsageKind.OUTPUT_TOKENS, v, "tokenizer");
    }

    /** USD per 1M tokens: input 2, cached input 0.5 (included in input), output 10. */
    static PriceSnapshot usd(String version, LocalDate effective) {
        return snapshot("prices", version, "prov", "model-x", "USD", effective,
                PriceLine.of(UsageKind.INPUT_TOKENS, new BigDecimal("2"), 1_000_000),
                new PriceLine(UsageKind.CACHED_INPUT_TOKENS, new BigDecimal("0.5"), 1_000_000,
                        Optional.of(UsageKind.INPUT_TOKENS)),
                PriceLine.of(UsageKind.OUTPUT_TOKENS, new BigDecimal("10"), 1_000_000));
    }

    static PriceSnapshot usd() {
        return usd("1", LocalDate.parse("2025-12-01"));
    }

    static PriceSnapshot snapshot(String id, String version, String provider, String model, String currency,
                                  LocalDate effective, PriceLine... lines) {
        return new PriceSnapshot(id, version, provider, model, PriceSnapshot.currency(currency), effective,
                "caller fixture", "fixed test prices, no live lookup", List.of(lines));
    }

    static PriceBook book(PriceSnapshot... snapshots) {
        return PriceBook.of(List.of(snapshots));
    }

    static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
