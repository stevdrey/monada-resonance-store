package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEncodingDiagnosticIntegrationTest {

    @Test
    void diagnosticsDoNotChangeRankingOrMetrics(@TempDir Path tempDir) {
        var dataset = dataset();
        var options = new MonadaMemoryOptions(
                new LexicalEnrichmentPipeline(), false, LexicalExpansionOptions.DEFAULT);

        var disabled = new EvaluationRunner(
                List.of(1), TextEncodingDiagnosticOptions.disabled())
                .run(dataset, tempDir.resolve("disabled"), options);
        var enabled = new EvaluationRunner(
                List.of(1), TextEncodingDiagnosticOptions.enabledDefaults())
                .run(dataset, tempDir.resolve("enabled"), options);
        var enabledAgain = new EvaluationRunner(
                List.of(1), TextEncodingDiagnosticOptions.enabledDefaults())
                .run(dataset, tempDir.resolve("enabled-again"), options);

        assertQualityEquals(disabled, enabled);
        assertEquals(enabled.render(), enabledAgain.render());
        assertFalse(disabled.render().contains("Encoding Contributions"));
        assertTrue(enabled.render().contains("Encoding Contributions"));
        assertTrue(enabled.render().contains("ALIAS_EXPANSION"));
        assertTrue(enabled.render().contains("MISSED_EXPECTED"));
    }

    @Test
    void diagnosticsDoNotChangeFeedbackAwareProfileResults(@TempDir Path tempDir) {
        var profiles = List.of(EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK);
        var disabled = new EvaluationProfileRunner(
                new EvaluationRunner(TextEncodingDiagnosticOptions.disabled()),
                new EvaluationComparator())
                .run(dataset(), profiles, tempDir.resolve("disabled"));
        var enabled = new EvaluationProfileRunner(
                new EvaluationRunner(TextEncodingDiagnosticOptions.enabledDefaults()),
                new EvaluationComparator())
                .run(dataset(), profiles, tempDir.resolve("enabled"));

        assertQualityEquals(
                disabled.reportByProfile().get(profiles.get(0)),
                enabled.reportByProfile().get(profiles.get(0)));
        assertFalse(disabled.render().contains("Encoding Contributions"));
        assertTrue(enabled.render().contains("Encoding Contributions"));
    }

    @Test
    void parsesEnvironmentDefaultsAndExplicitBounds() {
        assertEquals(
                TextEncodingDiagnosticOptions.disabled(),
                TextEncodingDiagnosticEnvironment.parse(Map.of()));
        assertEquals(
                new TextEncodingDiagnosticOptions(true, 2, 4, 7),
                TextEncodingDiagnosticEnvironment.parse(Map.of(
                        TextEncodingDiagnosticEnvironment.ENABLED, "true",
                        TextEncodingDiagnosticEnvironment.MAX_TOP_RESULTS, "2",
                        TextEncodingDiagnosticEnvironment.MAX_MISSED_EXPECTED, "4",
                        TextEncodingDiagnosticEnvironment.MAX_TERMS, "7")));
        assertThrows(IllegalArgumentException.class, () ->
                TextEncodingDiagnosticEnvironment.parse(Map.of(
                        TextEncodingDiagnosticEnvironment.ENABLED, "yes")));
        assertThrows(IllegalArgumentException.class, () ->
                TextEncodingDiagnosticEnvironment.parse(Map.of(
                        TextEncodingDiagnosticEnvironment.ENABLED, "true",
                        TextEncodingDiagnosticEnvironment.MAX_TERMS, "0")));
    }

    private EvaluationDataset dataset() {
        return new EvaluationDataset(
                List.of(
                        new DatasetAtom("cache", "Temporary cache", List.of("temporary lookup")),
                        new DatasetAtom("database", "Relational database", List.of("sql store")),
                        new DatasetAtom("queue", "Message queue", List.of("event buffer"))),
                List.of(
                        new EvaluationQuery("temporary lookup", Set.of("database")),
                        new EvaluationQuery("sql store", Set.of("database"))));
    }

    private void assertQualityEquals(EvaluationReport expected, EvaluationReport actual) {
        assertEquals(expected.averagePrecisionByK(), actual.averagePrecisionByK());
        assertEquals(expected.averageRecallByK(), actual.averageRecallByK());
        assertEquals(expected.averageHitByK(), actual.averageHitByK());
        assertEquals(expected.meanReciprocalRank(), actual.meanReciprocalRank());
        assertEquals(expected.queryResults().size(), actual.queryResults().size());
        for (int i = 0; i < expected.queryResults().size(); i++) {
            var left = expected.queryResults().get(i);
            var right = actual.queryResults().get(i);
            assertEquals(left.queryText(), right.queryText());
            assertEquals(left.expectedLabels(), right.expectedLabels());
            assertEquals(left.returnedLabels(), right.returnedLabels());
            assertEquals(left.precisionByK(), right.precisionByK());
            assertEquals(left.recallByK(), right.recallByK());
            assertEquals(left.hitByK(), right.hitByK());
            assertEquals(left.reciprocalRank(), right.reciprocalRank());
            assertEquals(left.queryKeyDiagnostic(), right.queryKeyDiagnostic());
        }
    }
}
