package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceHit;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExportRequest;
import com.monada.core.execution.ScopeId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The attempt-level join (issue #121) on real hits and samples of the alpha project of {@code forge-multi-attempt-v1}:
 * what an attempt did stays apart from its execution's raw label, and a hit that cannot be joined exactly is reported
 * as such instead of borrowing the execution's verdict.
 */
class AttemptJoinTest {
    @TempDir
    static Path root;

    private static final MultiAttemptFixture FIXTURE = MultiAttemptFixture.load();
    private static ExecutionMemory memory;
    private static List<ExperienceSample> samples;
    private static final Map<String, ExperienceHit> HITS = new LinkedHashMap<>();

    @BeforeAll
    static void recordAlpha() {
        ScopeId scope = MultiAttemptFixtureRecorder.scope(FIXTURE, "alpha");
        try (ExecutionMemory writer = ExecutionMemory.open(root, scope)) {
            MultiAttemptFixtureRecorder.recordScope(writer, FIXTURE, "alpha");
            writer.rebuildProjection(scope);
        }
        memory = ExecutionMemory.openReadOnly(root, scope);
        samples = memory.exportSamples(ExportRequest.firstPage(scope, 50)).samples();
        for (MultiAttemptFixture.Query q : FIXTURE.queries()) {
            if (q.scopeKey().equals("alpha")) {
                memory.recall(scope, q.text(), 50).hits().forEach(h -> HITS.putIfAbsent(
                        ForgeExperienceEvaluator.experienceId(h.ref()), h));
            }
        }
    }

    @AfterAll
    static void close() {
        memory.close();
    }

    private static ExperienceHit hit(String experienceId) {
        ExperienceHit h = HITS.get(experienceId);
        assertTrue(h != null, "not recalled: " + experienceId);
        return h;
    }

    private static AttemptJoin.Row join(String experienceId) {
        return AttemptJoin.join(hit(experienceId), samples, Long.MAX_VALUE);
    }

    @Test
    void failedAttemptOfASuccessExecutionKeepsTheRawLabelAndIsNotTheAcceptedAttempt() {
        AttemptJoin.Row r = join("m-repair/a1");
        assertEquals(AttemptJoin.Status.JOINED, r.status());
        assertEquals("FAILED", r.attemptResult());
        assertEquals("SUCCESS", r.executionLabel()); // the raw execution label, unchanged
        assertEquals("VALIDATED_ACCEPTED", r.executionAcceptance());
        assertFalse(r.markedAccepted());
        assertFalse(r.validatedAcceptedAttempt());
        assertEquals("m-repair.a1.fin", r.eventId());
        assertEquals(1, r.revision());
        assertEquals(hit("m-repair/a1").event().sequence(), r.sequence());
    }

    @Test
    void theRepairThatTheValidatedAcceptanceNamesIsTheAcceptedAttempt() {
        AttemptJoin.Row r = join("m-repair/a2");
        assertEquals("COMPLETED", r.attemptResult());
        assertTrue(r.markedAccepted());
        assertTrue(r.validatedAcceptedAttempt());
        assertEquals("SUCCESS", r.executionLabel());
    }

    @Test
    void abandonedAttemptBeforeAnAcceptedRetryIsCancelledNotAccepted() {
        AttemptJoin.Row r = join("m-abandon/a1");
        assertEquals("CANCELLED", r.attemptResult());
        assertFalse(r.validatedAcceptedAttempt());
        assertEquals("SUCCESS", r.executionLabel());
    }

    @Test
    void correctedAttemptJoinsAtItsLatestRevisionWithTheCorrectedResult() {
        AttemptJoin.Row r = join("m-correct/a1");
        assertEquals(AttemptJoin.Status.JOINED, r.status());
        assertEquals(2, r.revision());
        assertEquals("FAILED", r.attemptResult()); // recorded COMPLETED, corrected to FAILED
        assertFalse(r.validatedAcceptedAttempt());
    }

    @Test
    void anUnfinishedAttemptIsNeverRecalledButItsExecutionStaysPending() {
        assertFalse(HITS.containsKey("m-unfinished/a2"));
        AttemptJoin.Row r = join("m-unfinished/a1");
        assertEquals("FAILED", r.attemptResult());
        assertEquals("UNLABELED", r.executionLabel());
        assertEquals("PENDING", r.executionAcceptance());
    }

    @Test
    void identicalTextsOfIndependentExecutionsJoinTheirOwnSamples() {
        AttemptJoin.Row ok = join("m-twin-ok/a1");
        AttemptJoin.Row ko = join("m-twin-ko/a1");
        assertEquals(hit("m-twin-ok/a1").atomId(), hit("m-twin-ko/a1").atomId()); // one shared atom, two refs
        assertEquals("SUCCESS", ok.executionLabel());
        assertTrue(ok.validatedAcceptedAttempt());
        assertEquals("FAILURE", ko.executionLabel());
        assertEquals("FAILED", ko.attemptResult());
        assertFalse(ko.validatedAcceptedAttempt());
    }

    @Test
    void aHitAfterTheCutoffIsNotJoinedToAnEarlierState() {
        ExperienceHit h = hit("m-repair/a2");
        AttemptJoin.Row r = AttemptJoin.join(h, samples, h.event().sequence() - 1);
        assertEquals(AttemptJoin.Status.BEYOND_CUTOFF, r.status());
        assertEquals(AttemptJoin.NONE, r.executionLabel());
        assertEquals(AttemptJoin.NONE, r.attemptResult());
        assertFalse(r.validatedAcceptedAttempt());
        assertEquals(AttemptJoin.Status.JOINED, AttemptJoin.join(h, samples, h.event().sequence()).status());
    }

    @Test
    void aHitWhoseExecutionWasNotExportedHasNoSample() {
        List<ExperienceSample> others = samples.stream()
                .filter(s -> !s.executionId().value().equals("m-repair")).toList();
        AttemptJoin.Row r = AttemptJoin.join(hit("m-repair/a1"), others, Long.MAX_VALUE);
        assertEquals(AttemptJoin.Status.NO_SAMPLE, r.status());
        assertEquals(AttemptJoin.NONE, r.executionLabel());
    }

    @Test
    void aSampleWithoutTheHitsAttemptDoesNotLendItsLabelAsAnAttemptResult() {
        ExperienceSample repair = ForgeTestSupport.sampleOf(samples, "m-repair");
        ExperienceSample withoutFirst = ForgeTestSupport.withAttempts(repair, repair.attempts().subList(1, 2));
        AttemptJoin.Row r = AttemptJoin.join(hit("m-repair/a1"), replace(withoutFirst), Long.MAX_VALUE);
        assertEquals(AttemptJoin.Status.ATTEMPT_NOT_IN_SAMPLE, r.status());
        assertEquals(AttemptJoin.NONE, r.attemptResult());
        assertFalse(r.validatedAcceptedAttempt());
    }

    @Test
    void aSampleAttemptWithAnotherFinishIsAMismatch() {
        ExperienceSample repair = ForgeTestSupport.sampleOf(samples, "m-repair");
        ExperienceSample forged = ForgeTestSupport.withAttempt(repair, 0, a -> ForgeTestSupport.withFinish(a,
                ForgeTestSupport.ref(a.finish().orElseThrow(), "m-repair.a1.stage", 1)));
        AttemptJoin.Row r = AttemptJoin.join(hit("m-repair/a1"), replace(forged), Long.MAX_VALUE);
        assertEquals(AttemptJoin.Status.FINISH_MISMATCH, r.status());
    }

    @Test
    void aMismatchedFinishOfTheAcceptedAttemptIsNeverReportedAsValidated() {
        // the sample's a2 is COMPLETED, marked accepted and VALIDATED_ACCEPTED, but its finish is not the hit's fact
        ExperienceSample repair = ForgeTestSupport.sampleOf(samples, "m-repair");
        ExperienceSample forged = ForgeTestSupport.withAttempt(repair, 1, a -> ForgeTestSupport.withFinish(a,
                ForgeTestSupport.ref(a.finish().orElseThrow(), "m-repair.a2.stage", 1)));
        AttemptJoin.Row r = AttemptJoin.join(hit("m-repair/a2"), replace(forged), Long.MAX_VALUE);
        assertEquals(AttemptJoin.Status.FINISH_MISMATCH, r.status());
        assertEquals(AttemptJoin.NONE, r.attemptResult());
        assertFalse(r.markedAccepted());
        assertFalse(r.validatedAcceptedAttempt());
        assertEquals("SUCCESS", r.executionLabel()); // the execution was found; only the attempt fact was not
        assertTrue(MultiAttemptEvaluator.rowViolations(FIXTURE, "T", r).stream()
                .anyMatch(v -> v.contains("did not join its exported sample (FINISH_MISMATCH)")));
    }

    @Test
    void aFailedAttemptWronglyMarkedAcceptedIsStillNotReportedAsTheAcceptedAttempt() {
        ExperienceSample repair = ForgeTestSupport.sampleOf(samples, "m-repair");
        ExperienceSample forged = ForgeTestSupport.withAttempt(repair, 0, a -> ForgeTestSupport.copyAttempt(a, true,
                a.previousAttempt(), a.solutionSummary(), a.lessonSummary(), a.evidence()));
        AttemptJoin.Row r = AttemptJoin.join(hit("m-repair/a1"), replace(forged), Long.MAX_VALUE);
        assertEquals(AttemptJoin.Status.JOINED, r.status());
        assertTrue(r.markedAccepted());
        assertFalse(r.validatedAcceptedAttempt()); // a FAILED attempt is never the accepted attempt
        // the gate still reports the forged marker against the recorded outcome
        assertTrue(MultiAttemptEvaluator.rowViolations(FIXTURE, "T", r).stream()
                .anyMatch(v -> v.contains("outcome marker true")), MultiAttemptEvaluator.rowViolations(FIXTURE, "T", r)
                .toString());
    }

    @Test
    void rowChecksRejectAFailedAttemptReportedAsAcceptedAndAReinterpretedLabel() {
        AttemptJoin.Row good = join("m-repair/a1");
        assertEquals(List.of(), MultiAttemptEvaluator.rowViolations(FIXTURE, "T", good));
        AttemptJoin.Row acceptedFailure = new AttemptJoin.Row(good.experienceId(), good.eventId(), good.revision(),
                good.sequence(), good.status(), good.executionLabel(), good.executionAcceptance(), "FAILED", false,
                true);
        assertTrue(MultiAttemptEvaluator.rowViolations(FIXTURE, "T", acceptedFailure).stream()
                .anyMatch(v -> v.contains("FAILED attempt reported as the accepted attempt")));
        AttemptJoin.Row relabelled = new AttemptJoin.Row(good.experienceId(), good.eventId(), good.revision(),
                good.sequence(), good.status(), "FAILURE", good.executionAcceptance(), "FAILED", false, false);
        assertTrue(MultiAttemptEvaluator.rowViolations(FIXTURE, "T", relabelled).stream()
                .anyMatch(v -> v.contains("execution label FAILURE but judged SUCCESS")));
    }

    /** The export with the sample of the same execution replaced by {@code forged}. */
    private static List<ExperienceSample> replace(ExperienceSample forged) {
        List<ExperienceSample> out = new ArrayList<>();
        for (ExperienceSample s : samples) {
            out.add(s.executionId().equals(forged.executionId()) ? forged : s);
        }
        return out;
    }

    @Test
    void joiningIsPureAndRepeatable() {
        ExperienceHit h = hit("m-chain3/a2");
        assertEquals(AttemptJoin.join(h, samples, Long.MAX_VALUE), AttemptJoin.join(h, List.copyOf(samples),
                Long.MAX_VALUE));
        assertEquals(Optional.of("FAILED"), Optional.of(AttemptJoin.join(h, samples, Long.MAX_VALUE).attemptResult()));
    }
}
