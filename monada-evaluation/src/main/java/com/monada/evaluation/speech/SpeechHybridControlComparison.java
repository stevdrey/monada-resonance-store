package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

/** Stateless control-arm comparisons shared by the fixed robustness study and the missing-modality policy study. */
final class SpeechHybridControlComparison {

    private static final SpeechHybridProfileEvaluator PROFILE_EVALUATOR = new SpeechHybridProfileEvaluator();

    private SpeechHybridControlComparison() {
    }

    static SpeechHybridBetterControl strongerControl(SpeechHybridQueryContext context) {
        SpeechHybridComparison comparison = PROFILE_EVALUATOR.compare(
                context.transcriptControl().metrics(), context.acousticControl().metrics());
        return switch (comparison) {
            case WIN -> SpeechHybridBetterControl.TRANSCRIPT;
            case REGRESS -> SpeechHybridBetterControl.ACOUSTIC;
            case MAINTAIN -> SpeechHybridBetterControl.TIED;
        };
    }

    static SpeechModalityQueryMetrics strongerMetrics(SpeechHybridQueryContext context, SpeechHybridBetterControl control) {
        return control == SpeechHybridBetterControl.ACOUSTIC ? context.acousticControl().metrics()
                : context.transcriptControl().metrics();
    }

    static boolean belowStrongerGroupControl(
            SpeechEvaluationMetrics candidate,
            SpeechEvaluationMetrics transcript,
            SpeechEvaluationMetrics acoustic
    ) {
        double tolerance = SpeechHybridProfileEvaluator.COMPARISON_TOLERANCE;
        return candidate.precisionAtK() + tolerance < Math.max(transcript.precisionAtK(), acoustic.precisionAtK())
                || candidate.recallAtK() + tolerance < Math.max(transcript.recallAtK(), acoustic.recallAtK())
                || candidate.hitRateAtK() + tolerance < Math.max(transcript.hitRateAtK(), acoustic.hitRateAtK())
                || candidate.mrr() + tolerance < Math.max(transcript.mrr(), acoustic.mrr());
    }

    /** Accumulates per-query metrics into a group mean. */
    static final class MeanMetrics {
        private double precision;
        private double recall;
        private double hit;
        private double reciprocalRank;
        private int count;

        void add(SpeechModalityQueryMetrics metrics) {
            precision += metrics.precisionAtK();
            recall += metrics.recallAtK();
            hit += metrics.hitAtK() ? 1.0 : 0.0;
            reciprocalRank += metrics.reciprocalRank();
            count++;
        }

        SpeechEvaluationMetrics mean() {
            if (count == 0) {
                throw new IllegalStateException("no metrics were accumulated");
            }
            return new SpeechEvaluationMetrics(precision / count, recall / count, hit / count, reciprocalRank / count, count);
        }
    }
}
