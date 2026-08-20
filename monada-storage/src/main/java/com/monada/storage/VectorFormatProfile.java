package com.monada.storage;

import java.util.Objects;

/**
 * Immutable description of the bytes and index layout used by a vector segment.
 */
public record VectorFormatProfile(
        int formatVersion,
        ScalarType scalarType,
        ByteOrder byteOrder,
        Framing framing,
        int dimensions,
        int vectorIndexFormatVersion
) {

    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int CURRENT_INDEX_FORMAT_VERSION = 1;

    public VectorFormatProfile {
        Objects.requireNonNull(scalarType, "scalarType");
        Objects.requireNonNull(byteOrder, "byteOrder");
        Objects.requireNonNull(framing, "framing");
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be greater than zero");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero");
        }
        if (vectorIndexFormatVersion <= 0) {
            throw new IllegalArgumentException("vectorIndexFormatVersion must be greater than zero");
        }
    }

    public static VectorFormatProfile currentFixedRaw(int dimensions) {
        return new VectorFormatProfile(
                CURRENT_FORMAT_VERSION,
                ScalarType.FLOAT32,
                ByteOrder.BIG_ENDIAN,
                Framing.FIXED_RAW,
                dimensions,
                CURRENT_INDEX_FORMAT_VERSION);
    }

    public static VectorFormatProfile legacyLengthPrefixed(int dimensions) {
        return new VectorFormatProfile(
                CURRENT_FORMAT_VERSION,
                ScalarType.FLOAT32,
                ByteOrder.BIG_ENDIAN,
                Framing.LEGACY_LENGTH_PREFIXED,
                dimensions,
                CURRENT_INDEX_FORMAT_VERSION);
    }

    public void requireSupportedRuntime() {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported vectorFormatVersion " + formatVersion + "; supported: " + CURRENT_FORMAT_VERSION
                            + ". Rebuild the store explicitly to use this runtime.");
        }
        if (scalarType != ScalarType.FLOAT32) {
            throw new IllegalArgumentException(
                    "Unsupported vectorScalarType " + scalarType + "; supported: FLOAT32"
                            + ". Rebuild the store explicitly to use this runtime.");
        }
        if (byteOrder != ByteOrder.BIG_ENDIAN) {
            throw new IllegalArgumentException(
                    "Unsupported vectorByteOrder " + byteOrder + "; supported: BIG_ENDIAN"
                            + ". Rebuild the store explicitly to use this runtime.");
        }
        if (vectorIndexFormatVersion != CURRENT_INDEX_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported vectorIndexFormatVersion " + vectorIndexFormatVersion + "; supported: "
                            + CURRENT_INDEX_FORMAT_VERSION + ". Rebuild the store explicitly to use this runtime.");
        }
    }

    public long frameSizeInBytes() {
        long valueBytes = (long) dimensions * Float.BYTES;
        return framing == Framing.FIXED_RAW ? valueBytes : Integer.BYTES + valueBytes;
    }

    public enum ScalarType {
        FLOAT32
    }

    public enum ByteOrder {
        BIG_ENDIAN
    }

    public enum Framing {
        FIXED_RAW,
        LEGACY_LENGTH_PREFIXED
    }
}
