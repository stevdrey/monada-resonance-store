package com.monada.evaluation.speech;

import com.monada.api.MonadaMemory;
import com.monada.core.ResonanceResult;
import com.monada.speech.bridge.SpeechSampleAtomMapper;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.retrieval.SpeechRetrievalOptions;
import com.monada.speech.retrieval.SpeechRetrievalResult;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSpeechModalityComparisonIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedFixtureExposesAllFourPairedOutcomes() throws IOException {
        Run run = run(tempDir.resolve("fixture"));
        SpeechModalityComparisonReport report = run.report();

        assertEquals(SpeechModalityEvidence.GENERATED_CI, report.evidence());
        assertEquals(8, report.corpusSize());
        assertEquals(4, report.queryResults().size());
        assertEquals(1, report.k());
        assertEquals(SpeechModalityDecision.HYBRID_EXPERIMENT_JUSTIFIED, report.decision());
        assertEquals(1, report.aggregate().outcomeCount(SpeechModalityOutcome.BOTH_SUCCEED));
        assertEquals(1, report.aggregate().outcomeCount(SpeechModalityOutcome.TRANSCRIPT_ONLY));
        assertEquals(1, report.aggregate().outcomeCount(SpeechModalityOutcome.ACOUSTIC_ONLY));
        assertEquals(1, report.aggregate().outcomeCount(SpeechModalityOutcome.BOTH_FAIL));

        Map<String, SpeechModalityComparisonQueryResult> resultsById = resultsById(report);
        assertEquals(SpeechModalityOutcome.ACOUSTIC_ONLY, resultsById.get("q_acoustic_only").outcome());
        assertEquals("a_deploy_440", resultsById.get("q_acoustic_only").transcript().topResults().getFirst().sampleId());
        assertEquals("z_deploy_1760", resultsById.get("q_acoustic_only").acoustic().topResults().getFirst().sampleId());
        assertEquals(SpeechModalityOutcome.TRANSCRIPT_ONLY, resultsById.get("q_transcript_only").outcome());
        assertEquals(SpeechModalityOutcome.BOTH_SUCCEED, resultsById.get("q_both_succeed").outcome());
        assertEquals(SpeechModalityOutcome.BOTH_FAIL, resultsById.get("q_both_fail").outcome());
        assertTrue(report.render().contains("No hybrid score was computed"));
    }

    @Test
    void renderIsDeterministicAcrossFreshStores() throws IOException {
        assertEquals(
                run(tempDir.resolve("first")).report().render(),
                run(tempDir.resolve("second")).report().render());
    }

    @Test
    void reusingPopulatedTranscriptMemoryIsRejectedBeforeTheNextComparison() throws IOException {
        Path root = tempDir.resolve("reused-transcript-memory");
        Run first = run(root);
        Path transcriptMemoryPath = root.resolve("transcript-memory");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> run(first.fixture(), transcriptMemoryPath));

        assertTrue(failure.getMessage().contains("transcriptMemoryPath must be a nonexistent path or an empty directory"));
    }

    @Test
    void preexistingEmptyTranscriptMemoryDirectoryIsAccepted() throws IOException {
        Path root = tempDir.resolve("empty-transcript-memory");
        GeneratedSpeechModalityFixture.Fixture fixture = GeneratedSpeechModalityFixture.create(root);
        Path transcriptMemoryPath = root.resolve("transcript-memory");
        Files.createDirectories(transcriptMemoryPath);

        SpeechModalityComparisonReport report = run(fixture, transcriptMemoryPath).report();

        assertEquals(SpeechModalityDecision.HYBRID_EXPERIMENT_JUSTIFIED, report.decision());
    }

    @Test
    void transcriptMemoryPathMustNotBeAFile() throws IOException {
        Path root = tempDir.resolve("file-transcript-memory");
        GeneratedSpeechModalityFixture.Fixture fixture = GeneratedSpeechModalityFixture.create(root);
        Path transcriptMemoryPath = root.resolve("transcript-memory");
        Files.writeString(transcriptMemoryPath, "not a transcript memory directory");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> run(fixture, transcriptMemoryPath));

        assertTrue(failure.getMessage().contains("transcriptMemoryPath must be a nonexistent path or an empty directory"));
    }

    @Test
    void acousticArmMatchesDirectSpeechRetrieverRankingAndScore() throws IOException {
        Run run = run(tempDir.resolve("acoustic"));
        PairedSpeechQuery query = query(run.fixture(), "q_transcript_only");
        List<SpeechRetrievalResult> direct = new SpeechSampleRetriever(run.fixture().encoder()).search(
                query.queryAudio(),
                run.fixture().sampleStore(),
                run.fixture().featureStore(),
                new SpeechRetrievalOptions(run.fixture().samples().size(), null, null, null, null, null));

        SpeechModalityRankedResult actual = resultsById(run.report())
                .get(query.queryId())
                .acoustic()
                .topResults()
                .getFirst();
        SpeechRetrievalResult expected = direct.getFirst();
        assertEquals(expected.sample().id(), actual.sampleId());
        assertEquals(expected.rank(), actual.rank());
        assertEquals(expected.score(), actual.score(), 1e-12);
    }

    @Test
    void transcriptArmMatchesDirectMemoryWithStableDuplicateExpansion() throws IOException {
        Run run = run(tempDir.resolve("transcript"));
        PairedSpeechQuery query = query(run.fixture(), "q_acoustic_only");
        List<SpeechModalityRankedResult> direct = directTranscriptRanking(
                run.fixture().samples(),
                query,
                tempDir.resolve("direct-memory"));

        SpeechModalityQueryResult actual = resultsById(run.report()).get(query.queryId()).transcript();
        assertEquals(direct.getFirst(), actual.topResults().getFirst());
        assertEquals(2, actual.metrics().firstRelevantRank());
        assertEquals(0.5, actual.metrics().reciprocalRank(), 1e-12);
    }

    private Run run(Path root) throws IOException {
        return run(GeneratedSpeechModalityFixture.create(root), root.resolve("transcript-memory"));
    }

    private Run run(GeneratedSpeechModalityFixture.Fixture fixture, Path transcriptMemoryPath) throws IOException {
        SpeechModalityComparisonReport report = new SpeechModalityComparisonRunner().run(
                SpeechModalityEvidence.GENERATED_CI,
                fixture.label(),
                fixture.queries(),
                fixture.k(),
                new SpeechSampleRetriever(fixture.encoder()),
                fixture.sampleStore(),
                fixture.featureStore(),
                transcriptMemoryPath);
        return new Run(fixture, report);
    }

    private Map<String, SpeechModalityComparisonQueryResult> resultsById(SpeechModalityComparisonReport report) {
        Map<String, SpeechModalityComparisonQueryResult> results = new HashMap<>();
        for (SpeechModalityComparisonQueryResult result : report.queryResults()) {
            results.put(result.query().queryId(), result);
        }
        return results;
    }

    private PairedSpeechQuery query(GeneratedSpeechModalityFixture.Fixture fixture, String queryId) {
        return fixture.queries().stream()
                .filter(query -> query.queryId().equals(queryId))
                .findFirst()
                .orElseThrow();
    }

    private List<SpeechModalityRankedResult> directTranscriptRanking(
            List<SpeechSample> samples,
            PairedSpeechQuery query,
            Path memoryPath
    ) {
        MonadaMemory memory = MonadaMemory.open(memoryPath);
        Map<String, List<String>> sampleIdsByAtomId = new HashMap<>();
        for (SpeechSample sample : samples.stream().sorted(Comparator.comparing(SpeechSample::id)).toList()) {
            var transcriptAtom = SpeechSampleAtomMapper.toTranscriptAtom(sample);
            var storedAtom = memory.remember(transcriptAtom.content(), transcriptAtom.aliases());
            sampleIdsByAtomId.computeIfAbsent(storedAtom.id(), ignored -> new ArrayList<>()).add(sample.id());
        }
        List<SpeechModalityRankedResult> expanded = new ArrayList<>();
        List<ResonanceResult> atomResults = memory.resonate(query.transcript())
                .topK(sampleIdsByAtomId.size())
                .threshold(Double.NEGATIVE_INFINITY)
                .execute()
                .results();
        for (ResonanceResult atomResult : atomResults) {
            for (String sampleId : sampleIdsByAtomId.get(atomResult.atom().id()).stream().sorted().toList()) {
                expanded.add(new SpeechModalityRankedResult(sampleId, atomResult.score(), expanded.size() + 1));
            }
        }
        return expanded;
    }

    private record Run(
            GeneratedSpeechModalityFixture.Fixture fixture,
            SpeechModalityComparisonReport report
    ) {
    }
}
