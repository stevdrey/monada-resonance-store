package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryFeedbackKeyValidationFixtureParserTest {

    @Test
    void fixtureContainsDistinctVersionedControlTransferAndNegativeCases() throws Exception {
        var cases = ProjectMemoryFeedbackKeyValidationMain.loadFixture();

        assertEquals(17, cases.size());
        assertEquals(1, cases.stream()
                .filter(comparisonCase -> comparisonCase.category()
                        == FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL)
                .count());
        assertEquals(8, cases.stream()
                .filter(comparisonCase -> comparisonCase.category().intendedTransfer())
                .filter(comparisonCase -> comparisonCase.category()
                        != FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL)
                .count());
        assertEquals(8, cases.stream()
                .filter(comparisonCase -> !comparisonCase.category().intendedTransfer())
                .count());

        assertEquals(cases.size(), cases.stream()
                .map(FeedbackQueryKeyComparisonCase::id).distinct().count());
        assertEquals(cases.size(), cases.stream()
                .map(FeedbackQueryKeyComparisonCase::seedQueryText).distinct().count());
        assertEquals(cases.size(), cases.stream()
                .map(FeedbackQueryKeyComparisonCase::evaluationQueryText).distinct().count());
        assertEquals(cases.size(), cases.stream()
                .map(FeedbackQueryKeyComparisonCase::createdAt).distinct().count());

        Set<FeedbackQueryKeyComparisonCaseCategory> categories = cases.stream()
                .map(FeedbackQueryKeyComparisonCase::category)
                .collect(Collectors.toUnmodifiableSet());
        assertTrue(categories.containsAll(Set.of(
                FeedbackQueryKeyComparisonCaseCategory.CASE_NORMALIZATION,
                FeedbackQueryKeyComparisonCaseCategory.PUNCTUATION_SEPARATOR_NORMALIZATION,
                FeedbackQueryKeyComparisonCaseCategory.WHITESPACE_NORMALIZATION,
                FeedbackQueryKeyComparisonCaseCategory.STOP_WORD_NORMALIZATION,
                FeedbackQueryKeyComparisonCaseCategory.PLURAL_NORMALIZATION,
                FeedbackQueryKeyComparisonCaseCategory.COMBINED_NORMALIZATION)));

        for (FeedbackQueryKeyComparisonCase comparisonCase : cases) {
            double expectedDelta = comparisonCase.category()
                    == FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL
                    ? 0.10
                    : comparisonCase.category().intendedTransfer() ? 0.50 : 10.0;
            assertEquals(expectedDelta, comparisonCase.delta(), comparisonCase.id());
            assertEquals(comparisonCase.category().intendedTransfer(),
                    comparisonCase.expectedTargetRelevant(), comparisonCase.id());
        }
    }
}
