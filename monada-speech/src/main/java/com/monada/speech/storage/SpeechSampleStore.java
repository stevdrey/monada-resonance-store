package com.monada.speech.storage;

import com.monada.speech.domain.SpeechSample;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface SpeechSampleStore {
    void save(SpeechSample sample) throws IOException;

    Optional<SpeechSample> findById(String sampleId) throws IOException;

    List<SpeechSample> findBySpeakerId(String speakerId) throws IOException;

    List<SpeechSample> findAll() throws IOException;
}
