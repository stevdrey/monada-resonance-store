package com.monada.encoder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoOpTextNormalizerTest {

    private final NoOpTextNormalizer normalizer = new NoOpTextNormalizer();

    @Test
    void preservesOriginalText() {
        var result = normalizer.normalize("cosine similarity over high dimensional vectors");

        assertEquals("cosine similarity over high dimensional vectors", result.original());
        assertEquals("cosine similarity over high dimensional vectors", result.normalized());
        assertEquals(List.of(), result.expansions());
    }

    @Test
    void enrichedTextEqualsNormalized() {
        var result = normalizer.normalize("approximate nearest neighbor search");

        assertEquals(result.normalized(), result.enrichedText());
    }

    @Test
    void preservesBlankText() {
        var result = normalizer.normalize("   ");

        assertEquals("   ", result.original());
        assertEquals("   ", result.normalized());
    }

    @Test
    void preservesEmptyString() {
        var result = normalizer.normalize("");

        assertEquals("", result.original());
        assertEquals("", result.normalized());
        assertEquals(List.of(), result.expansions());
    }

    @Test
    void isDeterministic() {
        var input = "feedback aware ranking with delta adjustments";

        var first = normalizer.normalize(input);
        var second = normalizer.normalize(input);

        assertEquals(first.original(), second.original());
        assertEquals(first.normalized(), second.normalized());
        assertEquals(first.expansions(), second.expansions());
    }

    @Test
    void throwsOnNull() {
        assertThrows(NullPointerException.class, () -> normalizer.normalize(null));
    }
}
