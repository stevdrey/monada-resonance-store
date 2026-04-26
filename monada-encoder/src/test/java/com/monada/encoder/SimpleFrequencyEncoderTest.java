package com.monada.encoder;

import com.monada.core.FrequencyVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleFrequencyEncoderTest {

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleFrequencyEncoder(0));
        assertThrows(IllegalArgumentException.class, () -> new SimpleFrequencyEncoder(-1));
    }

    @Test
    void encodingIsDeterministicAcrossInvocations() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(64);
        FrequencyVector first = encoder.encode("hello world hello");
        FrequencyVector second = encoder.encode("hello world hello");
        assertArrayEquals(first.values(), second.values());
    }

    @Test
    void encodingIsNormalized() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(32);
        float[] values = encoder.encode("the quick brown fox jumps over the lazy dog").values();
        double magnitude = 0.0;
        for (float v : values) {
            magnitude += v * v;
        }
        assertEquals(1.0, magnitude, 1e-5);
    }

    @Test
    void blankContentProducesZeroVector() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(8);
        float[] values = encoder.encode("   ").values();
        assertEquals(8, values.length);
        for (float v : values) {
            assertTrue(v == 0.0f);
        }
    }

    @Test
    void digestReuseIsThreadSafe() throws Exception {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(64);
        Thread[] threads = new Thread[4];
        boolean[] ok = new boolean[threads.length];
        for (int i = 0; i < threads.length; i++) {
            int idx = i;
            threads[i] = new Thread(() -> {
                FrequencyVector a = encoder.encode("alpha beta gamma");
                FrequencyVector b = encoder.encode("alpha beta gamma");
                ok[idx] = java.util.Arrays.equals(a.values(), b.values());
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        for (boolean b : ok) {
            assertTrue(b);
        }
    }
}
