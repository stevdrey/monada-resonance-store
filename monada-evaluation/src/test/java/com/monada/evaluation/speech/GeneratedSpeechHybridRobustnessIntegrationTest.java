package com.monada.evaluation.speech;

import com.monada.speech.retrieval.SpeechSampleRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(Set.copyOf(SpeechHybridRobustnessRunner.REQUIRED_CONFLICT_COVERAGE), report.queryResults().stream()
                .map(result -> result.stressCase().expectedConflictCategory()).collect(Collectors.toSet()));
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

    @Test
    void policyStudyKeepsControlsAndTheReferenceArmIdenticalToTheFixedReport() throws IOException {
        SpeechHybridRobustnessReport fixed = run(tempDir.resolve("fixed"));
        SpeechMissingModalityPolicyReport study = runPolicyStudy(tempDir.resolve("study"));

        for (SpeechHybridRobustnessQueryResult expected : fixed.queryResults()) {
            String queryId = expected.stressCase().query().queryId();
            SpeechMissingModalityPolicyQueryResult reference = study.result(
                    "development", SpeechMissingModalityPolicy.BOTH_REQUIRED).query(queryId);
            assertEquals(expected.hybrid().result().topResults(), reference.result().topResults(), queryId);
            assertEquals(expected.vsStrongerControl(), reference.vsStrongerControl(), queryId);
            for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
                SpeechHybridQueryContext context = study.result("development", policy).query(queryId).preparedCase().context();
                assertEquals(expected.context().transcriptControl(), context.transcriptControl(), queryId);
                assertEquals(expected.context().acousticControl(), context.acousticControl(), queryId);
            }
        }
    }

    @Test
    void holdoutCoversMissingOneOrBothModalitiesAndPartialDistractors() throws IOException {
        SpeechMissingModalityPolicyReport study = runPolicyStudy(tempDir.resolve("coverage"));
        SpeechMissingModalityPolicySplitResult holdout = study.result("holdout", SpeechMissingModalityPolicy.BOTH_REQUIRED);

        assertEquals(Set.of(SpeechHybridConflictCategory.TRANSCRIPT_MISSING, SpeechHybridConflictCategory.ACOUSTIC_MISSING,
                        SpeechHybridConflictCategory.BOTH_MISSING, SpeechHybridConflictCategory.AGREEMENT_RELEVANT,
                        SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_ACOUSTIC_CONFLICT,
                        SpeechHybridConflictCategory.ACOUSTIC_CORRECT_TRANSCRIPT_CONFLICT,
                        SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_PARTIAL_DISTRACTOR,
                        SpeechHybridConflictCategory.ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR),
                holdout.queries().stream().map(query -> query.preparedCase().stressCase().expectedConflictCategory())
                        .collect(Collectors.toSet()));
        Set<String> developmentIds = study.splits().getFirst().prepared().cases().getFirst().context().candidates().stream()
                .map(SpeechHybridCandidate::sampleId).collect(Collectors.toSet());
        assertTrue(study.splits().getLast().prepared().cases().getFirst().context().candidates().stream()
                .map(SpeechHybridCandidate::sampleId).noneMatch(developmentIds::contains));
        for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
            SpeechMissingModalityPolicyQueryResult bothMissing = study.result("holdout", policy).query("holdout_both_missing_same");
            SpeechMissingModalityComponentScore relevant = bothMissing.ranking().componentsBySampleId().get("h_c_gate_target_1540");
            assertEquals(SpeechHybridCandidateAvailability.BOTH_MISSING, relevant.availability(), policy.name());
            assertNull(relevant.policyScore(), policy.name());
            assertFalse(bothMissing.result().metrics().hitAtK(), policy.name());
        }
    }

    @Test
    void missingModalityPoliciesRecoverDevelopmentCasesButRegressHoldoutPartialDistractors() throws IOException {
        SpeechMissingModalityPolicyReport study = runPolicyStudy(tempDir.resolve("decision"));

        for (SpeechMissingModalityPolicy policy : List.of(
                SpeechMissingModalityPolicy.AVAILABLE_MODALITY_ONLY, SpeechMissingModalityPolicy.ABSTAIN_ON_PARTIAL)) {
            SpeechMissingModalityPolicySplitResult development = study.result("development", policy);
            SpeechMissingModalityPolicySplitResult holdout = study.result("holdout", policy);
            assertEquals(0, development.regressionsVsStronger(), policy.name());
            assertEquals(2, development.recoveriesVsBothRequired(), policy.name());
            assertEquals(SpeechHybridComparison.WIN, development.query("robust_transcript_missing_same").vsBothRequired());
            assertEquals(SpeechHybridComparison.WIN, development.query("robust_acoustic_missing_cross").vsBothRequired());
            assertEquals(2, holdout.conflictRegressions(), policy.name());
            assertEquals(SpeechHybridComparison.REGRESS,
                    holdout.query("holdout_acoustic_partial_distractor_cross").vsStrongerControl(), policy.name());
            assertEquals(SpeechHybridComparison.REGRESS,
                    holdout.query("holdout_transcript_partial_distractor_cross").vsStrongerControl(), policy.name());
            assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, study.verdict(policy).decision(), policy.name());
        }
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS,
                study.verdict(SpeechMissingModalityPolicy.BOTH_REQUIRED).decision());
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, study.decision());
        assertTrue(study.render().contains("Decision: RISK_REMAINS"));
        assertTrue(study.render().contains("Promotion: not permitted"));
    }

    @Test
    void policyStudyReportIsDeterministicAcrossFreshGeneratedStores() throws IOException {
        assertEquals(runPolicyStudy(tempDir.resolve("first-study")).render(),
                runPolicyStudy(tempDir.resolve("second-study")).render());
    }

    @Test
    void partialDistractorCasesRequireTheMaskedCandidateToBeTheWrongTopResult() throws IOException {
        Path root = tempDir.resolve("distractor-guard");
        GeneratedSpeechHybridRobustnessFixture.Fixture fixture = GeneratedSpeechHybridRobustnessFixture.createHoldout(root);
        List<SpeechHybridRobustnessCase> cases = new ArrayList<>(fixture.cases());
        int index = cases.stream().map(SpeechHybridRobustnessCase::expectedConflictCategory).toList()
                .indexOf(SpeechHybridConflictCategory.ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR);
        SpeechHybridRobustnessCase original = cases.get(index);
        cases.set(index, new SpeechHybridRobustnessCase(original.query(), original.querySpeakerId(),
                original.expectedConflictCategory(), Set.of(), Set.of("h_d_certificate_target_1650")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> prepare(
                new SpeechHybridRobustnessRunner(), new GeneratedSpeechHybridRobustnessFixture.Fixture(
                        fixture.sampleStore(), fixture.featureStore(), fixture.encoder(), cases), root));

        assertTrue(failure.getMessage().contains("transcript top result must be the partial distractor"));
    }

    @Test
    void policyStudyRejectsReusedOrOverlappingSplits() throws IOException {
        SpeechHybridRobustnessRunner runner = new SpeechHybridRobustnessRunner();
        Path root = tempDir.resolve("reused");
        SpeechHybridPreparedCases development = prepare(runner,
                GeneratedSpeechHybridRobustnessFixture.create(root.resolve("development")), root.resolve("development"));
        SpeechHybridPreparedCases copy = prepare(runner,
                GeneratedSpeechHybridRobustnessFixture.create(root.resolve("copy")), root.resolve("copy"));
        SpeechMissingModalityPolicyRunner policyRunner = new SpeechMissingModalityPolicyRunner();
        SpeechMissingModalityStudySplit developmentSplit = new SpeechMissingModalityStudySplit(
                SpeechMissingModalityPolicyRunner.DEVELOPMENT_SPLIT, GeneratedSpeechHybridRobustnessFixture.LABEL, development);

        assertThrows(IllegalArgumentException.class, () -> policyRunner.run(SpeechModalityEvidence.GENERATED_CI,
                List.of(developmentSplit, developmentSplit)));
        assertThrows(IllegalArgumentException.class, () -> policyRunner.run(SpeechModalityEvidence.GENERATED_CI,
                List.of(developmentSplit, new SpeechMissingModalityStudySplit(SpeechMissingModalityPolicyRunner.HOLDOUT_SPLIT,
                        GeneratedSpeechHybridRobustnessFixture.LABEL, copy))));
        IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class, () -> policyRunner.run(
                SpeechModalityEvidence.GENERATED_CI, List.of(developmentSplit, new SpeechMissingModalityStudySplit(
                        SpeechMissingModalityPolicyRunner.HOLDOUT_SPLIT, "relabelled-development", copy))));
        assertTrue(overlap.getMessage().contains("holdout split shares corpus samples with development"));
    }

    private SpeechMissingModalityPolicyReport runPolicyStudy(Path root) throws IOException {
        SpeechHybridRobustnessRunner runner = new SpeechHybridRobustnessRunner();
        return new SpeechMissingModalityPolicyRunner().run(SpeechModalityEvidence.GENERATED_CI, List.of(
                new SpeechMissingModalityStudySplit(SpeechMissingModalityPolicyRunner.DEVELOPMENT_SPLIT, GeneratedSpeechHybridRobustnessFixture.LABEL,
                        prepare(runner, GeneratedSpeechHybridRobustnessFixture.create(root.resolve("development")),
                                root.resolve("development"))),
                new SpeechMissingModalityStudySplit(SpeechMissingModalityPolicyRunner.HOLDOUT_SPLIT, GeneratedSpeechHybridRobustnessFixture.HOLDOUT_LABEL,
                        prepare(runner, GeneratedSpeechHybridRobustnessFixture.createHoldout(root.resolve("holdout")),
                                root.resolve("holdout")))));
    }

    private SpeechHybridPreparedCases prepare(
            SpeechHybridRobustnessRunner runner,
            GeneratedSpeechHybridRobustnessFixture.Fixture fixture,
            Path root
    ) throws IOException {
        return runner.prepare(fixture.cases(), fixture.k(), new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(), fixture.featureStore(), root.resolve("transcript-memory"));
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
