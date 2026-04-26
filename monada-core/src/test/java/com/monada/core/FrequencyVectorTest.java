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
}
