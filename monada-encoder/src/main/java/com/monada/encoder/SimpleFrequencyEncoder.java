package com.monada.encoder;

import com.monada.core.FrequencyVector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class SimpleFrequencyEncoder implements FrequencyEncoder {

    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private final int dimensions;

    public SimpleFrequencyEncoder(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero");
        }
        this.dimensions = dimensions;
    }

    @Override
    public FrequencyVector encode(String content) {
        float[] vector = new float[dimensions];
        String[] tokens = content.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+");

        for (String token : tokens) {
            if (!token.isBlank()) {
                addToken(vector, token);
            }
        }

        normalize(vector);
        return new FrequencyVector(vector);
    }

    private void addToken(float[] vector, String token) {
        byte[] digest = digest(token);
        int bucket = Math.floorMod(toInt(digest, 0), dimensions);
        float sign = Math.floorMod(toInt(digest, 4), 2) == 0 ? 1.0f : -1.0f;
        vector[bucket] += sign;
    }

    private byte[] digest(String token) {
        MessageDigest digest = DIGEST.get();
        digest.reset();
        return digest.digest(token.getBytes(StandardCharsets.UTF_8));
    }

    private int toInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private void normalize(float[] vector) {
        double magnitude = 0.0;
        for (float value : vector) {
            magnitude += value * value;
        }
        if (magnitude == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(magnitude);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}