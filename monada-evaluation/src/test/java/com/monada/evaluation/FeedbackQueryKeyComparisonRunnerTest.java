package com.monada.evaluation;

import com.monada.evaluation.datasets.FeedbackQueryKeyComparisonDataset;
import com.monada.storage.feedback.FeedbackSignal;
import com.monada.storage.feedback.FeedbackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackQueryKeyComparisonRunnerTest {

    @Test
    void comparesAllStrategiesWithFreshPersistedStoresAndDeterministicEvidence(
            @TempDir Path firstBase,
            @TempDir Path secondBase) throws Exception {
        var dataset = FeedbackQueryKeyComparisonDataset.get();
        var cases = FeedbackQueryKeyComparisonMain.loadFixture();

        var first = new FeedbackQueryKeyComparisonRunner().run(dataset, cases, firstBase.resolve("run"));
        var second = new FeedbackQueryKeyComparisonRunner().run(dataset, cases, secondBase.resolve("run"));

        assertEquals(first, second);
        assertEquals(first.render(), second.render());
        assertEquals(FeedbackQueryKeyRecommendation.INVESTIGATE_NORMALIZED, first.recommendation());
        assertEquals(List.of(
                        FeedbackQueryKeyComparisonStrategy.EXACT,
                        FeedbackQueryKeyComparisonStrategy.NORMALIZED,
                        FeedbackQueryKeyComparisonStrategy.LEXICALLY_ENRICHED),
                first.strategyReports().stream().map(FeedbackQueryKeyStrategyReport::strategy).toList());

        var exact = first.reportFor(FeedbackQueryKeyComparisonStrategy.EXACT);
        var normalized = first.reportFor(FeedbackQueryKeyComparisonStrategy.NORMALIZED);
        var lexical = first.reportFor(FeedbackQueryKeyComparisonStrategy.LEXICALLY_ENRICHED);
        assertEquals(1, exact.summary().intendedTransferImproved());
        assertEquals(2, exact.summary().intendedTransferIsolated());
        assertEquals(3, exact.summary().negativeIsolated());
        assertEquals(2, normalized.summary().intendedTransferImproved());
        assertEquals(0, normalized.summary().falseSharingCount());
        assertEquals(3, lexical.summary().intendedTransferImproved());
        assertEquals(3, lexical.summary().contaminationCount());
        assertEquals(1.0, lexical.summary().falseSharingRate());
        assertFalse(lexical.meritsFurtherInvestigation());

        assertClassification(exact, "normalized_embedded",
                FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER, false);
        assertClassification(normalized, "normalized_embedded",
                FeedbackQueryKeyCaseClassification.INTENDED_TRANSFER, true);
        assertClassification(lexical, "normalized_embedded",
                FeedbackQueryKeyCaseClassification.INTENDED_TRANSFER, true);
        assertClassification(exact, "lexical_second_pass",
                FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER, false);
        assertClassification(normalized, "lexical_second_pass",
                FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER, false);
        assertClassification(lexical, "lexical_second_pass",
                FeedbackQueryKeyCaseClassification.INTENDED_TRANSFER, true);

        for (String negativeCaseId : List.of(
                "confusable_similarity", "temporary_lookup_intent", "ordered_terms_intent")) {
            assertClassification(exact, negativeCaseId,
                    FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER, false);
            assertClassification(normalized, negativeCaseId,
                    FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER, false);
            assertClassification(lexical, negativeCaseId,
                    FeedbackQueryKeyCaseClassification.CONTAMINATION, true);
        }

        Path exactLog = firstBase.resolve("run")
                .resolve("exact")
                .resolve(FeedbackStore.DEFAULT_SEGMENT);
        Path normalizedLog = firstBase.resolve("run")
                .resolve("normalized")
                .resolve(FeedbackStore.DEFAULT_SEGMENT);
        Path lexicalLog = firstBase.resolve("run")
                .resolve("lexically-enriched")
                .resolve(FeedbackStore.DEFAULT_SEGMENT);
        assertEquals(6, Files.readAllLines(exactLog, StandardCharsets.UTF_8).size());
        assertEquals(6, Files.readAllLines(normalizedLog, StandardCharsets.UTF_8).size());
        assertEquals(6, Files.readAllLines(lexicalLog, StandardCharsets.UTF_8).size());
        assertTrue(Files.readString(normalizedLog, StandardCharsets.UTF_8)
                .contains("\"queryKey\":\"normalized:embedded lightweight storage engine\""));
        assertTrue(Files.readString(lexicalLog, StandardCharsets.UTF_8)
                .contains("\"queryKey\":\"lexical-expansion:approximate nearest neighbor\""));

        String rendered = first.render();
        assertTrue(rendered.contains("full-corpus-rank="));
        assertTrue(rendered.contains("False sharing: 3/3"));
        assertTrue(rendered.contains("Evidence conclusion: INVESTIGATE_NORMALIZED"));
    }

    @Test
    void validatesCaseIdsTargetsQueriesAndExplicitRelevance(@TempDir Path basePath) throws Exception {
        var dataset = FeedbackQueryKeyComparisonDataset.get();
        var fixture = FeedbackQueryKeyComparisonMain.loadFixture();
        var runner = new FeedbackQueryKeyComparisonRunner();

        var duplicateId = new ArrayList<>(fixture);
        duplicateId.set(1, copyWithId(fixture.get(1), fixture.getFirst().id()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> runner.run(dataset, duplicateId, basePath.resolve("duplicate")))
                .getMessage().contains("duplicate comparison case id"));

        var unknownTarget = new ArrayList<>(fixture);
        unknownTarget.set(0, copyWithTarget(fixture.getFirst(), "ka_missing"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> runner.run(dataset, unknownTarget, basePath.resolve("unknown-target")))
                .getMessage().contains("unknown target label"));

        var unknownQuery = new ArrayList<>(fixture);
        unknownQuery.set(0, copyWithEvaluationQuery(fixture.getFirst(), "missing evaluation query"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> runner.run(dataset, unknownQuery, basePath.resolve("unknown-query")))
                .getMessage().contains("unknown evaluation query"));

        var relevanceMismatch = new ArrayList<>(fixture);
        relevanceMismatch.set(0, new FeedbackQueryKeyComparisonCase(
                "relevance_mismatch",
                FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL,
                fixture.getFirst().seedQueryText(),
                fixture.getFirst().evaluationQueryText(),
                "ka_redis",
                FeedbackSignal.POSITIVE,
                1.0,
                Instant.EPOCH,
                true));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> runner.run(dataset, relevanceMismatch, basePath.resolve("relevance")))
                .getMessage().contains("expected target relevance"));
    }

    @Test
    void classifiesSharedNegativeKeyWithoutRankOrScoreMovement() {
        var comparisonCase = new FeedbackQueryKeyComparisonCase(
                "shared_without_movement",
                FeedbackQueryKeyComparisonCaseCategory.CONFUSABLE_NEIGHBOR,
                "seed",
                "evaluation",
                "ka_target",
                FeedbackSignal.POSITIVE,
                1.0,
                Instant.EPOCH,
                false);
        var rank = new FeedbackQueryKeyTargetRank(3, 0.2);

        assertEquals(FeedbackQueryKeyCaseClassification.SHARED_KEY_NO_MOVEMENT,
                new FeedbackQueryKeyComparisonRunner().classify(comparisonCase, true, rank, rank));
    }

    @Test
    void aggregateImprovementCannotMakeAContaminatedStrategyEligible() {
        var before = report(0.5);
        var after = report(0.75);
        var comparison = new EvaluationComparator().compare(before, after);
        var summary = new FeedbackQueryKeyStrategySummary(1, 0, 0, 0, 0, 0, 1, 1, 1);
        var strategyReport = new FeedbackQueryKeyStrategyReport(
                FeedbackQueryKeyComparisonStrategy.NORMALIZED,
                "NormalizedQueryKeyStrategy",
                before,
                after,
                comparison,
                List.of(),
                summary);

        assertEquals(RankingChange.IMPROVED, comparison.aggregate());
        assertFalse(strategyReport.meritsFurtherInvestigation());
    }

    private void assertClassification(
            FeedbackQueryKeyStrategyReport report,
            String caseId,
            FeedbackQueryKeyCaseClassification expected,
            boolean expectedKeysMatched) {
        var observation = report.observations().stream()
                .filter(candidate -> candidate.comparisonCase().id().equals(caseId))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, observation.classification());
        assertEquals(expectedKeysMatched, observation.keysMatched());
    }

    private FeedbackQueryKeyComparisonCase copyWithId(
            FeedbackQueryKeyComparisonCase source,
            String id) {
        return new FeedbackQueryKeyComparisonCase(
                id, source.category(), source.seedQueryText(), source.evaluationQueryText(),
                source.targetLabel(), source.signal(), source.delta(), source.createdAt(),
                source.expectedTargetRelevant());
    }

    private FeedbackQueryKeyComparisonCase copyWithTarget(
            FeedbackQueryKeyComparisonCase source,
            String targetLabel) {
        return new FeedbackQueryKeyComparisonCase(
                source.id(), source.category(), source.seedQueryText(), source.evaluationQueryText(),
                targetLabel, source.signal(), source.delta(), source.createdAt(),
                source.expectedTargetRelevant());
    }

    private FeedbackQueryKeyComparisonCase copyWithEvaluationQuery(
            FeedbackQueryKeyComparisonCase source,
            String evaluationQueryText) {
        return new FeedbackQueryKeyComparisonCase(
                source.id(), source.category(), source.seedQueryText(), evaluationQueryText,
                source.targetLabel(), source.signal(), source.delta(), source.createdAt(),
                source.expectedTargetRelevant());
    }

    private EvaluationReport report(double recallAtThree) {
        var precision = Map.of(1, 0.0, 3, 0.0, 5, 0.0);
        var recall = Map.of(1, 0.0, 3, recallAtThree, 5, 0.0);
        var hit = Map.of(1, 0.0, 3, 0.0, 5, 0.0);
        return new EvaluationReport(List.of(), precision, recall, hit, 0.0);
    }
}
