package com.monada.speech.storage;

import com.monada.core.FrequencyVector;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface SpeechFeatureStore {
    void save(String sampleId, FrequencyVector vector) throws IOException;

    Optional<FrequencyVector> findBySampleId(String sampleId) throws IOException;

    List<StoredSpeechFeatureVector> findAll() throws IOException;
}
