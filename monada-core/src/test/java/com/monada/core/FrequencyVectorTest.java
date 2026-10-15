package com.monada.core;

import com.monada.core.io.ChunkedFloatReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void constructorAndAccessorCopyDefensively() {
        float[] source = {1f, 2f};
        FrequencyVector a = new FrequencyVector(source);
        source[0] = 9f;
        a.values()[1] = 9f;
        assertArrayEquals(new float[]{1f, 2f}, a.values());
    }

    @Test
    void nullValuesAreRejected() {
        assertThrows(NullPointerException.class, () -> new FrequencyVector(null));
    }

    @Test
    void toStringFormatIsUnchanged() {
        assertEquals("FrequencyVector[1.0, 2.0]", new FrequencyVector(new float[]{1f, 2f}).toString());
    }

    @Test
    void isAFinalClassThatIsNotARecord() {
        assertTrue(Modifier.isFinal(FrequencyVector.class.getModifiers()));
        assertFalse(FrequencyVector.class.isRecord());
    }

    // --- read (Issue #135): no-copy construction for the vector stores ---

    /**
     * Big-endian raw bits. Writes with {@code writeInt}, not {@code writeFloat}: {@code writeFloat} goes through
     * {@code floatToIntBits}, which collapses every NaN to the canonical {@code 0x7fc00000}.
     */
    private static byte[] bigEndian(int[] bits) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            for (int b : bits) {
                out.writeInt(b);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] bigEndian(float[] values) throws IOException {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Float.floatToRawIntBits(values[i]);
        }
        return bigEndian(bits);
    }

    /** Seeded raw bit patterns with NaN payloads (quiet and signalling), -0.0, infinity and a subnormal. */
    private static int[] rawBitPatterns(int dimensions) {
        var random = new Random(dimensions);
        int[] bits = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            bits[i] = random.nextInt();
        }
        int[] specials = {0x7fc00001, 0x7f800001, 0xffc12345, 0x80000000, 0x7f800000, 0x00000001};
        for (int i = 0; i < specials.length && i < dimensions; i++) {
            bits[(int) ((long) i * dimensions / specials.length)] = specials[i];
        }
        return bits;
    }

    @Test
    void readIsBitIdenticalToTheWrittenBitsAcrossChunkBoundaries() throws IOException {
        var reader = new ChunkedFloatReader();
        for (int dimensions : new int[]{1, 3, 6, 4_095, 4_096, 4_097, 10_000}) {
            int[] expected = rawBitPatterns(dimensions);
            var input = new DataInputStream(new ByteArrayInputStream(bigEndian(expected)));

            var vector = FrequencyVector.read(reader, input, dimensions);
            float[] actual = vector.values();
            assertEquals(dimensions, vector.dimensions());
            for (int i = 0; i < dimensions; i++) {
                assertEquals(expected[i], Float.floatToRawIntBits(actual[i]),
                        "dimensions=" + dimensions + " index=" + i);
            }
        }
    }

    @Test
    void readKeepsNanPayloadsThatWriteFloatWouldCanonicalize() throws IOException {
        int payload = 0x7fc00001;
        var input = new DataInputStream(new ByteArrayInputStream(bigEndian(new int[]{payload})));
        var vector = FrequencyVector.read(new ChunkedFloatReader(), input, 1);
        assertEquals(payload, Float.floatToRawIntBits(vector.values()[0]));
        assertNotEquals(payload, Float.floatToIntBits(Float.intBitsToFloat(payload)), "guard: canonical form differs");
    }

    @Test
    void readEqualsAConstructedVectorAndStaysImmutable() throws IOException {
        float[] expected = {1.5f, -2f, 3.25f};
        var input = new DataInputStream(new ByteArrayInputStream(bigEndian(expected)));
        var vector = FrequencyVector.read(new ChunkedFloatReader(), input, expected.length);
        assertEquals(new FrequencyVector(expected), vector);
        assertEquals(new FrequencyVector(expected).hashCode(), vector.hashCode());
        vector.values()[0] = 99f;
        assertArrayEquals(expected, vector.values());
    }

    @Test
    void readReadsOnlyTheRequestedFloats() throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(bigEndian(new float[]{1f, 2f, 3f})));
        var reader = new ChunkedFloatReader();
        assertArrayEquals(new float[]{1f, 2f}, FrequencyVector.read(reader, input, 2).values());
        assertArrayEquals(new float[]{3f}, FrequencyVector.read(reader, input, 1).values());
    }

    @Test
    void readPropagatesEofOnTruncatedInput() throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(bigEndian(new float[]{1f, 2f})));
        assertThrows(EOFException.class, () -> FrequencyVector.read(new ChunkedFloatReader(), input, 3));
    }

    @Test
    void readRejectsNonPositiveDimensionsAndNullArguments() {
        var input = new DataInputStream(new ByteArrayInputStream(new byte[8]));
        var reader = new ChunkedFloatReader();
        var zero = assertThrows(IllegalArgumentException.class, () -> FrequencyVector.read(reader, input, 0));
        assertEquals("FrequencyVector must have at least one dimension", zero.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FrequencyVector.read(reader, input, -1));
        assertThrows(NullPointerException.class, () -> FrequencyVector.read(null, input, 1));
        assertThrows(NullPointerException.class, () -> FrequencyVector.read(reader, null, 1));
    }
}
