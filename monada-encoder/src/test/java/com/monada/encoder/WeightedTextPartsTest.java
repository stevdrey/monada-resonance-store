package com.monada.encoder;

import com.monada.core.KnowledgeAtom;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeightedTextPartsTest {

    @Test
    void normalizedPartsFlattenToTheExistingWeightedRepresentation() {
        var normalized = new NormalizedText(
                "Original",
                "cache cache",
                List.of("redis store"));
        var options = LexicalExpansionOptions.DEFAULT;

        var parts = normalized.toWeightedTextParts(options);

        assertEquals(List.of(
                new WeightedToken("cache", 1.0),
                new WeightedToken("cache", 1.0)), parts.originalTokens());
        assertEquals(List.of(
                new WeightedToken("redis", 0.25),
                new WeightedToken("store", 0.25)), parts.expansionTokens());
        assertEquals(normalized.toWeightedText(options), parts.toWeightedText());
    }

    @Test
    void atomPartsPreserveOriginalExpansionAliasAndAliasExpansionWeights() {
        var normalizer = new LexicalEnrichmentPipeline(
                Set.of(),
                Map.of(),
                Map.of("cache", List.of("redis")));
        var atom = KnowledgeAtom.text("cache cache", List.of("cache"));

        var parts = WeightedAtomEncoder.toWeightedTextParts(
                atom, normalizer, LexicalExpansionOptions.DEFAULT);

        assertEquals(List.of(
                new WeightedToken("cache", 1.0),
                new WeightedToken("cache", 1.0)), parts.originalTokens());
        assertEquals(List.of(new WeightedToken("redis", 0.25)), parts.expansionTokens());
        assertEquals(List.of(new WeightedToken("cache", 0.25)), parts.aliasTokens());
        assertEquals(List.of(new WeightedToken("redis", 0.0625)), parts.aliasExpansionTokens());
        assertEquals(List.of(
                new WeightedToken("cache", 1.0),
                new WeightedToken("cache", 1.0),
                new WeightedToken("redis", 0.25),
                new WeightedToken("cache", 0.25),
                new WeightedToken("redis", 0.0625)),
                parts.toWeightedText().tokens());
        assertEquals(
                WeightedAtomEncoder.toWeightedText(
                        atom, normalizer, LexicalExpansionOptions.DEFAULT),
                parts.toWeightedText());
    }
}
