package com.monada.core.io;

import java.io.DataInput;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Shared store codec, not a supported consumer API. The package is exported because {@code monada-core} is an
 * automatic module, but it exists only for the storage and speech vector stores and may change in any {@code 0.x}
 * release; see the ADR 0001 amendment and {@code docs/specs/library-consumption.md}.
 *
 * <p>Reads big-endian floats in bulk chunks instead of one {@code readFloat()} call per float. It is shared by the
 * vector stores of {@code monada-storage} and {@code monada-speech} (Issues #130, #132, #134); it lives in
 * {@code monada-core} because that is the only module both depend on.
 *
 * <p>The big-endian view copies raw bits, so values are bit-identical to {@link RandomAccessFile#readFloat()},
 * including NaN payloads, and a short read still surfaces as {@link EOFException} so callers keep their own error
 * messages. The scratch is reused across reads and sized to the largest chunk needed so far, at most
 * {@value #MAX_CHUNK_FLOATS} floats (16 KiB), so a single small read does not allocate a full chunk and an unused
 * reader allocates nothing. Instances are not thread-safe.
 */
public final class ChunkedFloatReader {

    /** Floats decoded per bulk read; bounds the scratch buffer to 16 KiB regardless of dimensions. */
    static final int MAX_CHUNK_FLOATS = 4_096;

    private byte[] bytes = new byte[0];
    private FloatBuffer floats = view(bytes);

    /** Creates a reader with an empty scratch; the first read allocates it. */
    public ChunkedFloatReader() {
    }

    /**
     * Fills {@code values} with the next {@code values.length} big-endian floats from {@code input}.
     *
     * @param input  source positioned at the first float to read
     * @param values destination, filled from index 0
     * @throws EOFException if {@code input} ends before all values are read
     */
    public void readFloats(DataInput input, float[] values) throws IOException {
        ensureCapacity(Math.min(values.length, MAX_CHUNK_FLOATS));
        for (int start = 0; start < values.length; start += MAX_CHUNK_FLOATS) {
            int count = Math.min(MAX_CHUNK_FLOATS, values.length - start);
            input.readFully(bytes, 0, count * Float.BYTES);
            floats.get(0, values, start, count);
        }
    }

    /** Current scratch size in bytes; visible for tests. */
    int scratchBytes() {
        return bytes.length;
    }

    private void ensureCapacity(int chunkFloats) {
        if (bytes.length < chunkFloats * Float.BYTES) {
            bytes = new byte[chunkFloats * Float.BYTES];
            floats = view(bytes);
        }
    }

    private static FloatBuffer view(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer();
    }
}
