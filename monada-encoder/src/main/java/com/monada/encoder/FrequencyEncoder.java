package com.monada.encoder;

import com.monada.core.FrequencyVector;

public interface FrequencyEncoder {

    FrequencyVector encode(String content);
}