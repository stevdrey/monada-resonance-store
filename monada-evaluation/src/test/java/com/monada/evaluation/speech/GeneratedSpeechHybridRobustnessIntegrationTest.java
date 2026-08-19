package com.monada.evaluation.speech;

import com.monada.speech.retrieval.SpeechSampleRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSpeechHybridRobustnessIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedFixtureCoversRequiredGroupsAndReportsTheRiskFromMissingModalities() throws IOException {
        SpeechHybridRobustnessReport report = run(tempDir.resolve("fixture"));

        assertEquals(new SpeechFusionWeights(0.5, 0.5), report.candidateWeights());
        assertEquals(8, report.queryResults().size());
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY, report.decision());
        assertEquals(Set.of(SpeechHybridConflictCategory.values()), report.queryResults().stream()
                .map(result -> result.stressCase().expectedConflictCategory()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(report.byCondition().containsKey("CONTROL"));
        assertTrue(report.byCondition().containsKey("DYSARTHRIC"));
        assertTrue(report.byTask().keySet().containsAll(Set.of("WORD", "SENTENCE", "COMMAND")));
        assertTrue(report.bySpeakerRelation().keySet().containsAll(Set.of("SAME_SPEAKER", "CROSS_SPEAKER")));
        assertEquals("robust_acoustic_missing_cross", report.worstRegression().stressCase().query().queryId());
        assertTrue(report.render().contains("Fixed candidate inherited from #78: T=0.50 A=0.50"));
    }

    @Test
    void directConflictsAreStrictAndMissingCandidatesReceiveNoImputedHybridScore() throws IOException {
        SpeechHybridRobustnessReport report = run(tempDir.resolve("conflicts"));
        SpeechHybridRobustnessQueryResult acousticConflict = query(report, "robust_acoustic_conflict_same");
        SpeechHybridRobustnessQueryResult transcriptConflict = query(report, "robust_transcript_conflict_cross");
        SpeechHybridRobustnessQueryResult transcriptMissing = query(report, "robust_transcript_missing_same");
        SpeechHybridRobustnessQueryResult acousticMissing = query(report, "robust_acoustic_missing_cross");

        assertEquals(SpeechHybridBetterControl.ACOUSTIC, acousticConflict.strongerControl());
        assertEquals(SpeechHybridBetterControl.TRANSCRIPT, transcriptConflict.strongerControl());
        assertEquals(SpeechHybridComparison.MAINTAIN, acousticConflict.vsStrongerControl());
        assertEquals(SpeechHybridComparison.MAINTAIN, transcriptConflict.vsStrongerControl());
        assertEquals(SpeechHybridComparison.REGRESS, transcriptMissing.vsStrongerControl());
        assertEquals(SpeechHybridComparison.REGRESS, acousticMissing.vsStrongerControl());
        assertTrue(transcriptMissing.context().availabilityIds().get(SpeechHybridCandidateAvailability.TRANSCRIPT_MISSING)
                .contains("d_acoustic_target_1760"));
        assertTrue(acousticMissing.context().availabilityIds().get(SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING)
                .contains("c_text_target_1320"));
        assertFalse(transcriptMissing.hybrid().result().topResults().stream()
                .anyMatch(result -> result.sampleId().equals("d_acoustic_target_1760")));
        assertFalse(acousticMissing.hybrid().result().topResults().stream()
                .anyMatch(result -> result.sampleId().equals("c_text_target_1320")));
    }

    @Test
    void reportIsDeterministicAcrossFreshGeneratedStores() throws IOException {
        assertEquals(run(tempDir.resolve("first")).render(), run(tempDir.resolve("second")).render());
    }

    @Test
    void unknownAvailabilityMaskIdsAreRejectedAgainstTheCollectedCorpusSet() throws IOException {
        Path root = tempDir.resolve("unknown-mask");
        GeneratedSpeechHybridRobustnessFixture.Fixture fixture = GeneratedSpeechHybridRobustnessFixture.create(root);
        List<SpeechHybridRobustnessCase> cases = new ArrayList<>(fixture.cases());
        int index = cases.stream().map(SpeechHybridRobustnessCase::expectedConflictCategory)
                .toList().indexOf(SpeechHybridConflictCategory.TRANSCRIPT_MISSING);
        SpeechHybridRobustnessCase original = cases.get(index);
        cases.set(index, new SpeechHybridRobustnessCase(
                original.query(), original.querySpeakerId(), original.expectedConflictCategory(),
                Set.of("missing-sample"), Set.of()));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new SpeechHybridRobustnessRunner().run(
                SpeechModalityEvidence.GENERATED_CI,
                GeneratedSpeechHybridRobustnessFixture.LABEL,
                cases,
                fixture.k(),
                new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(),
                fixture.featureStore(),
                root.resolve("transcript-memory")));

        assertTrue(failure.getMessage().contains("transcript availability mask references unknown samples: [missing-sample]"));
    }

    private SpeechHybridRobustnessReport run(Path root) throws IOException {
        GeneratedSpeechHybridRobustnessFixture.Fixture fixture = GeneratedSpeechHybridRobustnessFixture.create(root);
        return new SpeechHybridRobustnessRunner().run(
                SpeechModalityEvidence.GENERATED_CI,
                GeneratedSpeechHybridRobustnessFixture.LABEL,
                fixture.cases(),
                fixture.k(),
                new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(),
                fixture.featureStore(),
                root.resolve("transcript-memory"));
    }

    private SpeechHybridRobustnessQueryResult query(SpeechHybridRobustnessReport report, String queryId) {
        return report.queryResults().stream()
                .filter(result -> result.stressCase().query().queryId().equals(queryId))
                .findFirst().orElseThrow();
    }
}
