package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryKeyDiagnosticTest {

    @Test
    void withoutFeedbackCreatesDiagnosticWithNullSeedKey() {
        var diagnostic = QueryKeyDiagnostic.withoutFeedback(
                "exact:query text", "ExactQueryKeyStrategy");

        assertEquals("exact:query text", diagnostic.queryKey());
        assertEquals("ExactQueryKeyStrategy", diagnostic.strategyName());
        assertFalse(diagnostic.feedbackAware());
        assertNull(diagnostic.seedQueryKey());
        assertFalse(diagnostic.hasSeedQueryKey());
        assertFalse(diagnostic.feedbackKeyMatch());
    }

    @Test
    void withFeedbackCreatesDiagnosticWithSeedKey() {
        var diagnostic = QueryKeyDiagnostic.withFeedback(
                "lexical-expansion:cache redis", "LexicallyEnrichedQueryKeyStrategy",
                "lexical-expansion:cache redis");

        assertEquals("lexical-expansion:cache redis", diagnostic.queryKey());
        assertEquals("LexicallyEnrichedQueryKeyStrategy", diagnostic.strategyName());
        assertTrue(diagnostic.feedbackAware());
        assertEquals("lexical-expansion:cache redis", diagnostic.seedQueryKey());
        assertTrue(diagnostic.hasSeedQueryKey());
        assertTrue(diagnostic.feedbackKeyMatch());
    }

    @Test
    void feedbackKeyMatchReturnsFalseWhenSeedKeyDiffers() {
        var diagnostic = QueryKeyDiagnostic.withFeedback(
                "lexical-expansion:cache redis", "LexicallyEnrichedQueryKeyStrategy",
                "exact:temporary lookup store");

        assertEquals("lexical-expansion:cache redis", diagnostic.queryKey());
        assertEquals("exact:temporary lookup store", diagnostic.seedQueryKey());
        assertTrue(diagnostic.hasSeedQueryKey());
        assertFalse(diagnostic.feedbackKeyMatch());
    }

    @Test
    void feedbackKeyMatchReturnsFalseForNonFeedbackAware() {
        var diagnostic = QueryKeyDiagnostic.withoutFeedback(
                "exact:query text", "ExactQueryKeyStrategy");

        assertFalse(diagnostic.feedbackAware());
        assertFalse(diagnostic.feedbackKeyMatch());
        assertFalse(diagnostic.hasSeedQueryKey());
    }

    @Test
    void withFeedbackRequiresNonNullSeedKey() {
        assertThrows(NullPointerException.class, () ->
                QueryKeyDiagnostic.withFeedback("key", "Strategy", null));
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(NullPointerException.class, () ->
                new QueryKeyDiagnostic(null, "Strategy", true, "seed"));
        assertThrows(NullPointerException.class, () ->
                new QueryKeyDiagnostic("key", null, true, "seed"));
        assertThrows(IllegalArgumentException.class, () ->
                new QueryKeyDiagnostic("", "Strategy", false, null));
        assertThrows(IllegalArgumentException.class, () ->
                new QueryKeyDiagnostic("key", "  ", false, null));
    }

    @Test
    void constructorRejectsFeedbackAwareWithoutSeedKey() {
        assertThrows(IllegalArgumentException.class, () ->
                new QueryKeyDiagnostic("key", "Strategy", true, null));
        assertThrows(IllegalArgumentException.class, () ->
                new QueryKeyDiagnostic("key", "Strategy", true, ""));
    }

    @Test
    void constructorRejectsNonFeedbackAwareWithSeedKey() {
        // A non-feedback-aware diagnostic must not carry a seed key
        assertThrows(IllegalArgumentException.class, () ->
                new QueryKeyDiagnostic("key", "Strategy", false, "some-seed-key"));
    }

    @Test
    void constructorAllowsNullSeedKeyForNonFeedbackAware() {
        // Null seedKey is correct for non-feedback-aware
        var diagnostic = new QueryKeyDiagnostic("key", "Strategy", false, null);
        assertFalse(diagnostic.hasSeedQueryKey());
        assertNull(diagnostic.seedQueryKey());
    }

    // ---- P3: feedbackKeyMatch must be gated on feedbackAware ----

    @Test
    void feedbackKeyMatchReturnsTrueOnlyWhenFeedbackAwareAndKeysMatch() {
        var diagnostic = new QueryKeyDiagnostic("exact:query", "ExactQueryKeyStrategy", true, "exact:query");

        assertTrue(diagnostic.feedbackAware());
        assertTrue(diagnostic.feedbackKeyMatch());
    }

    @Test
    void feedbackKeyMatchReturnsFalseWhenSeedKeyDiffersEvenIfFeedbackAware() {
        var diagnostic = new QueryKeyDiagnostic("exact:query", "ExactQueryKeyStrategy", true, "other-seed");

        assertTrue(diagnostic.feedbackAware());
        assertFalse(diagnostic.feedbackKeyMatch());
    }
}
