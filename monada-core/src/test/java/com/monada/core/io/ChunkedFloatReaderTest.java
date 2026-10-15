package com.monada.core.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkedFloatReaderTest {

    private static final int CHUNK = ChunkedFloatReader.MAX_CHUNK_FLOATS;

    @Test
    void readsValuesSpanningSeveralChunks() throws IOException {
        float[] expected = sequence(CHUNK * 2 + 17);
        DataInputStream input = input(expected);

        float[] actual = new float[expected.length];
        new ChunkedFloatReader().readFloats(input, actual);

        assertArrayEquals(expected, actual);
        assertEquals(-1, input.read(), "reader must consume exactly the requested floats");
    }

    @Test
    void readsExactChunkMultipleAndConsecutiveVectors() throws IOException {
        float[] first = sequence(CHUNK);
        float[] second = sequence(3);
        DataInputStream input = input(concat(first, second));
        ChunkedFloatReader reader = new ChunkedFloatReader();

        float[] actualFirst = new float[first.length];
        float[] actualSecond = new float[second.length];
        reader.readFloats(input, actualFirst);
        reader.readFloats(input, actualSecond);

        assertArrayEquals(first, actualFirst);
        assertArrayEquals(second, actualSecond);
    }

    @Test
    void truncationMidChunkThrowsEofException() throws IOException {
        byte[] bytes = bytes(sequence(CHUNK + 10));
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 6);

        float[] values = new float[CHUNK + 10];
        assertThrows(EOFException.class, () -> new ChunkedFloatReader()
                .readFloats(new DataInputStream(new ByteArrayInputStream(truncated)), values));
    }

    @Test
    void truncationInFirstChunkThrowsEofException() throws IOException {
        byte[] truncated = Arrays.copyOf(bytes(sequence(8)), 8 * Float.BYTES - 1);

        assertThrows(EOFException.class, () -> new ChunkedFloatReader()
                .readFloats(new DataInputStream(new ByteArrayInputStream(truncated)), new float[8]));
    }

    @Test
    void preservesRawBitsIncludingNanPayloads() throws IOException {
        int[] bits = {
                0x7fc00000, 0x7fa00001, 0xffc12345, 0x7f800001, 0x00000001,
                0x80000000, 0x7f800000, 0xff800000, 0x3f800000
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            for (int bit : bits) {
                data.writeInt(bit);
            }
        }

        float[] values = new float[bits.length];
        new ChunkedFloatReader().readFloats(new DataInputStream(new ByteArrayInputStream(out.toByteArray())), values);

        for (int i = 0; i < bits.length; i++) {
            assertEquals(bits[i], Float.floatToRawIntBits(values[i]), "raw bits at index " + i);
        }
    }

    @Test
    void chunkSizeMatchesStoreParityTestBoundaries() {
        // FileFrequencyStoreReadParityTest and FileSpeechFeatureStoreReadParityTest use 4_095/4_096/4_097/8_193
        // dimensions to hit chunk boundaries; change them together with this constant.
        assertEquals(4_096, CHUNK);
    }

    @Test
    void emptyArrayReadsNothing() throws IOException {
        DataInputStream input = input(sequence(1));
        new ChunkedFloatReader().readFloats(input, new float[0]);
        assertEquals(Float.BYTES, input.available());
    }

    private static float[] sequence(int length) {
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = i * 0.5f - 7.25f;
        }
        return values;
    }

    private static float[] concat(float[] first, float[] second) {
        float[] values = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, values, first.length, second.length);
        return values;
    }

    private static DataInputStream input(float[] values) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(bytes(values)));
    }

    private static byte[] bytes(float[] values) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            for (float value : values) {
                data.writeFloat(value);
            }
        }
        return out.toByteArray();
    }
}
