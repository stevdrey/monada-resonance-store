package com.monada.api.execution;

import static com.monada.api.execution.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionMemoryRecordOutcomesTest {
    @TempDir Path root;

    private long totalBytes() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @Test
    void appendedThenIdempotentWritesNothingTheSecondTime() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            RecordResult first = m.record(started("e1"));
            long size = totalBytes();
            RecordResult retry = m.record(started("e1"));
            assertEquals(RecordResult.Status.APPENDED, first.status());
            assertEquals(RecordResult.Status.IDEMPOTENT, retry.status());
            assertEquals(first.sequence(), retry.sequence());
            assertEquals(size, totalBytes());
        }
    }

    @Test
    void conflictingPayloadAndDuplicateStartAreConflictsAndWriteNothing() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            long size = totalBytes();
            RecordResult sameIdOtherPayload = m.record(attempt("e2", A2, 1));
            RecordResult duplicateExecution = m.record(started("e9"));
            RecordResult ordinalReuse = m.record(attempt("e3", A2, 1));
            for (RecordResult r : new RecordResult[] {sameIdOtherPayload, duplicateExecution, ordinalReuse}) {
                assertEquals(RecordResult.Status.CONFLICT, r.status());
                assertTrue(r.reason().isPresent());
            }
            assertEquals(size, totalBytes());
        }
    }

    @Test
    void illegalTransitionsFailBeforeWriting() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertThrows(IllegalArgumentException.class, () -> m.record(attempt("e2", A1, 1))); // no execution
            baseRun(m, ObservationState.PASS);
            long size = totalBytes();
            assertThrows(IllegalArgumentException.class, () -> m.record(stage("e7", A1))); // after finish
            assertThrows(IllegalArgumentException.class, () -> m.record(stage("e8", A2))); // unknown attempt
            assertThrows(IllegalArgumentException.class, () -> m.record(finished("e9", A1))); // second finish
            assertEquals(size, totalBytes());
        }
    }

    @Test
    void foreignScopeEventAndNullFailBeforeIo() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            long size = totalBytes();
            ExecutionEvent foreign = ExecutionEvent.executionStarted(EventId.of("x"), ScopeId.of("other"), TASK,
                    EXEC, new com.monada.core.execution.SourceProvenance("r", "c", "k"),
                    new com.monada.core.execution.EvaluationPolicy("p", "1",
                            java.util.Set.of(com.monada.core.execution.QualityDimension.TESTS)), "s", T0);
            assertThrows(IllegalArgumentException.class, () -> m.record(foreign));
            assertThrows(NullPointerException.class, () -> m.record(null));
            assertEquals(size, totalBytes());
        }
    }
}
