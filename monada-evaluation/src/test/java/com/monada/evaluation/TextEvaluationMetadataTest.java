package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEvaluationMetadataTest {

    @Test
    void protectedReportIsClearlyLabeled() {
        var rendered = new VersionedTextEvaluationReport(
                TextEvaluationCatalog.DEFAULT_DATABASES, emptyReport()).render();

        assertTrue(rendered.startsWith("Text Evaluation Context"), rendered);
        assertTrue(rendered.contains("Mode: PROTECTED"), rendered);
        assertTrue(rendered.contains("versioned baseline enforced in CI"), rendered);
        assertTrue(rendered.contains("Dataset: default-databases"), rendered);
        assertTrue(rendered.contains("Dataset version: 1"), rendered);
        assertTrue(rendered.contains("Profile: PRODUCTION_DEFAULTS"), rendered);
        assertTrue(rendered.contains("Monada Resonance Store Evaluation Report"), rendered);
    }

    @Test
    void exploratoryDatasetsAndSpecializedReportsAreClearlyLabeled() {
        assertExploratory(TextEvaluationCatalog.EXPANDED_TECHNOLOGY);
        assertExploratory(TextEvaluationCatalog.PROJECT_MEMORY);
        assertExploratory(TextEvaluationCatalog.EXPANDED_PROFILE_COMPARISON);
        assertExploratory(TextEvaluationCatalog.EXPANDED_LATENCY);
        assertExploratory(TextEvaluationCatalog.PROJECT_MEMORY_FEEDBACK_KEY_VALIDATION);
        assertEquals("2", TextEvaluationCatalog.PROJECT_MEMORY.datasetVersion());
        assertEquals("2", TextEvaluationCatalog.PROJECT_MEMORY_FEEDBACK_KEY_VALIDATION.datasetVersion());
    }

    @Test
    void rejectsBlankMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new TextEvaluationMetadata(" ", "1", "PROFILE", TextEvaluationMode.PROTECTED));
        assertThrows(IllegalArgumentException.class,
                () -> new TextEvaluationMetadata("dataset", " ", "PROFILE", TextEvaluationMode.PROTECTED));
        assertThrows(IllegalArgumentException.class,
                () -> new TextEvaluationMetadata("dataset", "1", " ", TextEvaluationMode.PROTECTED));
    }

    private void assertExploratory(TextEvaluationMetadata metadata) {
        assertEquals(TextEvaluationMode.EXPLORATORY, metadata.mode());
        var rendered = metadata.render("report body\n");
        assertTrue(rendered.contains("Mode: EXPLORATORY"), rendered);
        assertTrue(rendered.contains("NOT enforced in CI"), rendered);
        assertTrue(rendered.endsWith("report body\n"), rendered);
    }

    private EvaluationReport emptyReport() {
        var metrics = Map.of(1, 1.0);
        return new EvaluationReport(List.of(), metrics, metrics, metrics, 1.0);
    }
}
