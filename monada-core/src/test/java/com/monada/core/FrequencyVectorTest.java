package com.monada.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrequencyVectorTest {

    @Test
    void equalsAndHashCodeUseValueSemantics() {
        FrequencyVector a = new FrequencyVector(new float[]{1f, 2f, 3f});
        FrequencyVector b = new FrequencyVector(new float[]{1f, 2f, 3f});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentContentsAreNotEqual() {
        FrequencyVector a = new FrequencyVector(new float[]{1f, 2f});
        FrequencyVector b = new FrequencyVector(new float[]{1f, 3f});
        assertNotEquals(a, b);
    }

    @Test
    void worksInsideHashBasedCollections() {
        Set<FrequencyVector> set = new HashSet<>();
        set.add(new FrequencyVector(new float[]{1f, 2f, 3f}));
        assertTrue(set.contains(new FrequencyVector(new float[]{1f, 2f, 3f})));
    }

    @Test
    void toStringIncludesValues() {
        FrequencyVector a = new FrequencyVector(new float[]{1f, 2f});
        assertTrue(a.toString().contains("1.0"));
        assertTrue(a.toString().contains("2.0"));
    }

    @Test
    void emptyVectorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FrequencyVector(new float[]{}));
    }

    @Test
    void dotProductComputesCorrectValue() {
        FrequencyVector a = new FrequencyVector(new float[]{1f, 2f, 3f});
        FrequencyVector b = new FrequencyVector(new float[]{4f, 5f, 6f});
        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32.0, a.dotProduct(b), 1e-6);
    }

    @Test
    void dotProductIsCommutative() {
        FrequencyVector a = new FrequencyVector(new float[]{0.5f, 0.3f});
        FrequencyVector b = new FrequencyVector(new float[]{0.7f, 0.2f});
        assertEquals(a.dotProduct(b), b.dotProduct(a), 1e-9);
    }

    @Test
    void dotProductRejectsDimensionMismatch() {
        FrequencyVector a = new FrequencyVector(new float[]{1f, 2f});
        FrequencyVector b = new FrequencyVector(new float[]{1f, 2f, 3f});
        assertThrows(IllegalArgumentException.class, () -> a.dotProduct(b));
    }

    @Test
    void dotProductRejectsNull() {
        FrequencyVector a = new FrequencyVector(new float[]{1f, 2f});
        assertThrows(NullPointerException.class, () -> a.dotProduct(null));
    }

    @Test
    void dotProductWithOrthogonalVectorsIsZero() {
        FrequencyVector a = new FrequencyVector(new float[]{1f, 0f});
        FrequencyVector b = new FrequencyVector(new float[]{0f, 1f});
        assertEquals(0.0, a.dotProduct(b), 1e-9);
    }
}
