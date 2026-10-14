package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** The multi-attempt corpus (issue #121) holds the required scenarios, and its loader rejects inconsistent judgments. */
class MultiAttemptFixtureTest {
    private final MultiAttemptFixture fixture = MultiAttemptFixture.load();

    @Test
    void isASeparateVersionedFixtureWithTwoOpaqueProjects() {
        assertEquals("1", fixture.version());
        assertTrue(fixture.provenance().contains("synthetic"));
        assertEquals(List.of("alpha", "beta"), List.copyOf(fixture.scopeIds().keySet()));
        assertEquals(2, Set.copyOf(fixture.scopeIds().values()).size());
        ForgeFixture v1 = ForgeFixture.load();
        assertNotEquals(ForgeFixture.RESOURCE_ROOT, MultiAttemptFixture.RESOURCE_ROOT);
        for (String id : fixture.scopeIds().values()) {
            assertFalse(v1.scopeIds().containsValue(id), id);
        }
        for (String scopeKey : fixture.scopeIds().keySet()) {
            assertTrue(fixture.executionsOf(scopeKey).size() > fixture.pageSize(), scopeKey + " must page");
        }
    }

    @Test
    void forgeExperienceV1IsUnchanged() {
        ForgeFixture v1 = ForgeFixture.load();
        assertEquals("1", v1.version());
        assertEquals(25, v1.attempts().size());
        assertEquals(18, v1.executions().size());
        assertEquals(14, v1.queries().size());
    }

    @Test
    void holdsRetriesInsideOneExecution() {
        assertEquals(List.of("FAILED", "COMPLETED"), results("m-repair"));
        assertEquals(List.of("CANCELLED", "COMPLETED"), results("m-abandon"));
        assertEquals(List.of("FAILED", "FAILED", "COMPLETED"), results("m-chain3"));
        assertEquals(List.of("FAILED", MultiAttemptFixture.UNFINISHED), results("m-unfinished"));
        for (String exec : List.of("m-repair", "m-abandon", "m-chain3")) {
            assertEquals("SUCCESS", fixture.execution(exec).expectedLabel());
            List<MultiAttemptFixture.Attempt> attempts = fixture.attemptsOf(exec);
            // only the last attempt is the accepted one; the earlier ones share the SUCCESS label but are not accepted
            assertTrue(attempts.get(attempts.size() - 1).judgedAccepted(), exec);
            attempts.subList(0, attempts.size() - 1).forEach(a -> assertFalse(a.judgedAccepted(), a.experienceId()));
        }
    }

    @Test
    void holdsACorrectionThatChangesTheAttemptResult() {
        MultiAttemptFixture.Attempt a = fixture.findAttempt("m-correct/a1").orElseThrow();
        assertEquals("COMPLETED", a.result());
        assertEquals("FAILED", a.latestResult());
        assertEquals(2, a.latestRevision());
        assertTrue(fixture.cutoffEvent().startsWith("m-correct.a1."));
    }

    @Test
    void holdsIdenticalSummariesWithinAndAcrossProjects() {
        assertEquals(fixture.execution("m-twin-ok").summary(), fixture.execution("m-twin-ko").summary());
        assertEquals(fixture.execution("m-twin-ok").scopeKey(), fixture.execution("m-twin-ko").scopeKey());
        assertEquals(fixture.execution("m-repair").summary(), fixture.execution("n-repair").summary());
        assertNotEquals(fixture.execution("m-repair").scopeKey(), fixture.execution("n-repair").scopeKey());
    }

    @Test
    void rejectsAnAcceptedAttemptOutsideAValidatedAcceptance() {
        // n-abandon/a2 is named by an imported claim only: it can never be the validated accepted attempt
        assertInvalid(withAttempt("n-abandon/a2", a -> copy(a, a.result(), true)), "judged accepted attempt");
    }

    @Test
    void rejectsAFailedAttemptJudgedAccepted() {
        assertInvalid(withAttempt("m-repair/a1", a -> copy(a, a.result(), true)), "judged accepted attempt");
    }

    @Test
    void rejectsAnUnfinishedAttemptThatIsNotTheLast() {
        assertInvalid(withAttempt("m-repair/a1", a -> new MultiAttemptFixture.Attempt(a.execution(), a.ordinal(),
                a.reason(), MultiAttemptFixture.UNFINISHED, null, null, a.inputTokens(), a.outputTokens(),
                a.startSeconds(), a.endSeconds(), a.evidence(), null, null, null, false)), "only the last attempt");
    }

    @Test
    void rejectsAPartiallyPopulatedUnfinishedAttempt() {
        assertInvalid(withAttempt("m-unfinished/a2", a -> unfinished(a, "Started embedding the fonts", null)),
                "neither a solution nor a lesson");
        assertInvalid(withAttempt("m-unfinished/a2", a -> unfinished(a, null, "Fonts must be embedded")),
                "neither a solution nor a lesson");
    }

    @Test
    void rejectsAPartiallyPopulatedFinishedAttempt() {
        assertInvalid(withAttempt("m-repair/a1", a -> new MultiAttemptFixture.Attempt(a.execution(), a.ordinal(),
                a.reason(), a.result(), a.solution(), null, a.inputTokens(), a.outputTokens(), a.startSeconds(),
                a.endSeconds(), a.evidence(), null, null, null, false)), "needs a solution and a lesson");
    }

    private static MultiAttemptFixture.Attempt unfinished(MultiAttemptFixture.Attempt a, String solution,
                                                          String lesson) {
        return new MultiAttemptFixture.Attempt(a.execution(), a.ordinal(), a.reason(), MultiAttemptFixture.UNFINISHED,
                solution, lesson, a.inputTokens(), a.outputTokens(), a.startSeconds(), a.endSeconds(), a.evidence(),
                null, null, null, false);
    }

    @Test
    void rejectsACutoffRowForAnUnknownAttempt() {
        List<MultiAttemptFixture.CutoffAttempt> cutoff = new ArrayList<>(fixture.cutoff());
        cutoff.add(new MultiAttemptFixture.CutoffAttempt("m-correct", 9, "FAILED", 1, false, "UNLABELED", "PENDING"));
        assertInvalid(new MultiAttemptFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(),
                fixture.price(), fixture.recallLimit(), fixture.pageSize(), fixture.cutoffScope(),
                fixture.cutoffEvent(), fixture.executions(), fixture.attempts(), fixture.queries(), cutoff),
                "unknown attempt");
    }

    @Test
    void rejectsAFixtureWithoutAResultChangingCorrection() {
        assertInvalid(withAttempt("m-correct/a1", a -> new MultiAttemptFixture.Attempt(a.execution(), a.ordinal(),
                a.reason(), a.result(), a.solution(), a.lesson(), a.inputTokens(), a.outputTokens(), a.startSeconds(),
                a.endSeconds(), a.evidence(), a.result(), a.revisedSolution(), a.revisedLesson(), false)),
                "changes the attempt result");
    }

    private List<String> results(String exec) {
        return fixture.attemptsOf(exec).stream().map(MultiAttemptFixture.Attempt::latestResult).toList();
    }

    private static MultiAttemptFixture.Attempt copy(MultiAttemptFixture.Attempt a, String result, boolean accepted) {
        return new MultiAttemptFixture.Attempt(a.execution(), a.ordinal(), a.reason(), result, a.solution(),
                a.lesson(), a.inputTokens(), a.outputTokens(), a.startSeconds(), a.endSeconds(), a.evidence(),
                a.revisedResult(), a.revisedSolution(), a.revisedLesson(), accepted);
    }

    private MultiAttemptFixture withAttempt(String experienceId, UnaryOperator<MultiAttemptFixture.Attempt> change) {
        List<MultiAttemptFixture.Attempt> attempts = fixture.attempts().stream()
                .map(a -> a.experienceId().equals(experienceId) ? change.apply(a) : a).toList();
        return new MultiAttemptFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(), fixture.price(),
                fixture.recallLimit(), fixture.pageSize(), fixture.cutoffScope(), fixture.cutoffEvent(),
                fixture.executions(), attempts, fixture.queries(), fixture.cutoff());
    }

    private static void assertInvalid(MultiAttemptFixture candidate, String message) {
        IllegalStateException e = assertThrows(IllegalStateException.class, candidate::validate);
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
