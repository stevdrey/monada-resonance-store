package com.monada.encoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LexicalExpansionOptionsTest {

    @Test
    void acceptsValidFiniteAndPositiveWeights() {
        assertDoesNotThrow(() -> new LexicalExpansionOptions(1.0, 0.25));
        assertDoesNotThrow(() -> new LexicalExpansionOptions(1.0, 1.0));
        assertDoesNotThrow(() -> new LexicalExpansionOptions(2.5, 1.2));
        
        LexicalExpansionOptions options = new LexicalExpansionOptions(1.5, 0.75);
        assertEquals(1.5, options.originalWeight());
        assertEquals(0.75, options.expansionWeight());
    }

    @Test
    void rejectsNonPositiveWeights() {
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(0.0, 0.25));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(-1.0, 0.25));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(1.0, -0.5));
    }

    @Test
    void rejectsNonFiniteWeights() {
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(Double.NaN, 0.25));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(Double.POSITIVE_INFINITY, 0.25));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(Double.NEGATIVE_INFINITY, 0.25));

        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(1.0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(1.0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(1.0, Double.NEGATIVE_INFINITY));
    }

    @Test
    void rejectsExpansionWeightGreaterThanOriginalWeight() {
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(1.0, 1.1));
        assertThrows(IllegalArgumentException.class, () -> new LexicalExpansionOptions(0.5, 0.6));
    }
}
