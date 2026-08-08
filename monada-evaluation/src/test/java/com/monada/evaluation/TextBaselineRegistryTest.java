package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextBaselineRegistryTest {

    @Test
    void loadsDefaultVersionedBaseline() {
        var baseline = TextBaselineRegistry.loadDefault()
                .require(TextEvaluationCatalog.DEFAULT_DATABASES);

        assertEquals("default-databases", baseline.datasetName());
        assertEquals("1", baseline.datasetVersion());
        assertEquals("PRODUCTION_DEFAULTS", baseline.profileName());
        assertEquals(1e-15, baseline.tolerance());
        assertEquals(4, baseline.metrics().size());
        assertEquals(TextBaselinePolicy.EXACT,
                baseline.metrics().get("averageHit@1").policy());
    }

    @Test
    void parsesExactAndMinimumPolicies() {
        var baseline = parse("""
                dataset=example
                datasetVersion=2
                profile=PROFILE
                tolerance=1e-9
                metric.averageHit@1=EXACT:1.0
                metric.meanReciprocalRank=MINIMUM:0.75
                """);

        assertEquals(TextBaselinePolicy.EXACT,
                baseline.metrics().get("averageHit@1").policy());
        assertEquals(TextBaselinePolicy.MINIMUM,
                baseline.metrics().get("meanReciprocalRank").policy());
    }

    @Test
    void rejectsMissingMetadataAndMalformedMetrics() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                dataset=example
                profile=PROFILE
                tolerance=1e-9
                metric.averageHit@1=EXACT:1.0
                """));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot(
                "metric.averageHit@1=UNKNOWN:1.0")));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot(
                "metric.averageHit@1=EXACT")));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot(
                "metric.unsupported=EXACT:1.0")));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot(
                "metric.averageHit@999999999999999999999=EXACT:1.0")));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot(
                "metric.averageHit@1=EXACT:NaN")));
        assertThrows(IllegalArgumentException.class, () -> parse(snapshot(
                "unexpected=value\nmetric.averageHit@1=EXACT:1.0")));
        assertThrows(IllegalArgumentException.class, () -> parse("""
                dataset=example
                datasetVersion=1
                profile=PROFILE
                tolerance=Infinity
                metric.averageHit@1=EXACT:1.0
                """));
    }

    @Test
    void rejectsEmptyMissingOrUnsafeIndexEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> TextBaselineRegistry.load(new StringReader("# none\n"), name -> null));
        assertThrows(IllegalArgumentException.class,
                () -> TextBaselineRegistry.load(new StringReader("missing.properties\n"), name -> null));
        assertThrows(IllegalArgumentException.class,
                () -> TextBaselineRegistry.load(new StringReader("../baseline.properties\n"), name -> null));
        assertThrows(IllegalArgumentException.class,
                () -> TextBaselineRegistry.load(
                        new StringReader("same.properties\nsame.properties\n"),
                        name -> stream(snapshot("metric.averageHit@1=EXACT:1.0"))));
    }

    @Test
    void rejectsDuplicateBaselineIdentitiesAcrossResources() {
        var content = snapshot("metric.averageHit@1=EXACT:1.0");
        var resources = Map.of("a.properties", content, "b.properties", content);

        var exception = assertThrows(IllegalArgumentException.class,
                () -> TextBaselineRegistry.load(
                        new StringReader("a.properties\nb.properties\n"),
                        name -> stream(resources.get(name))));

        assertTrue(exception.getMessage().contains("duplicate baseline identity"), exception.getMessage());
    }

    @Test
    void registryRejectsExploratoryMetadataAndUnknownProtectedIdentity() {
        var registry = TextBaselineRegistry.loadDefault();

        assertThrows(IllegalArgumentException.class,
                () -> registry.require(TextEvaluationCatalog.EXPANDED_TECHNOLOGY));
        assertThrows(IllegalArgumentException.class, () -> registry.require(
                new TextEvaluationMetadata(
                        "unknown", "1", "PROFILE", TextEvaluationMode.PROTECTED)));
    }

    private TextEvaluationBaseline parse(String content) {
        return TextBaselineRegistry.parse("test.properties", new StringReader(content));
    }

    private String snapshot(String extraProperties) {
        return """
                dataset=example
                datasetVersion=1
                profile=PROFILE
                tolerance=1e-15
                """ + extraProperties + "\n";
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
