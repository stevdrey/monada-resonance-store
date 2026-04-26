package com.monada.encoder;

import com.monada.core.FrequencyVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void vectorLengthMatchesConfiguredDimensions() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(8);
        float[] values = encoder.encode("test").values();
        assertEquals(8, values.length);
    }

    @Test
    void differentInputProducesDifferentVector() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(64);
        FrequencyVector a = encoder.encode("graph document database");
        FrequencyVector b = encoder.encode("relational sql database");
        assertFalse(java.util.Arrays.equals(a.values(), b.values()));
    }

    @Test
    void emptyContentDoesNotThrowAndReturnsZeroVector() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(8);
        FrequencyVector vector = assertDoesNotThrow(() -> encoder.encode(""));
        float[] values = vector.values();
        assertEquals(8, values.length);
        for (float v : values) {
            assertEquals(0.0f, v);
        }
    }

    @Test
    void multipleNonEmptyInputsProduceUnitLengthVectors() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(64);
        String[] inputs = {
                "graph document database",
                "relational sql database",
                "graph nodes relationships",
                "the quick brown fox"
        };
        for (String input : inputs) {
            float[] values = encoder.encode(input).values();
            double magnitude = 0.0;
            for (float v : values) {
                magnitude += v * v;
            }
            assertEquals(1.0, magnitude, 1e-5, "magnitude should be ~1 for input: " + input);
        }
    }

    @Test
    void deterministicRankingMicroBenchmark() {
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(128);

        // Tiny corpus mapping concept -> descriptive text.
        java.util.Map<String, String> corpus = new java.util.LinkedHashMap<>();
        corpus.put("OrientDB", "OrientDB multi model graph and document database");
        corpus.put("ArangoDB", "ArangoDB native multi model graph and document database");
        corpus.put("PostgreSQL", "PostgreSQL relational sql database with acid transactions");
        corpus.put("Neo4j", "Neo4j graph database with nodes and relationships");

        java.util.Map<String, FrequencyVector> encoded = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e : corpus.entrySet()) {
            encoded.put(e.getKey(), encoder.encode(e.getValue()));
        }

        assertTopMatch(encoder, encoded, "graph document database",
                java.util.Set.of("OrientDB", "ArangoDB"));
        assertTopMatch(encoder, encoded, "relational sql database",
                java.util.Set.of("PostgreSQL"));
        assertTopMatch(encoder, encoded, "graph nodes relationships",
                java.util.Set.of("Neo4j"));

        // Stability: re-running yields the exact same ranking.
        String firstRun = ranking(encoder, encoded, "graph document database");
        String secondRun = ranking(encoder, encoded, "graph document database");
        assertEquals(firstRun, secondRun);
    }

    private static void assertTopMatch(SimpleFrequencyEncoder encoder,
                                       java.util.Map<String, FrequencyVector> encoded,
                                       String query,
                                       java.util.Set<String> expectedTopCandidates) {
        FrequencyVector q = encoder.encode(query);
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (java.util.Map.Entry<String, FrequencyVector> e : encoded.entrySet()) {
            double score = cosine(q.values(), e.getValue().values());
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        assertTrue(expectedTopCandidates.contains(best),
                "expected top match for '" + query + "' to be one of " + expectedTopCandidates
                        + " but was '" + best + "' (score=" + bestScore + ")");
    }

    private static String ranking(SimpleFrequencyEncoder encoder,
                                  java.util.Map<String, FrequencyVector> encoded,
                                  String query) {
        FrequencyVector q = encoder.encode(query);
        java.util.List<java.util.Map.Entry<String, Double>> scored = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, FrequencyVector> e : encoded.entrySet()) {
            scored.add(java.util.Map.entry(e.getKey(), cosine(q.values(), e.getValue().values())));
        }
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.getValue(), a.getValue());
            return byScore != 0 ? byScore : a.getKey().compareTo(b.getKey());
        });
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Double> entry : scored) {
            sb.append(entry.getKey()).append('=').append(String.format(java.util.Locale.ROOT,
                    "%.6f", entry.getValue())).append(';');
        }
        return sb.toString();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * (double) b[i];
        }
        // vectors are L2-normalized by the encoder, so dot == cosine similarity
        return dot;
    }
}
