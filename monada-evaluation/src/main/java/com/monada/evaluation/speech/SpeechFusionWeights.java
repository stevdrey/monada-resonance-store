package com.monada.evaluation.speech;

import java.util.List;
import java.util.Locale;

/** Fixed global weights used by the evaluation-only transcript/acoustic score sweep. */
public record SpeechFusionWeights(double transcriptWeight, double acousticWeight) {

    private static final double SUM_TOLERANCE = 1.0e-12;

    public SpeechFusionWeights {
        if (!Double.isFinite(transcriptWeight) || !Double.isFinite(acousticWeight)) {
            throw new IllegalArgumentException("fusion weights must be finite");
        }
        if (transcriptWeight < 0.0 || acousticWeight < 0.0) {
            throw new IllegalArgumentException("fusion weights must be non-negative");
        }
        if (Math.abs(transcriptWeight + acousticWeight - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("fusion weights must sum to 1.0");
        }
    }

    public static List<SpeechFusionWeights> defaultSweep() {
        return List.of(
                new SpeechFusionWeights(1.00, 0.00),
                new SpeechFusionWeights(0.75, 0.25),
                new SpeechFusionWeights(0.50, 0.50),
                new SpeechFusionWeights(0.25, 0.75),
                new SpeechFusionWeights(0.00, 1.00));
    }

    public boolean isTranscriptControl() {
        return transcriptWeight == 1.0 && acousticWeight == 0.0;
    }

    public boolean isAcousticControl() {
        return transcriptWeight == 0.0 && acousticWeight == 1.0;
    }

    public boolean isHybrid() {
        return !isTranscriptControl() && !isAcousticControl();
    }

    public String label() {
        return String.format(Locale.ROOT, "T=%.2f A=%.2f", transcriptWeight, acousticWeight);
    }
}
