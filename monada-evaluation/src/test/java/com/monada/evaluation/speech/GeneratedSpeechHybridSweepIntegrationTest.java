package com.monada.evaluation.speech;

import com.monada.core.FrequencyVector;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.StoredSpeechFeatureVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSpeechHybridSweepIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedFixtureShowsAStablePromisingHybridRegion() throws IOException {
        Run run = run(tempDir.resolve("fixture"), GeneratedSpeechModalityFixture.K);
        SpeechHybridSweepReport report = run.report();

        assertEquals(SpeechModalityEvidence.GENERATED_CI, report.evidence());
        assertEquals(8, report.corpusSize());
        assertEquals(4, report.profiles().getFirst().queryResults().size());
        assertEquals(SpeechHybridDecision.HYBRID_ROBUSTNESS_STUDY_JUSTIFIED, report.decision());
        assertEquals(SpeechFusionWeights.defaultSweep(), report.profiles().stream()
                .map(SpeechHybridProfileResult::weights)
                .toList());
        for (SpeechHybridProfileResult profile : report.profiles().stream()
                .filter(profile -> profile.weights().isHybrid())
                .toList()) {
            assertTrue(profile.summary().promising());
            assertEquals(1.0, profile.summary().metrics().precisionAtK());
            assertEquals(1.0, profile.summary().metrics().recallAtK());
            assertEquals(1.0, profile.summary().metrics().hitRateAtK());
            assertEquals(1.0, profile.summary().metrics().mrr());
            assertEquals(1, profile.summary().disagreementOutcomeCounts()
                    .get(SpeechHybridDisagreementOutcome.RECOVERED_TRANSCRIPT_MISS));
            assertEquals(1, profile.summary().disagreementOutcomeCounts()
                    .get(SpeechHybridDisagreementOutcome.RECOVERED_ACOUSTIC_MISS));
        }
        assertEquals(SpeechHybridDisagreementOutcome.RECOVERED_BOTH_MISS,
                hybridProfile(report, 0.5, 0.5).queryResult("q_both_fail").disagreementOutcome());
        assertTrue(report.render().contains("Normalization: per-query, per-modality min-max"));
        assertTrue(report.render().contains("Evaluation only"));
    }

    @Test
    void controlProfilesExactlyReproduceTheCompletePairedControlRankings() throws IOException {
        Path root = tempDir.resolve("controls");
        GeneratedSpeechModalityFixture.Fixture fixture = GeneratedSpeechModalityFixture.create(root);
        int k = fixture.samples().size();
        SpeechHybridSweepReport hybrid = new SpeechHybridSweepRunner().run(
                SpeechModalityEvidence.GENERATED_CI,
                fixture.label(),
                fixture.queries(),
                k,
                new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(),
                fixture.featureStore(),
                root.resolve("hybrid-transcript-memory"));
        SpeechModalityComparisonReport controls = new SpeechModalityComparisonRunner().run(
                SpeechModalityEvidence.GENERATED_CI,
                fixture.label(),
                fixture.queries(),
                k,
                new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(),
                fixture.featureStore(),
                root.resolve("comparison-transcript-memory"));

        SpeechHybridProfileResult transcriptControl = hybridProfile(hybrid, 1.0, 0.0);
        SpeechHybridProfileResult acousticControl = hybridProfile(hybrid, 0.0, 1.0);
        for (SpeechModalityComparisonQueryResult control : controls.queryResults()) {
            assertEquals(control.transcript(), transcriptControl.queryResult(control.query().queryId()).result());
            assertEquals(control.acoustic(), acousticControl.queryResult(control.query().queryId()).result());
        }
    }

    @Test
    void acousticFeatureMissingIsReportedAndExcludedFromNonTrivialFusion() throws IOException {
        Path root = tempDir.resolve("missing-acoustic");
        GeneratedSpeechModalityFixture.Fixture fixture = GeneratedSpeechModalityFixture.create(root);
        SpeechFeatureStore withoutRelevantFeature = withoutFeature(fixture.featureStore(), "z_deploy_1760");
        SpeechHybridSweepReport report = new SpeechHybridSweepRunner().run(
                SpeechModalityEvidence.GENERATED_CI,
                fixture.label(),
                fixture.queries(),
                fixture.k(),
                new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(),
                withoutRelevantFeature,
                root.resolve("transcript-memory"));

        SpeechHybridProfileQueryResult result = hybridProfile(report, 0.5, 0.5)
                .queryResult("q_acoustic_only");

        assertEquals(List.of("z_deploy_1760"), result.context().availabilityIds()
                .get(SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING));
        assertFalse(result.result().topResults().stream()
                .anyMatch(ranked -> ranked.sampleId().equals("z_deploy_1760")));
        assertFalse(result.result().metrics().hitAtK());
    }

    @Test
    void renderIsDeterministicAcrossFreshGeneratedStores() throws IOException {
        assertEquals(
                run(tempDir.resolve("first"), GeneratedSpeechModalityFixture.K).report().render(),
                run(tempDir.resolve("second"), GeneratedSpeechModalityFixture.K).report().render());
    }

    private Run run(Path root, int k) throws IOException {
        GeneratedSpeechModalityFixture.Fixture fixture = GeneratedSpeechModalityFixture.create(root);
        SpeechHybridSweepReport report = new SpeechHybridSweepRunner().run(
                SpeechModalityEvidence.GENERATED_CI,
                fixture.label(),
                fixture.queries(),
                k,
                new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(),
                fixture.featureStore(),
                root.resolve("transcript-memory"));
        return new Run(fixture, report);
    }

    private SpeechHybridProfileResult hybridProfile(
            SpeechHybridSweepReport report,
            double transcriptWeight,
            double acousticWeight
    ) {
        return report.profiles().stream()
                .filter(profile -> profile.weights().equals(new SpeechFusionWeights(transcriptWeight, acousticWeight)))
                .findFirst()
                .orElseThrow();
    }

    private SpeechFeatureStore withoutFeature(SpeechFeatureStore delegate, String excludedSampleId) {
        return new SpeechFeatureStore() {
            @Override
            public void save(String sampleId, FrequencyVector vector) throws IOException {
                delegate.save(sampleId, vector);
            }

            @Override
            public Optional<FrequencyVector> findBySampleId(String sampleId) throws IOException {
                if (sampleId.equals(excludedSampleId)) {
                    return Optional.empty();
                }
                return delegate.findBySampleId(sampleId);
            }

            @Override
            public List<StoredSpeechFeatureVector> findAll() throws IOException {
                return delegate.findAll().stream()
                        .filter(vector -> !vector.sampleId().equals(excludedSampleId))
                        .toList();
            }
        };
    }

    private record Run(
            GeneratedSpeechModalityFixture.Fixture fixture,
            SpeechHybridSweepReport report
    ) {
    }
}
