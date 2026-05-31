package com.monada.encoder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalEnrichmentPipelineTest {

    @Test
    void defaultResourcesNormalizeDeterministically() {
        var normalizer = new LexicalEnrichmentPipeline();

        var first = normalizer.normalize("separate read and write paths for optimization");
        var second = normalizer.normalize("separate read and write paths for optimization");

        assertEquals(first.original(), second.original());
        assertEquals(first.normalized(), second.normalized());
        assertEquals(first.expansions(), second.expansions());
        assertEquals("separate read write path optimization", first.normalized());
        assertEquals("separate read write path optimization cqrs command query responsibility segregation",
                first.enrichedText());
    }

    @Test
    void normalizesGenericSearchableText() {
        TextNormalizer normalizer = new LexicalEnrichmentPipeline();

        var normalized = normalizer.normalize("The indexes and queries for temporary lookups");

        assertEquals("index query temporary lookup", normalized.normalized());
        assertTrue(normalized.expansions().contains("cache"));
        assertTrue(normalized.expansions().contains("redis"));
    }

    @Test
    void pluralRulesComeFromDefaultResource() {
        var normalizer = new LexicalEnrichmentPipeline();

        var normalized = normalizer.normalize("indexes indices queries embeddings models lookups transactions paths");

        assertEquals("index index query embedding model lookup transaction path", normalized.normalized());
    }

    @Test
    void controlledStopWordsDoNotRemoveTechnicalTerms() {
        var normalizer = new LexicalEnrichmentPipeline();

        var normalized = normalizer.normalize("separate read and write paths for optimization");

        assertFalse(normalized.normalized().contains(" and "));
        assertFalse(normalized.normalized().contains(" for "));
        assertTrue(normalized.normalized().contains("read"));
        assertTrue(normalized.normalized().contains("write"));
        assertTrue(normalized.normalized().contains("path"));
    }

    @Test
    void synonymRulesComeFromDefaultResource() {
        var normalizer = new LexicalEnrichmentPipeline();

        var normalized = normalizer.normalize("second pass model for improving search results");

        assertEquals(List.of("re ranking", "reranking"), normalized.expansions());
        assertTrue(normalized.enrichedText().contains("re ranking"));
    }

    @Test
    void languageSpecificResourcesCanBeSelected() {
        var normalizer = new LexicalEnrichmentPipeline("en");

        var normalized = normalizer.normalize("frequency closeness for memory recall");

        assertTrue(normalized.expansions().contains("resonance recall"));
        assertTrue(normalized.expansions().contains("resonance similarity"));
    }

    @Test
    void missingLanguageResourceFailsClearly() {
        var ex = assertThrows(IllegalStateException.class, () -> new LexicalEnrichmentPipeline("missing-language"));

        assertTrue(ex.getMessage().contains("Missing lexical resource"));
    }

    @Test
    void configurationFingerprintIsStableForSameResources() {
        var first = new LexicalEnrichmentPipeline();
        var second = new LexicalEnrichmentPipeline();

        assertEquals(first.configurationFingerprint(), second.configurationFingerprint());
        assertTrue(first.configurationFingerprint().startsWith("LexicalEnrichmentPipeline#"));
    }

    @Test
    void configurationFingerprintDiffersForDifferentResources() {
        var defaultPipeline = new LexicalEnrichmentPipeline();
        var customPipeline = new LexicalEnrichmentPipeline(
                Set.of("customstop"),
                Map.of("databases", "database"),
                Map.of("db", List.of("database")));

        assertNotEquals(defaultPipeline.configurationFingerprint(),
                customPipeline.configurationFingerprint());
    }

    @Test
    void configurationFingerprintIsIndependentOfResourceInsertionOrder() {
        var a = new LexicalEnrichmentPipeline(
                Set.of("alpha", "beta"),
                Map.of("cats", "cat", "dogs", "dog"),
                Map.of("db", List.of("database"), "k8s", List.of("kubernetes")));
        var b = new LexicalEnrichmentPipeline(
                Set.of("beta", "alpha"),
                Map.of("dogs", "dog", "cats", "cat"),
                Map.of("k8s", List.of("kubernetes"), "db", List.of("database")));

        assertEquals(a.configurationFingerprint(), b.configurationFingerprint());
    }
}
