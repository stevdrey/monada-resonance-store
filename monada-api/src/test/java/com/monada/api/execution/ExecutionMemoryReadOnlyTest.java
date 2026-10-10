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
                assertEquals(5, reader.history(SCOPE).entries().size());
                assertTrue(reader.loadAttempt(SCOPE, EXEC, A1).isPresent());
                // the writer is not blocked and keeps appending
                assertEquals(RecordResult.Status.APPENDED, writer.record(
                        outcome("e6", com.monada.core.execution.Outcome.cancelled(
                                com.monada.core.execution.OutcomeOrigin.VALIDATED))).status());
                // a snapshot: the new append is invisible to it, but visible to a new read-only instance
                assertEquals(5, reader.history(SCOPE).entries().size());
                try (ExecutionMemory fresh = ExecutionMemory.openReadOnly(root, SCOPE)) {
                    assertEquals(6, fresh.history(SCOPE).entries().size());
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
            assertTrue(reader.history(ScopeId.of("other")).entries().isEmpty());
            assertTrue(reader.loadExecution(ScopeId.of("other"), EXEC).isEmpty());
        }
    }

    private Path segment() throws java.io.IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().equals("events-000001.log")).findFirst().orElseThrow();
        }
    }

    @Test
    void corruptedLedgerIsRefusedInsteadOfServedTruncated() throws java.io.IOException {
        try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
            baseRun(writer, ObservationState.PASS);
        }
        Path segment = segment();
        String text = Files.readString(segment);
        int second = text.indexOf('\n') + 1;
        // damage the payload of the second record so its digest no longer matches
        Files.writeString(segment, text.substring(0, second) + text.substring(second).replaceFirst("\\|", "!"));
        assertThrows(UncheckedIOException.class, () -> ExecutionMemory.openReadOnly(root, SCOPE));
    }

    @Test
    void tornTailIsToleratedButReported() throws java.io.IOException {
        try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
            baseRun(writer, ObservationState.PASS);
        }
        Files.writeString(segment(), "MXL1\t6\t99\tpartial", java.nio.file.StandardOpenOption.APPEND);
        try (ExecutionMemory reader = ExecutionMemory.openReadOnly(root, SCOPE)) {
            assertTrue(reader.hasTornTail());
            assertEquals(5, reader.history(SCOPE).entries().size());
        }
        try (ExecutionMemory healthy = ExecutionMemory.openReadOnly(root, ScopeId.of("other"))) {
            assertFalse(healthy.hasTornTail());
        }
    }
}
