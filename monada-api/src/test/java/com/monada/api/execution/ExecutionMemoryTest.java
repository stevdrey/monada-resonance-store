package com.monada.api.execution;

import static com.monada.api.execution.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.ScopeId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionMemoryTest {
    @TempDir Path root;

    @Test
    void recordCloseReopenLoadReturnsUnchangedProvenanceAndEvidence() {
        ExecutionView before;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.PASS);
            m.record(outcome("e6", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            before = m.loadExecution(SCOPE, EXEC).orElseThrow();
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            ExecutionView after = m.loadExecution(SCOPE, EXEC).orElseThrow();
            assertEquals(before, after);
            assertEquals("rev-1", after.provenance().sourceRevision());
            AttemptView a = m.loadAttempt(SCOPE, EXEC, A1).orElseThrow();
            assertEquals("worker-a", a.stages().get(0).route().worker());
            assertEquals(ObservationState.PASS, a.evidence().get(0).observations().get(0).state());
            assertTrue(m.loadAttempt(SCOPE, EXEC, A2).isEmpty());
            assertTrue(m.loadExecution(SCOPE, com.monada.core.execution.ExecutionId.of("nope")).isEmpty());
        }
    }

    @Test
    void closeIsIdempotentAndOperationsFailAfterClose() {
        ExecutionMemory m = ExecutionMemory.open(root, SCOPE);
        m.close();
        m.close();
        assertThrows(IllegalStateException.class, () -> m.record(started("e1")));
        assertThrows(IllegalStateException.class, () -> m.loadExecution(SCOPE, EXEC));
        assertThrows(IllegalStateException.class, () -> m.history(SCOPE));
        assertThrows(IllegalStateException.class, m::scope);
    }

    @Test
    void lifecycleIsCheckedBeforeNullArguments() {
        ExecutionMemory m = ExecutionMemory.open(root, SCOPE);
        assertThrows(NullPointerException.class, () -> m.record(null));
        assertThrows(NullPointerException.class, () -> m.loadExecution(SCOPE, null));
        assertThrows(NullPointerException.class, () -> m.loadEvent(SCOPE, null));
        m.close();
        assertThrows(IllegalStateException.class, () -> m.record(null));
        assertThrows(IllegalStateException.class, () -> m.loadExecution(SCOPE, null));
        assertThrows(IllegalStateException.class, () -> m.loadAttempt(SCOPE, EXEC, null));
        assertThrows(IllegalStateException.class, () -> m.loadEvent(SCOPE, null, 1));
    }

    @Test
    void secondOwnerFailsFastAndOwnershipIsReleasedOnClose() {
        ExecutionMemory first = ExecutionMemory.open(root, SCOPE);
        assertThrows(ExecutionMemoryLockedException.class, () -> ExecutionMemory.open(root, SCOPE));
        first.close();
        ExecutionMemory.open(root, SCOPE).close();
    }

    @Test
    void foreignScopeIsRejected() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertThrows(IllegalArgumentException.class, () -> m.loadExecution(ScopeId.of("other"), EXEC));
            assertThrows(IllegalArgumentException.class, () -> m.history(ScopeId.of("other")));
        }
    }

    @Test
    void historyIsBoundedByConfig() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE,
                ExecutionMemoryConfig.defaults().withDefaultPageSize(5).withMaxPageSize(10))) {
            assertThrows(IllegalArgumentException.class, () -> m.history(SCOPE, null, 11));
            assertThrows(IllegalArgumentException.class, () -> m.history(SCOPE, null, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new ExecutionMemoryConfig(1, 501));
    }
}
