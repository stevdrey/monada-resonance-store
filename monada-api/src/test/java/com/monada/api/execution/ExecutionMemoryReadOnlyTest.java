package com.monada.api.execution;

import static com.monada.api.execution.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.monada.core.execution.ObservationState;
import com.monada.core.execution.ScopeId;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionMemoryReadOnlyTest {
    @TempDir Path root;

    @Test
    void readsTheValidPrefixNextToALiveWriterWithoutBlockingIt() {
        try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
            baseRun(writer, ObservationState.PASS);
            try (ExecutionMemory reader = ExecutionMemory.openReadOnly(root, SCOPE)) {
                assertEquals(writer.loadExecution(SCOPE, EXEC), reader.loadExecution(SCOPE, EXEC));
                assertEquals(5, reader.history(SCOPE).records().size());
                assertTrue(reader.loadAttempt(SCOPE, EXEC, A1).isPresent());
                // the writer is not blocked and keeps appending
                assertEquals(RecordResult.Status.APPENDED, writer.record(
                        outcome("e6", com.monada.core.execution.Outcome.cancelled(
                                com.monada.core.execution.OutcomeOrigin.VALIDATED))).status());
                // a snapshot: the new append is invisible to it, but visible to a new read-only instance
                assertEquals(5, reader.history(SCOPE).records().size());
                try (ExecutionMemory fresh = ExecutionMemory.openReadOnly(root, SCOPE)) {
                    assertEquals(6, fresh.history(SCOPE).records().size());
                }
            }
        }
    }

    @Test
    void recordIsUnsupportedAndLifecycleStillApplies() {
        try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
            writer.record(started("e1"));
        }
        ExecutionMemory reader = ExecutionMemory.openReadOnly(root, SCOPE);
        assertThrows(UnsupportedOperationException.class, () -> reader.record(started("e2")));
        assertTrue(reader.loadEvent(SCOPE, com.monada.core.execution.EventId.of("e1")).isPresent());
        reader.close();
        reader.close();
        assertThrows(IllegalStateException.class, () -> reader.history(SCOPE));
        assertThrows(IllegalStateException.class, () -> reader.record(null));
        // the exclusive writer lock was never taken by the read-only instance
        ExecutionMemory.open(root, SCOPE).close();
    }

    @Test
    void missingRootFailsCleanlyAndCreatesNothing() {
        Path missing = root.resolve("absent");
        assertThrows(UncheckedIOException.class, () -> ExecutionMemory.openReadOnly(missing, SCOPE));
        assertFalse(Files.exists(missing));
    }

    @Test
    void missingScopeIsAnEmptyHistory() {
        try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
            writer.record(started("e1"));
        }
        try (ExecutionMemory reader = ExecutionMemory.openReadOnly(root, ScopeId.of("other"))) {
            assertTrue(reader.history(ScopeId.of("other")).records().isEmpty());
            assertTrue(reader.loadExecution(ScopeId.of("other"), EXEC).isEmpty());
        }
    }
}
