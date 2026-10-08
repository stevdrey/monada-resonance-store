package com.monada.core.execution;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Usage counter with provenance. A value of {@code 0} with {@code REPORTED} or {@code ESTIMATED}
 * provenance is a known zero. {@code UNKNOWN} carries no value; an absent counter is "not measured".
 * Neither is ever treated as zero.
 */
public record UsageCounter(UsageKind kind, OptionalLong value, UsageProvenance provenance, String source) {
    public UsageCounter {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
        source = Validation.opaque(source, "usage source");
        if (provenance == UsageProvenance.UNKNOWN) {
            if (value.isPresent()) {
                throw new IllegalArgumentException("UNKNOWN usage must not carry a value");
            }
        } else if (value.isEmpty()) {
            throw new IllegalArgumentException(provenance + " usage requires a value");
        } else if (value.getAsLong() < 0) {
            throw new IllegalArgumentException("usage value must be non-negative");
        }
    }

    public static UsageCounter reported(UsageKind kind, long value, String source) {
        return new UsageCounter(kind, OptionalLong.of(value), UsageProvenance.REPORTED, source);
    }

    public static UsageCounter estimated(UsageKind kind, long value, String source) {
        return new UsageCounter(kind, OptionalLong.of(value), UsageProvenance.ESTIMATED, source);
    }

    public static UsageCounter unknown(UsageKind kind, String source) {
        return new UsageCounter(kind, OptionalLong.empty(), UsageProvenance.UNKNOWN, source);
    }
}
