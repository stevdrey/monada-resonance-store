package com.monada.speech.encoder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SpeechFeatureEncodingReportTest {

    @Test
    void rejectsFailureGroupWhoseReasonDoesNotMatchItsMapKey() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechFeatureEncodingReport(
                1,
                0,
                1,
                0,
                Map.of("silent audio", new SpeechFeatureEncodingFailureGroup(
                        "unsupported audio format", 1, List.of("sample-1"))),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()));
    }
}
