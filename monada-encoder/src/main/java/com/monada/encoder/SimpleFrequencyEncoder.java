package com.monada.encoder;

import com.monada.core.FrequencyVector;

import java.util.Random;

public class SimpleFrequencyEncoder implements FrequencyEncoder {

    private final int dimensions;
    private final Random random = new Random();

    public SimpleFrequencyEncoder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public FrequencyVector encode(String content) {

        float[] vector = new float[dimensions];

        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextFloat();
        }

        return new FrequencyVector(vector);
    }
}