package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEncodingDiagnosticGeneratorTest {

    @Test
    void identifiesAllContributionSourcesWeightsOccurrencesAndOverlaps() {
        var dataset = dataset();
        var query = dataset.queries().get(0);
        var options = memoryOptions();

        var diagnostic = TextEncodingDiagnosticGenerator.generate(
                dataset,
                query,
                List.of(
                        ranked("top", 1, 0.9),
                        ranked("other", 2, 0.5)),
                List.of(
                        ranked("top", 1, 0.9),
                        ranked("other", 2, 0.5),
                        ranked("expected-a", 3, 0.4),
                        ranked("expected-b", 4, 0.3)),
                options,
                TextEncodingDiagnosticOptions.enabledDefaults());

        assertEquals(List.of(
                contribution("cache", TextEncodingContributionSource.ORIGINAL, 1.0, 1),
                contribution("redis", TextEncodingContributionSource.EXPANSION, 0.25, 1)),
                diagnostic.queryRepresentation().terms());

        var top = diagnostic.candidates().get(0);
        assertEquals("top", top.label());
        assertEquals(TextEncodingCandidateSelection.TOP_RANKED, top.selection());
        assertEquals(1, top.rank().orElseThrow());
        assertEquals(0.9, top.score().orElseThrow());
        assertEquals(List.of("cache", "redis"), top.overlappingTerms());
        assertEquals(List.of(
                contribution("cache", TextEncodingContributionSource.ORIGINAL, 1.0, 2),
                contribution("redis", TextEncodingContributionSource.EXPANSION, 0.25, 1),
                contribution("cache", TextEncodingContributionSource.ALIAS, 0.25, 1),
                contribution("redis", TextEncodingContributionSource.ALIAS_EXPANSION, 0.0625, 1)),
                top.representation().terms());

        var withoutExpansion = diagnostic.candidates().get(1);
        assertTrue(withoutExpansion.representation().terms().stream()
                .noneMatch(term -> term.source() == TextEncodingContributionSource.EXPANSION
                        || term.source() == TextEncodingContributionSource.ALIAS_EXPANSION));

        var missed = diagnostic.candidates().get(2);
        assertEquals("expected-a", missed.label());
        assertEquals(TextEncodingCandidateSelection.MISSED_EXPECTED, missed.selection());
        assertEquals(3, missed.rank().orElseThrow());
        assertEquals(0, diagnostic.omittedTopResultCount());
        assertEquals(0, diagnostic.omittedMissedExpectedCount());
    }

    @Test
    void appliesCandidateAndTermBoundsWithDeterministicOmittedCounts() {
        var diagnostic = TextEncodingDiagnosticGenerator.generate(
                dataset(),
                dataset().queries().get(0),
                List.of(
                        ranked("top", 1, 0.9),
                        ranked("other", 2, 0.5)),
                List.of(
                        ranked("top", 1, 0.9),
                        ranked("other", 2, 0.5),
                        ranked("expected-a", 3, 0.4),
                        ranked("expected-b", 4, 0.3)),
                memoryOptions(),
                new TextEncodingDiagnosticOptions(true, 1, 1, 2));

        assertEquals(2, diagnostic.candidates().size());
        assertEquals(List.of("top", "expected-a"), diagnostic.candidates().stream()
                .map(TextEncodingCandidateDiagnostic::label).toList());
        assertEquals(1, diagnostic.omittedTopResultCount());
        assertEquals(1, diagnostic.omittedMissedExpectedCount());
        assertEquals(2, diagnostic.candidates().get(0).representation().terms().size());
        assertEquals(2, diagnostic.candidates().get(0).representation().omittedTermCount());
    }

    @Test
    void representsBlankNormalizedQueryAndUnrankedExpectedAtom() {
        var dataset = new EvaluationDataset(
                List.of(new DatasetAtom("expected", "cache")),
                List.of(new EvaluationQuery("the and", Set.of("expected"))));
        var options = new MonadaMemoryOptions(
                new LexicalEnrichmentPipeline(), false, LexicalExpansionOptions.DEFAULT);

        var diagnostic = TextEncodingDiagnosticGenerator.generate(
                dataset,
                dataset.queries().get(0),
                List.of(),
                List.of(),
                options,
                TextEncodingDiagnosticOptions.enabledDefaults());

        assertTrue(diagnostic.queryRepresentation().terms().isEmpty());
        var expected = diagnostic.candidates().get(0);
        assertEquals(TextEncodingCandidateSelection.MISSED_EXPECTED, expected.selection());
        assertFalse(expected.rank().isPresent());
        assertFalse(expected.score().isPresent());
        assertTrue(expected.overlappingTerms().isEmpty());
    }

    private EvaluationDataset dataset() {
        return new EvaluationDataset(
                List.of(
                        new DatasetAtom("top", "cache cache", List.of("cache")),
                        new DatasetAtom("other", "unrelated term"),
                        new DatasetAtom("expected-a", "database cache"),
                        new DatasetAtom("expected-b", "database store")),
                List.of(new EvaluationQuery(
                        "cache",
                        Set.of("expected-a", "expected-b"))));
    }

    private MonadaMemoryOptions memoryOptions() {
        var normalizer = new LexicalEnrichmentPipeline(
                Set.of(),
                Map.of(),
                Map.of("cache", List.of("redis")));
        return new MonadaMemoryOptions(normalizer, false, LexicalExpansionOptions.DEFAULT);
    }

    private TextEncodingDiagnosticGenerator.RankedResult ranked(
            String label, int rank, double score) {
        return new TextEncodingDiagnosticGenerator.RankedResult(label, rank, score);
    }

    private TextEncodingTermContribution contribution(
            String term,
            TextEncodingContributionSource source,
            double weight,
            int occurrences) {
        return new TextEncodingTermContribution(
                term, source, weight, occurrences, weight * occurrences);
    }
}
