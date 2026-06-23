package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate report for a speech retrieval evaluation run.
 *
 * @param queryCount         total number of queries evaluated
 * @param precisionAtK       aggregate precision at k
 * @param recallAtK          aggregate recall at k
 * @param hitRateAtK         aggregate hit rate at k
 * @param meanReciprocalRank aggregate mean reciprocal rank
 * @param queryResults       per-query results (empty if diagnostics were disabled)
 * @param metricsByCondition metrics grouped by query sample condition
 * @param metricsByTaskType  metrics grouped by query sample task type
 */
public record SpeechEvaluationReport(
        int queryCount,
        double precisionAtK,
        double recallAtK,
        double hitRateAtK,
        double meanReciprocalRank,
        List<SpeechQueryEvaluationResult> queryResults,
        Map<SpeechCondition, SpeechEvaluationMetrics> metricsByCondition,
        Map<SpeechTaskType, SpeechEvaluationMetrics> metricsByTaskType
) {
    public SpeechEvaluationReport {
        if (queryCount < 0) {
            throw new IllegalArgumentException("queryCount must be non-negative");
        }
        if (!Double.isFinite(precisionAtK)) {
            throw new IllegalArgumentException("precisionAtK must be finite");
        }
        if (!Double.isFinite(recallAtK)) {
            throw new IllegalArgumentException("recallAtK must be finite");
        }
        if (!Double.isFinite(hitRateAtK)) {
            throw new IllegalArgumentException("hitRateAtK must be finite");
        }
        if (!Double.isFinite(meanReciprocalRank)) {
            throw new IllegalArgumentException("meanReciprocalRank must be finite");
        }
        Objects.requireNonNull(queryResults, "queryResults");
        Objects.requireNonNull(metricsByCondition, "metricsByCondition");
        Objects.requireNonNull(metricsByTaskType, "metricsByTaskType");
        queryResults = List.copyOf(queryResults);
        metricsByCondition = Collections.unmodifiableMap(new EnumMap<>(metricsByCondition));
        metricsByTaskType = Collections.unmodifiableMap(new EnumMap<>(metricsByTaskType));
    }
}
