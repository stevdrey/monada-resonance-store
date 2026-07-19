package com.monada.speech.encoder;

/**
 * Coverage counts for one speech-sample metadata group during a batch encoding run.
 */
public record SpeechFeatureEncodingCoverage(
        int totalSamples,
        int encodedSamples,
        int existingFeatureSamples,
        int failedSamples
) {
    public SpeechFeatureEncodingCoverage {
        if (totalSamples < 0 || encodedSamples < 0 || existingFeatureSamples < 0 || failedSamples < 0) {
            throw new IllegalArgumentException("coverage counts must be non-negative");
        }
        if (encodedSamples + existingFeatureSamples + failedSamples != totalSamples) {
            throw new IllegalArgumentException(
                    "encoded, existing, and failed samples must add up to total samples");
        }
    }

    /** Samples with an available feature vector after this run. */
    public int coveredSamples() {
        return encodedSamples + existingFeatureSamples;
    }
}
