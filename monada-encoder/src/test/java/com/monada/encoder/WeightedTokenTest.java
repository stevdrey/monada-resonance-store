package com.monada.encoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeightedTokenTest {

    @Test
    void acceptsValidFiniteAndPositiveWeights() {
        assertDoesNotThrow(() -> new WeightedToken("hello", 1.0));
        assertDoesNotThrow(() -> new WeightedToken("world", 0.25));
        
        WeightedToken wt = new WeightedToken("test", 3.14);
        assertEquals("test", wt.token());
        assertEquals(3.14, wt.weight());
    }

    @Test
    void rejectsNullToken() {
        assertThrows(NullPointerException.class, () -> new WeightedToken(null, 1.0));
    }

    @Test
    void rejectsNonPositiveWeights() {
        assertThrows(IllegalArgumentException.class, () -> new WeightedToken("token", 0.0));
        assertThrows(IllegalArgumentException.class, () -> new WeightedToken("token", -0.1));
    }

    @Test
    void rejectsNonFiniteWeights() {
        assertThrows(IllegalArgumentException.class, () -> new WeightedToken("token", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new WeightedToken("token", Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new WeightedToken("token", Double.NEGATIVE_INFINITY));
    }
}
