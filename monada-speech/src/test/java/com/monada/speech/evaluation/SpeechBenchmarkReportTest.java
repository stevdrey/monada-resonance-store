package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.retrieval.SpeechCandidateDiagnostic;
import com.monada.speech.retrieval.SpeechCandidateStatus;
import com.monada.speech.retrieval.SpeechMetadataFilter;
import com.monada.speech.retrieval.SpeechRetrievalDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechBenchmarkReportTest {

    private static SpeechEvaluationReport sampleReport() {
        return new SpeechEvaluationReport(
                2, 0.5, 1.0, 1.0, 1.0,
                List.of(),
                Map.of(),
                Map.of());
    }

    @Test
    void protectedRenderLabelsModeAndPolicy() {
        var report = new SpeechBenchmarkReport(
                SpeechBenchmarkMode.PROTECTED, "fixture", 3, 2, sampleReport());
        String rendered = report.render();
        assertTrue(rendered.contains("Mode: PROTECTED"), rendered);
        assertTrue(rendered.contains("enforced in CI"), rendered);
        assertFalse(rendered.contains("NOT enforced in CI"), rendered);
        assertTrue(rendered.contains("Label: fixture"), rendered);
        assertTrue(rendered.contains("Corpus size: 3"), rendered);
        assertTrue(rendered.contains("Query count: 2"), rendered);
        assertTrue(rendered.contains("k: 2"), rendered);
        assertTrue(rendered.contains("Precision@k: 0.5000"), rendered);
        assertTrue(rendered.contains("Recall@k: 1.0000"), rendered);
    }

    @Test
    void exploratoryRenderLabelsModeAndPolicy() {
        var report = new SpeechBenchmarkReport(
                SpeechBenchmarkMode.EXPLORATORY, "local-corpus", 10, 5, sampleReport());
        String rendered = report.render();
        assertTrue(rendered.contains("Mode: EXPLORATORY"), rendered);
        assertTrue(rendered.contains("NOT enforced in CI"), rendered);
    }

    @Test
    void renderIncludesGroupedMetricsWhenPresent() {
        var byCondition = Map.of(
                SpeechCondition.DYSARTHRIC, new SpeechEvaluationMetrics(0.5, 1.0, 1.0, 1.0, 1));
        var byTaskType = Map.of(
                SpeechTaskType.WORD, new SpeechEvaluationMetrics(0.5, 1.0, 1.0, 1.0, 1));
        var report = new SpeechBenchmarkReport(
                SpeechBenchmarkMode.PROTECTED, "fixture", 3, 2,
                new SpeechEvaluationReport(1, 0.5, 1.0, 1.0, 1.0, List.of(), byCondition, byTaskType));
        String rendered = report.render();
        assertTrue(rendered.contains("Metrics by condition"), rendered);
        assertTrue(rendered.contains("DYSARTHRIC"), rendered);
        assertTrue(rendered.contains("Metrics by task type"), rendered);
        assertTrue(rendered.contains("WORD"), rendered);
    }

    @Test
    void renderIsStableAcrossCalls() {
        var report = new SpeechBenchmarkReport(
                SpeechBenchmarkMode.PROTECTED, "fixture", 3, 2, sampleReport());
        assertEquals(report.render(), report.render());
    }

    @Test
    void renderIncludesOptInAcousticSummaryAndRelevantCandidatesOnly() {
        var retrievalDiagnostic = new SpeechRetrievalDiagnostic(
                2,
                0,
                0,
                1,
                1,
                0,
                0.9,
                0.9,
                0.9,
                0.9,
                0.0,
                List.of(
                        new SpeechCandidateDiagnostic(
                                "a_scored", SpeechCandidateStatus.SCORED, List.of(), 0.9, 1),
                        new SpeechCandidateDiagnostic(
                                "b_filtered",
                                SpeechCandidateStatus.METADATA_FILTERED,
                                List.of(SpeechMetadataFilter.CONDITION),
                                0.0,
                                0)));
        var acousticDiagnostic = new SpeechQueryAcousticDiagnostic(
                retrievalDiagnostic,
                List.of(
                        new SpeechRelevantCandidateDiagnostic(
                                "a_scored",
                                SpeechRelevantCandidateStatus.RETRIEVED_AT_K,
                                List.of(),
                                0.9,
                                1),
                        new SpeechRelevantCandidateDiagnostic(
                                "b_filtered",
                                SpeechRelevantCandidateStatus.METADATA_FILTERED,
                                List.of(SpeechMetadataFilter.CONDITION),
                                0.0,
                                0)));
        var queryResult = new SpeechQueryEvaluationResult(
                "q1",
                1,
                1,
                true,
                1.0,
                0.5,
                1.0,
                List.of("a_scored"),
                List.of("b_filtered"),
                "a_scored",
                0.9,
                acousticDiagnostic);
        var evaluationReport = new SpeechEvaluationReport(
                1, 1.0, 0.5, 1.0, 1.0, List.of(queryResult), Map.of(), Map.of());
        var report = new SpeechBenchmarkReport(
                SpeechBenchmarkMode.EXPLORATORY, "diagnostic", 2, 1, evaluationReport);

        String rendered = report.render();

        assertTrue(rendered.contains(
                "scan: vectors=2 orphan=0 incompatible=0 filtered=1 scored=1 ties=0"), rendered);
        assertTrue(rendered.contains(
                "scores: min=0.9000 mean=0.9000 max=0.9000 top=0.9000 top-gap=0.0000"), rendered);
        assertTrue(rendered.contains(
                "relevant a_scored: status=RETRIEVED_AT_K rank=1 score=0.9000"), rendered);
        assertTrue(rendered.contains(
                "relevant b_filtered: status=METADATA_FILTERED filters=[CONDITION]"), rendered);
        assertFalse(rendered.contains("candidate a_scored"), rendered);
        assertEquals(rendered, report.render());
    }

    @Test
    void rejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechBenchmarkReport(
                SpeechBenchmarkMode.PROTECTED, "  ", 3, 2, sampleReport()));
    }

    @Test
    void rejectsNegativeCorpusSize() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechBenchmarkReport(
                SpeechBenchmarkMode.PROTECTED, "fixture", -1, 2, sampleReport()));
    }

    @Test
    void rejectsNonPositiveK() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechBenchmarkReport(
                SpeechBenchmarkMode.PROTECTED, "fixture", 3, 0, sampleReport()));
    }

    @Test
    void rejectsNullMode() {
        assertThrows(NullPointerException.class, () -> new SpeechBenchmarkReport(
                null, "fixture", 3, 2, sampleReport()));
    }
}
