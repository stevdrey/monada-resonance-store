package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Aggregate paired-comparison metrics for all queries or one metadata group. */
public record SpeechModalitySummary(
        SpeechEvaluationMetrics transcriptMetrics,
        SpeechEvaluationMetrics acousticMetrics,
        Map<SpeechModalityOutcome, Integer> outcomeCounts,
        double outcomeAgreementRate,
        double meanTopKJaccard
) {
    public SpeechModalitySummary {
        Objects.requireNonNull(transcriptMetrics, "transcriptMetrics");
        Objects.requireNonNull(acousticMetrics, "acousticMetrics");
        if (transcriptMetrics.queryCount() != acousticMetrics.queryCount()) {
            throw new IllegalArgumentException("transcript and acoustic query counts must match");
        }
        Objects.requireNonNull(outcomeCounts, "outcomeCounts");
        var copiedCounts = new EnumMap<SpeechModalityOutcome, Integer>(SpeechModalityOutcome.class);
        for (SpeechModalityOutcome outcome : SpeechModalityOutcome.values()) {
            int count = outcomeCounts.getOrDefault(outcome, 0);
            if (count < 0) {
                throw new IllegalArgumentException("outcome counts must be non-negative");
            }
            copiedCounts.put(outcome, count);
        }
        int total = copiedCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total != transcriptMetrics.queryCount()) {
            throw new IllegalArgumentException(
                    "outcome count total must equal query count: " + total + " != " + transcriptMetrics.queryCount());
        }
        outcomeCounts = Collections.unmodifiableMap(copiedCounts);
        if (!Double.isFinite(outcomeAgreementRate) || outcomeAgreementRate < 0.0 || outcomeAgreementRate > 1.0) {
            throw new IllegalArgumentException("outcomeAgreementRate must be finite and between 0.0 and 1.0");
        }
        if (!Double.isFinite(meanTopKJaccard) || meanTopKJaccard < 0.0 || meanTopKJaccard > 1.0) {
            throw new IllegalArgumentException("meanTopKJaccard must be finite and between 0.0 and 1.0");
        }
    }

    public int outcomeCount(SpeechModalityOutcome outcome) {
        return outcomeCounts.getOrDefault(Objects.requireNonNull(outcome, "outcome"), 0);
    }

    public double outcomeRate(SpeechModalityOutcome outcome) {
        if (transcriptMetrics.queryCount() == 0) {
            return 0.0;
        }
        return (double) outcomeCount(outcome) / transcriptMetrics.queryCount();
    }
}
