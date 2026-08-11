package com.monada.evaluation;

import com.monada.evaluation.datasets.ProjectMemoryDataset;
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

class ProjectMemoryFeedbackKeyValidationRunnerTest {

    @Test
    void comparesExactAndNormalizedWithIsolatedStoresAndDeterministicEvidence(
            @TempDir Path firstBase,
            @TempDir Path secondBase) throws Exception {
        var dataset = ProjectMemoryDataset.get();
        var cases = ProjectMemoryFeedbackKeyValidationMain.loadFixture();

        var first = new ProjectMemoryFeedbackKeyValidationRunner().run(
                dataset, cases, firstBase.resolve("run"));
        var second = new ProjectMemoryFeedbackKeyValidationRunner().run(
                dataset, cases, secondBase.resolve("run"));

        assertEquals(first, second);
        assertEquals(first.render(), second.render());
        assertEquals(ProjectMemoryFeedbackKeyConclusion.CONTINUE_NORMALIZED_INVESTIGATION,
                first.conclusion());
        assertEquals(List.of(
                        FeedbackQueryKeyComparisonStrategy.EXACT,
                        FeedbackQueryKeyComparisonStrategy.NORMALIZED),
                first.strategyReports().stream()
                        .map(FeedbackQueryKeyStrategyReport::strategy)
                        .toList());

        var exact = first.reportFor(FeedbackQueryKeyComparisonStrategy.EXACT);
        var normalized = first.reportFor(FeedbackQueryKeyComparisonStrategy.NORMALIZED);
        assertEquals(8, exact.summary().intendedTransferIsolated());
        assertEquals(8, exact.summary().negativeIsolated());
        assertEquals(0, exact.summary().falseSharingCount());
        assertTrue(normalized.summary().intendedTransferImproved() > 0);
        assertEquals(8, normalized.summary().negativeIsolated());
        assertEquals(0, normalized.summary().falseSharingCount());
        assertTrue(normalized.meritsFurtherInvestigation());
        for (int index = 0; index < exact.observations().size(); index++) {
            assertEquals(exact.observations().get(index).targetBeforeReplay(),
                    normalized.observations().get(index).targetBeforeReplay());
            assertEquals(exact.observations().get(index).baselineTopK(),
                    normalized.observations().get(index).baselineTopK());
        }

        var normalizationCases = normalized.observations().stream()
                .filter(observation -> observation.comparisonCase().category().intendedTransfer())
                .filter(observation -> observation.comparisonCase().category()
                        != FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL)
                .toList();
        assertEquals(8, normalizationCases.size());
        assertTrue(normalizationCases.stream().allMatch(FeedbackQueryKeyCaseObservation::keysMatched));
        assertTrue(normalizationCases.stream().allMatch(observation -> observation.classification()
                == FeedbackQueryKeyCaseClassification.INTENDED_TRANSFER));
        assertTrue(normalized.observations().stream()
                .filter(observation -> !observation.comparisonCase().category().intendedTransfer())
                .allMatch(observation -> observation.classification()
                        == FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER));

        Path exactLog = firstBase.resolve("run")
                .resolve("exact")
                .resolve(FeedbackStore.DEFAULT_SEGMENT);
        Path normalizedLog = firstBase.resolve("run")
                .resolve("normalized")
                .resolve(FeedbackStore.DEFAULT_SEGMENT);
        assertEquals(17, Files.readAllLines(exactLog, StandardCharsets.UTF_8).size());
        assertEquals(17, Files.readAllLines(normalizedLog, StandardCharsets.UTF_8).size());
        assertFalse(Files.exists(firstBase.resolve("run").resolve("lexically-enriched")));
        assertTrue(Files.readString(normalizedLog, StandardCharsets.UTF_8)
                .contains("\"queryKey\":\"normalized:modules involved text recall pipeline from query ranked result\""));

        String rendered = first.render();
        assertTrue(rendered.contains("Arms: EXACT, NORMALIZED"));
        assertTrue(rendered.contains("full-corpus-rank="));
        assertTrue(rendered.contains("False sharing: 0/8"));
        assertTrue(rendered.contains("Evidence conclusion: CONTINUE_NORMALIZED_INVESTIGATION"));
    }

    @Test
    void validatesExperimentCountsCategoriesAndDeltas(@TempDir Path basePath) throws Exception {
        var cases = ProjectMemoryFeedbackKeyValidationMain.loadFixture();
        var runner = new ProjectMemoryFeedbackKeyValidationRunner();

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> runner.run(ProjectMemoryDataset.get(), cases.subList(0, 16),
                        basePath.resolve("missing-case")))
                .getMessage().contains("exactly 1 exact control, 8 normalization transfers"));

        var invalidDelta = new ArrayList<>(cases);
        FeedbackQueryKeyComparisonCase source = invalidDelta.get(1);
        invalidDelta.set(1, new FeedbackQueryKeyComparisonCase(
                source.id(), source.category(), source.seedQueryText(), source.evaluationQueryText(),
                source.targetLabel(), source.signal(), 0.75, source.createdAt(),
                source.expectedTargetRelevant()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> runner.run(ProjectMemoryDataset.get(), invalidDelta,
                        basePath.resolve("invalid-delta")))
                .getMessage().contains("must use delta 0.5"));
    }

    @Test
    void exactControlImprovementAloneIsInconclusive() {
        var runner = new ProjectMemoryFeedbackKeyValidationRunner();
        var normalized = strategyReport(
                FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL,
                new FeedbackQueryKeyStrategySummary(1, 0, 0, 0, 0, 0, 0, 0, 0));

        assertEquals(ProjectMemoryFeedbackKeyConclusion.INCONCLUSIVE_KEEP_EXACT,
                runner.conclusionFor(List.of(normalized)));
    }

    @Test
    void contaminationCannotBeHiddenByAggregateImprovement() {
        var runner = new ProjectMemoryFeedbackKeyValidationRunner();
        var normalized = strategyReport(
                FeedbackQueryKeyComparisonCaseCategory.CASE_NORMALIZATION,
                new FeedbackQueryKeyStrategySummary(1, 0, 0, 0, 0, 0, 1, 1, 1));

        assertEquals(RankingChange.IMPROVED, normalized.replayedVsBaseline().aggregate());
        assertFalse(normalized.meritsFurtherInvestigation());
        assertEquals(ProjectMemoryFeedbackKeyConclusion.KEEP_EXACT_ONLY,
                runner.conclusionFor(List.of(normalized)));
    }

    private FeedbackQueryKeyStrategyReport strategyReport(
            FeedbackQueryKeyComparisonCaseCategory category,
            FeedbackQueryKeyStrategySummary summary) {
        var before = report(0.0);
        var after = report(0.5);
        var observations = new ArrayList<FeedbackQueryKeyCaseObservation>();
        observations.add(observation(
                "synthetic-transfer",
                category,
                FeedbackQueryKeyCaseClassification.INTENDED_TRANSFER));
        if (summary.contaminationCount() > 0) {
            observations.add(observation(
                    "synthetic-contamination",
                    FeedbackQueryKeyComparisonCaseCategory.SEMANTICALLY_DISTINCT,
                    FeedbackQueryKeyCaseClassification.CONTAMINATION));
        }
        return new FeedbackQueryKeyStrategyReport(
                FeedbackQueryKeyComparisonStrategy.NORMALIZED,
                "NormalizedQueryKeyStrategy",
                before,
                after,
                new EvaluationComparator().compare(before, after),
                observations,
                summary);
    }

    private FeedbackQueryKeyCaseObservation observation(
            String id,
            FeedbackQueryKeyComparisonCaseCategory category,
            FeedbackQueryKeyCaseClassification classification) {
        var comparisonCase = new FeedbackQueryKeyComparisonCase(
                id, category, id + " seed", id + " evaluation", "ka_target",
                FeedbackSignal.POSITIVE, 1.0, Instant.EPOCH,
                category.intendedTransfer());
        return new FeedbackQueryKeyCaseObservation(
                comparisonCase,
                "normalized:key",
                "normalized:key",
                true,
                category.intendedTransfer(),
                new FeedbackQueryKeyTargetRank(2, 0.1),
                new FeedbackQueryKeyTargetRank(1, 0.6),
                RankingChange.IMPROVED,
                List.of("ka_other"),
                List.of("ka_target"),
                metrics(0.0),
                metrics(0.5),
                metrics(0.0),
                0.5,
                classification);
    }

    private EvaluationReport report(double recallAtOne) {
        var precision = metrics(recallAtOne);
        var recall = metrics(recallAtOne);
        var hit = metrics(recallAtOne);
        return new EvaluationReport(List.of(), precision, recall, hit, recallAtOne);
    }

    private Map<Integer, Double> metrics(double value) {
        return Map.of(1, value, 3, value, 5, value);
    }
}
