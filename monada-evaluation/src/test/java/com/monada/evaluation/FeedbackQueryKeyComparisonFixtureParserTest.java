package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackQueryKeyComparisonFixtureParserTest {

    @Test
    void mainFixtureContainsSixVersionedTransferAndContaminationCases() throws Exception {
        var cases = FeedbackQueryKeyComparisonMain.loadFixture();

        assertEquals(6, cases.size());
        assertEquals("exact_append_only", cases.getFirst().id());
        assertEquals(FeedbackQueryKeyComparisonCaseCategory.LEXICAL_EQUIVALENCE,
                cases.get(2).category());
        assertTrue(cases.subList(3, 6).stream().noneMatch(
                FeedbackQueryKeyComparisonCase::expectedTargetRelevant));
        assertEquals(10.0, cases.get(3).delta());
    }

    @Test
    void rejectsInvalidBooleanAndFieldCount() {
        var invalidBoolean = new FeedbackQueryKeyComparisonFixtureParser(
                input("id\tEXACT_CONTROL\tseed\tevaluation\tka_target\tPOSITIVE\t1.0\t2026-01-01T00:00:00Z\tmaybe"),
                "invalid.tsv");
        var invalidFieldCount = new FeedbackQueryKeyComparisonFixtureParser(
                input("id\tEXACT_CONTROL\tseed"), "invalid.tsv");

        assertTrue(assertThrows(IllegalArgumentException.class, invalidBoolean::parse)
                .getMessage().contains("expected true or false"));
        assertTrue(assertThrows(IllegalArgumentException.class, invalidFieldCount::parse)
                .getMessage().contains("must contain 9"));
    }

    @Test
    void rejectsCategoryRelevanceMismatch() {
        var parser = new FeedbackQueryKeyComparisonFixtureParser(
                input("id\tEXACT_CONTROL\tseed\tevaluation\tka_target\tPOSITIVE\t1.0\t2026-01-01T00:00:00Z\tfalse"),
                "invalid.tsv");

        assertThrows(IllegalArgumentException.class, parser::parse);
    }

    private ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
