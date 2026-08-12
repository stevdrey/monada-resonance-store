package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizedQueryKeyStressFixtureParserTest {

    private static final Map<NormalizationStressCategory, Integer> EXPECTED_COUNTS = Map.ofEntries(
            Map.entry(NormalizationStressCategory.CASING, 1),
            Map.entry(NormalizationStressCategory.PUNCTUATION_SEPARATOR, 2),
            Map.entry(NormalizationStressCategory.WHITESPACE, 1),
            Map.entry(NormalizationStressCategory.PLURAL_MAPPING, 2),
            Map.entry(NormalizationStressCategory.HARMLESS_STOP_WORD, 2),
            Map.entry(NormalizationStressCategory.STOP_WORD_COORDINATION, 2),
            Map.entry(NormalizationStressCategory.STOP_WORD_RELATION, 4),
            Map.entry(NormalizationStressCategory.TOKEN_ORDER, 2),
            Map.entry(NormalizationStressCategory.NUMERIC_VERSION_IDENTIFIER, 2),
            Map.entry(NormalizationStressCategory.NEGATION_EXCLUSION, 2),
            Map.entry(NormalizationStressCategory.TECHNICAL_MODULE_TERM, 2),
            Map.entry(NormalizationStressCategory.BLANK_FALLBACK_BOUNDARY, 2));

    @Test
    void versionedFixtureCoversTheRequiredTransformationSurface() throws Exception {
        var cases = NormalizedQueryKeyStressMain.loadFixture();

        assertEquals(24, cases.size());
        var counts = new EnumMap<NormalizationStressCategory, Integer>(NormalizationStressCategory.class);
        cases.forEach(stressCase -> counts.merge(stressCase.category(), 1, Integer::sum));
        assertEquals(EXPECTED_COUNTS, counts);
        assertEquals(8, cases.stream()
                .filter(stressCase -> stressCase.semanticRelationship()
                        == NormalizationSemanticRelationship.EQUIVALENT)
                .count());
        assertEquals(16, cases.stream()
                .filter(stressCase -> stressCase.semanticRelationship()
                        == NormalizationSemanticRelationship.DISTINCT)
                .count());
        assertEquals(cases.size(), cases.stream().map(NormalizedQueryKeyStressCase::id).distinct().count());
        assertEquals(cases.size(), cases.stream().map(NormalizedQueryKeyStressCase::createdAt).distinct().count());
        assertTrue(cases.stream()
                .filter(stressCase -> stressCase.semanticRelationship()
                        == NormalizationSemanticRelationship.EQUIVALENT)
                .allMatch(stressCase -> stressCase.expectedKeyRelation() == ExpectedQueryKeyRelation.MATCH));
        assertTrue(cases.stream()
                .filter(stressCase -> stressCase.semanticRelationship()
                        == NormalizationSemanticRelationship.DISTINCT)
                .allMatch(stressCase -> !stressCase.relevantTargetsB()
                        .contains(stressCase.feedbackTargetLabel())));

        NormalizedQueryKeyStressCase whitespace = cases.stream()
                .filter(stressCase -> stressCase.category() == NormalizationStressCategory.WHITESPACE)
                .findFirst()
                .orElseThrow();
        assertTrue(whitespace.queryA().contains("\t"));
        assertTrue(whitespace.queryA().contains("\n"));
    }

    @Test
    void rejectsMalformedFieldsEscapesAndTargetSets() {
        String malformedCount = "id\tCASING\tEQUIVALENT\tonly-four";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser(malformedCount).parse()).getMessage().contains("11 tab-separated fields"));

        String invalidEscape = row("bad\\q", "query b", "target", "target", "target");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser(invalidEscape).parse()).getMessage().contains("unsupported query escape"));

        String duplicateTarget = row("query a", "query b", "target,target", "target,target", "target");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser(duplicateTarget).parse()).getMessage().contains("duplicate target label"));
    }

    @Test
    void caseModelRejectsInvalidSemanticRelevance() {
        String invalid = row("query a", "query b", "target-a", "target-a", "target-a")
                .replace("\tEQUIVALENT\t", "\tDISTINCT\t");
        var parsed = assertThrows(IllegalArgumentException.class, () -> parser(invalid).parse());
        assertTrue(parsed.getMessage().contains("distinct query B"));
    }

    private NormalizedQueryKeyStressFixtureParser parser(String content) {
        return new NormalizedQueryKeyStressFixtureParser(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "test.tsv");
    }

    private String row(
            String queryA,
            String queryB,
            String targetsA,
            String targetsB,
            String feedbackTarget) {
        return String.join("\t",
                "id",
                "CASING",
                "EQUIVALENT",
                queryA,
                queryB,
                targetsA,
                targetsB,
                feedbackTarget,
                "MATCH",
                "10.0",
                "2026-04-01T00:00:00Z");
    }
}
