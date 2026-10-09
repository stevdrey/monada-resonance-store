package com.monada.api.execution;

import static com.monada.api.execution.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.LedgerRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionRevisionsAndHistoryTest {
    @TempDir Path root;

    @Test
    void correctionRetainsPriorRevisionsAndLookupSelectsRevision() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.PASS);
            assertEquals(RecordResult.Status.APPENDED, m.record(correctFinish("e6", "e5", 2, A1)).status());
            assertEquals(RecordResult.Status.APPENDED, m.record(correctFinish("e7", "e6", 3, A1)).status());
            // revisions are selected by chain, from any member of the chain
            for (String id : new String[] {"e5", "e6", "e7"}) {
                assertEquals(EventId.of("e5"), m.loadEvent(SCOPE, EventId.of(id), 1).orElseThrow().event().eventId());
                assertEquals(EventId.of("e6"), m.loadEvent(SCOPE, EventId.of(id), 2).orElseThrow().event().eventId());
                assertEquals(EventId.of("e7"), m.loadEvent(SCOPE, EventId.of(id), 3).orElseThrow().event().eventId());
                assertEquals(EventId.of("e7"), m.loadEvent(SCOPE, EventId.of(id)).orElseThrow().event().eventId());
                assertTrue(m.loadEvent(SCOPE, EventId.of(id), 4).isEmpty());
            }
            ExecutionView v = m.loadExecution(SCOPE, EXEC).orElseThrow();
            assertEquals(AttemptResult.FAILED, v.attempts().get(0).result().orElseThrow());
            assertEquals(7, v.history().size());
            assertEquals(1, v.attempts().get(0).stages().size()); // usage counted once, not per revision
        }
    }

    @Test
    void badCorrectionsAreExplicit() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.PASS);
            assertEquals(RecordResult.Status.CONFLICT, m.record(correctFinish("e6", "e5", 3, A1)).status());
            m.record(correctFinish("e7", "e5", 2, A1));
            RecordResult stale = m.record(correctFinish("e8", "e5", 3, A1)); // e5 is no longer the latest
            assertEquals(RecordResult.Status.CONFLICT, stale.status());
            assertThrows(IllegalArgumentException.class, () -> m.record(correctFinish("e9", "zz", 2, A1)));
            assertEquals(EventId.of("e7"), m.loadEvent(SCOPE, EventId.of("e5")).orElseThrow().event().eventId());
        }
    }

    @Test
    void pagingIsStableAndFollowsTheContractExample() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            m.record(finished("e3", A1));
            HistoryPage p1 = m.history(SCOPE, null, 2);
            assertEquals(List.of(1L, 2L), seqs(p1));
            assertTrue(p1.hasMore());
            HistoryCursor c = p1.next().orElseThrow();
            assertEquals(3, c.highWatermark());
            m.record(outcome("e4", com.monada.core.execution.Outcome.cancelled(
                    com.monada.core.execution.OutcomeOrigin.VALIDATED)));
            HistoryPage p2 = m.history(SCOPE, c, 2);
            assertEquals(List.of(3L), seqs(p2));
            assertFalse(p2.hasMore());
            assertEquals(seqs(p2), seqs(m.history(SCOPE, c, 2))); // same cursor, same page
            HistoryPage fresh = m.history(SCOPE, null, 2);
            assertEquals(List.of(1L, 2L), seqs(fresh));
            assertEquals(4, fresh.highWatermark());
            HistoryCursor parsed = HistoryCursor.parse(c.token());
            assertEquals(c, parsed);
        }
    }

    @Test
    void invalidCursorsAreRejectedAndEmptyHistoryIsEmpty() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            HistoryPage empty = m.history(SCOPE);
            assertTrue(empty.records().isEmpty());
            assertFalse(empty.hasMore());
            m.record(started("e1"));
            assertThrows(IllegalArgumentException.class,
                    () -> m.history(SCOPE, new HistoryCursor(SCOPE, 9, 1), 2));
            assertThrows(IllegalArgumentException.class,
                    () -> m.history(SCOPE, new HistoryCursor(ScopeId.of("other"), 1, 0), 2));
            assertThrows(IllegalArgumentException.class, () -> HistoryCursor.parse("not a cursor"));
            byte[] bad = new byte[] {'h', '1', '|', '1', '|', '0', '|', (byte) 0xC3, (byte) 0x28};
            String malformedUtf8 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bad);
            assertThrows(IllegalArgumentException.class, () -> HistoryCursor.parse(malformedUtf8));
            assertThrows(IllegalArgumentException.class, () -> new HistoryCursor(SCOPE, 1, 2));
        }
    }

    @Test
    void correctedAttemptStartKeepsEffectiveOrdinalOrder() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            m.record(attempt("e3", A2, 2));
            assertEquals(RecordResult.Status.APPENDED,
                    m.record(correctAttemptStart("e4", "e2", 2, A1, 1)).status());
            ExecutionView v = m.loadExecution(SCOPE, EXEC).orElseThrow();
            assertEquals(List.of(A1, A2), v.attempts().stream().map(AttemptView::id).toList());
            assertEquals(4, v.history().size());
        }
    }

    @Test
    void longCorrectionChainResolvesEveryRevision() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.PASS);
            String previous = "e5";
            for (int rev = 2; rev <= 51; rev++) {
                String id = "c" + rev;
                assertEquals(RecordResult.Status.APPENDED,
                        m.record(correctFinish(id, previous, rev, A1)).status());
                previous = id;
            }
            for (int rev = 1; rev <= 51; rev++) {
                String expected = rev == 1 ? "e5" : "c" + rev;
                assertEquals(EventId.of(expected),
                        m.loadEvent(SCOPE, EventId.of("c30"), rev).orElseThrow().event().eventId());
            }
            assertEquals(EventId.of("c51"), m.loadEvent(SCOPE, EventId.of("e5")).orElseThrow().event().eventId());
            assertTrue(m.loadEvent(SCOPE, EventId.of("e5"), 52).isEmpty());
            assertTrue(m.loadEvent(SCOPE, EventId.of("e5"), Integer.MAX_VALUE).isEmpty());
            assertTrue(m.loadEvent(SCOPE, EventId.of("missing")).isEmpty());
        }
    }

    private static List<Long> seqs(HistoryPage p) {
        return p.records().stream().map(LedgerRecord::sequence).toList();
    }
}
