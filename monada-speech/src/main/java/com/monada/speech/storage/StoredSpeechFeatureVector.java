package com.monada.speech.storage;

import com.monada.core.FrequencyVector;

public record StoredSpeechFeatureVector(
        String sampleId,
        FrequencyVector vector
) {
}
