package com.monada.evaluation.speech;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpeechScoreNormalizationTest {

    private final SpeechScoreNormalization normalization = new SpeechScoreNormalization();

    @Test
    void normalizesFiniteScoresWithStableMinMaxValues() {
        SpeechNormalizedScores scores = normalization.normalize(Map.of("b", 2.0, "a", 0.0, "c", 1.0));

        assertEquals(SpeechScoreNormalizationStatus.MIN_MAX, scores.status());
        assertEquals(0.0, scores.minimumRawScore());
        assertEquals(2.0, scores.maximumRawScore());
        assertEquals(0.0, scores.normalizedScoresBySampleId().get("a"));
        assertEquals(0.5, scores.normalizedScoresBySampleId().get("c"));
        assertEquals(1.0, scores.normalizedScoresBySampleId().get("b"));
    }

    @Test
    void marksConstantScoresAsHavingNoDiscrimination() {
        SpeechNormalizedScores scores = normalization.normalize(Map.of("a", 0.75, "b", 0.75));

        assertEquals(SpeechScoreNormalizationStatus.CONSTANT_NO_DISCRIMINATION, scores.status());
        assertEquals(0.0, scores.normalizedScoresBySampleId().get("a"));
        assertEquals(0.0, scores.normalizedScoresBySampleId().get("b"));
    }

    @Test
    void treatsSignedZeroScoresAsAConstantArm() {
        SpeechNormalizedScores scores = normalization.normalize(Map.of("negative", -0.0, "positive", 0.0));

        assertEquals(SpeechScoreNormalizationStatus.CONSTANT_NO_DISCRIMINATION, scores.status());
        assertEquals(0.0, scores.normalizedScoresBySampleId().get("negative"));
        assertEquals(0.0, scores.normalizedScoresBySampleId().get("positive"));
    }

    @Test
    void marksAnAbsentModalityWithoutInventingScores() {
        SpeechNormalizedScores scores = normalization.normalize(Map.of());

        assertEquals(SpeechScoreNormalizationStatus.NO_AVAILABLE_SCORES, scores.status());
        assertEquals(Map.of(), scores.normalizedScoresBySampleId());
    }

    @Test
    void rejectsNonFiniteRawScoresAndInvalidWeightProfiles() {
        assertThrows(IllegalArgumentException.class,
                () -> normalization.normalize(Map.of("a", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new SpeechFusionWeights(0.8, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new SpeechFusionWeights(-0.1, 1.1));
    }

    @Test
    void exposesTheFixedGlobalSweepWithoutQuerySpecificWeights() {
        assertEquals(List.of(
                        new SpeechFusionWeights(1.0, 0.0),
                        new SpeechFusionWeights(0.75, 0.25),
                        new SpeechFusionWeights(0.50, 0.50),
                        new SpeechFusionWeights(0.25, 0.75),
                        new SpeechFusionWeights(0.0, 1.0)),
                SpeechFusionWeights.defaultSweep());
    }
}
