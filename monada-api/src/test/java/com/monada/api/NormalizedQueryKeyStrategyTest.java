package com.monada.api;

import com.monada.encoder.LexicalEnrichmentPipeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NormalizedQueryKeyStrategyTest {

    private final NormalizedQueryKeyStrategy strategy =
            new NormalizedQueryKeyStrategy(new LexicalEnrichmentPipeline());

    @Test
    void sharesKeysAcrossCurrentExpectedInvariances() {
        assertSameKey(
                "WHICH MODULE OWNS QUERY KEY STRATEGIES",
                "which module owns query key strategies");
        assertSameKey(
                "difference between monada-index and monada-learning responsibilities",
                "difference between monada index and monada learning responsibilities");
        assertSameKey(
                "what   are\tthe design\nprinciples",
                "what are the design principles");
        assertSameKey("persisted vectors and indexes", "persisted vector index");
        assertSameKey(
                "design principles for experiments",
                "the design principles for the experiments");
    }

    @Test
    void exposesCurrentStopWordSensitiveCollisions() {
        assertSameKey("validate storage and index", "validate storage or index");
        assertSameKey("copy database to ledger", "copy database in ledger");
        assertSameKey("to index", "in index");
    }

    @Test
    void preservesOrderIdentifiersNegationAndTechnicalTerms() {
        assertDifferentKey("ledger database replication", "database ledger replication");
        assertDifferentKey("model 1 vector format", "model 2 vector format");
        assertDifferentKey("include speech in core", "do not include speech in core");
        assertDifferentKey("monada index owns ranking", "monada learning owns ranking");
    }

    @Test
    void fallsBackToTheExactRawQueryWhenNormalizationIsBlank() {
        assertEquals("and", strategy.keyFor("and"));
        assertEquals("AND", strategy.keyFor("AND"));
        assertNotEquals(strategy.keyFor("and"), strategy.keyFor("AND"));
    }

    @Test
    void productionDefaultsRemainExactQueryScoped() {
        assertInstanceOf(ExactQueryKeyStrategy.class,
                MonadaMemoryOptions.defaults().feedbackQueryKeyStrategy());
    }

    private void assertSameKey(String first, String second) {
        assertEquals(strategy.keyFor(first), strategy.keyFor(second));
    }

    private void assertDifferentKey(String first, String second) {
        assertNotEquals(strategy.keyFor(first), strategy.keyFor(second));
    }
}
