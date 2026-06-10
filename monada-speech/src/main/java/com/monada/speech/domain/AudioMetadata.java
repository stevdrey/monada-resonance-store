package com.monada.speech.domain;

import java.util.Objects;

public record AudioMetadata(
        int sampleRate,
        int channels,
        long durationMs,
        String sha256
) {
    public AudioMetadata {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative: " + durationMs);
        }
        Objects.requireNonNull(sha256, "sha256");
        if (sha256.isBlank()) {
            throw new IllegalArgumentException("sha256 must not be blank");
        }
    }
}
