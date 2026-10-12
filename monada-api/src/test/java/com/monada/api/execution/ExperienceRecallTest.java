package com.monada.api.execution;

import static com.monada.api.execution.Experiences.experience;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperienceRecallTest {
    static final ScopeId ALPHA = ScopeId.of("project-alpha");
    static final ScopeId BETA = ScopeId.of("project-beta");
    static final String DISTRACTOR = "Fix the flaky gradle cache test in the build pipeline";

    @TempDir
    Path root;

    /** Scope alpha: three loosely related experiences. Scope beta: a perfect-match distractor. */
    static void seed(Path root) {
        try (ExecutionMemory beta = ExecutionMemory.open(root, BETA)) {
            experience(beta, BETA, "b-1", DISTRACTOR, "Pinned the gradle cache key", "Cache keys need versions");
        }
        try (ExecutionMemory alpha = ExecutionMemory.open(root, ALPHA)) {
            experience(alpha, ALPHA, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
            experience(alpha, ALPHA, "x-2", "Speed up the gradle build", "Enabled the configuration cache",
                    "Measure before optimizing");
            experience(alpha, ALPHA, "x-3", "Write release notes", null, null);
        }
    }

    @Test
    void foreignScopeDistractorNeverAppearsNorConsumesSlots() {
        seed(root);
        double distractorScore;
        try (ExecutionMemory beta = ExecutionMemory.openReadOnly(root, BETA)) {
            ExperienceRecall own = beta.recall(BETA, DISTRACTOR, 1);
            assertEquals(1, own.hits().size());
            distractorScore = own.hits().get(0).similarity();
        }
        try (ExecutionMemory alpha = ExecutionMemory.open(root, ALPHA)) {
            double alphaTop = alpha.recall(ALPHA, DISTRACTOR, 1).hits().get(0).similarity();
            assertTrue(distractorScore > alphaTop, "the distractor would win a mixed ranking");
            for (int limit = 1; limit <= 3; limit++) {
                ExperienceRecall recall = alpha.recall(ALPHA, DISTRACTOR, limit);
                assertEquals(ProjectionStatus.State.CURRENT, recall.status().state());
                assertEquals(limit, recall.hits().size(), "every slot is filled from alpha");
                recall.hits().forEach(h -> assertEquals(ALPHA, h.ref().scope()));
            }
            assertEquals("x-2", alpha.recall(ALPHA, DISTRACTOR, 1).hits().get(0).ref().execution().value());
            assertEquals(3, alpha.recall(ALPHA, DISTRACTOR, 50).hits().size(), "bounded by the scope's experiences");
        }
    }

    @Test
    void hitsCarryExactLedgerProvenance() {
        seed(root);
        try (ExecutionMemory alpha = ExecutionMemory.open(root, ALPHA)) {
            ExperienceRecall recall = alpha.recall(ALPHA, "flaky login test", 3);
            ExperienceHit top = recall.hits().get(0);
            ExperienceRef expected = new ExperienceRef(ALPHA, TaskId.of("task-x-1"), ExecutionId.of("x-1"),
                    Optional.of(AttemptId.of("a1")), EventId.of("x-1-a1-fin"), 1);
            assertEquals(expected, top.ref());
            assertEquals(alpha.loadEvent(ALPHA, top.ref().eventId(), top.ref().revision()).orElseThrow(), top.event());
            assertTrue(top.event().event() instanceof AttemptFinished);
            assertEquals(1, top.projectionVersion());
            assertEquals(9, top.coveredSequence());
            assertEquals(9, recall.status().ledgerSequence());
            assertTrue(top.similarity() > 0 && top.similarity() <= 1.0 + 1e-9);
            for (int i = 1; i < recall.hits().size(); i++) {
                assertTrue(recall.hits().get(i - 1).similarity() >= recall.hits().get(i).similarity());
            }
        }
    }

    @Test
    void blankQueriesAndLimitsAreValidated() {
        seed(root);
        try (ExecutionMemory alpha = ExecutionMemory.open(root, ALPHA)) {
            assertEquals(List.of(), alpha.recall(ALPHA, "", 5).hits());
            assertEquals(List.of(), alpha.recall(ALPHA, " \t\n", 5).hits());
            assertEquals(ProjectionStatus.State.CURRENT, alpha.recall(ALPHA, " ", 5).status().state());
            assertThrows(IllegalArgumentException.class, () -> alpha.recall(ALPHA, "gradle", 0));
            assertThrows(IllegalArgumentException.class, () -> alpha.recall(ALPHA, "gradle", 51));
            assertThrows(NullPointerException.class, () -> alpha.recall(ALPHA, null, 1));
            assertThrows(IllegalArgumentException.class, () -> alpha.recall(BETA, "gradle", 1), "bound scope only");
            assertTrue(alpha.recall(ALPHA, "gradle", 50).hits().size() <= 50);
        }
    }

    @Test
    void emptyScopeIsCurrentWithNoHits() {
        try (ExecutionMemory m = ExecutionMemory.open(root, ALPHA)) {
            ProjectionStatus status = m.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, status.state());
            assertEquals(0, status.coveredSequence());
            assertEquals(List.of(), m.recall(ALPHA, "anything", 5).hits());
        }
    }

    @Test
    void closedMemoryRejectsProjectionOperations() {
        ExecutionMemory m = ExecutionMemory.open(root, ALPHA);
        m.close();
        assertThrows(IllegalStateException.class, () -> m.recall(ALPHA, "x", 1));
        assertThrows(IllegalStateException.class, () -> m.projectionStatus(ALPHA));
        assertThrows(IllegalStateException.class, () -> m.rebuildProjection(ALPHA));
    }

    // ------------------------------------------------------------------ contract section 11 validation cases

    private static ExperienceHit hit(String atomId, double similarity, String eventId) {
        EventEnvelope env = EventEnvelope.original(EventId.of(eventId), ALPHA, TaskId.of("t"), ExecutionId.of("e"),
                AttemptId.of("a1"), Experiences.T0, Experiences.T0);
        ExecutionEvent event = new AttemptFinished(env, com.monada.core.execution.AttemptResult.COMPLETED,
                Optional.empty(), Optional.empty());
        ExperienceRef ref = new ExperienceRef(ALPHA, TaskId.of("t"), ExecutionId.of("e"),
                Optional.of(AttemptId.of("a1")), EventId.of(eventId), 1);
        return new ExperienceHit(ref, atomId, similarity, new HistoryEntry(1, event), 1, 1);
    }

    @Test
    void tiedAtomsOrderByAtomIdBeforeRef() {
        ExperienceHit aToZ = hit("00000000-0000-3000-8000-00000000000a", 0.5, "z");
        ExperienceHit bToA = hit("00000000-0000-3000-8000-00000000000b", 0.5, "a");
        assertEquals(List.of(aToZ), ExperienceProjection.order(List.of(bToA, aToZ), 1), "case (a)");
    }

    @Test
    void refsOfOneAtomOrderCanonically() {
        ExperienceHit r1 = hit("00000000-0000-3000-8000-00000000000a", 0.5, "r1");
        ExperienceHit r2 = hit("00000000-0000-3000-8000-00000000000a", 0.5, "r2");
        assertEquals(List.of(r1), ExperienceProjection.order(List.of(r2, r1), 1), "case (b), limit 1");
        assertEquals(List.of(r1, r2), ExperienceProjection.order(List.of(r2, r1), 2), "case (b), limit 2");
        ExperienceHit higher = hit("00000000-0000-3000-8000-00000000000f", 0.9, "zz");
        assertEquals(List.of(higher, r1), ExperienceProjection.order(List.of(r2, r1, higher), 2));
        assertNotEquals(r1, r2);
    }
}
