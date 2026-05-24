package com.monada.encoder;

import com.monada.core.FrequencyVector;

public interface FrequencyEncoder {

    FrequencyVector encode(String content);

    default FrequencyVector encode(WeightedText weightedText) {
        throw new UnsupportedOperationException("Weighted encoding not supported");
    }
}