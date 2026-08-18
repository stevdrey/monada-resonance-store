package com.monada.evaluation.speech;

import java.util.List;
import java.util.Objects;

/** Top-K results and metrics produced by one modality for a paired query. */
public record SpeechModalityQueryResult(
        List<SpeechModalityRankedResult> topResults,
        SpeechModalityQueryMetrics metrics
) {
    public SpeechModalityQueryResult {
        Objects.requireNonNull(topResults, "topResults");
        Objects.requireNonNull(metrics, "metrics");
        topResults = List.copyOf(topResults);
        if (topResults.size() != metrics.retrievedCount()) {
            throw new IllegalArgumentException(
                    "topResults.size() must equal metrics.retrievedCount(): "
                            + topResults.size() + " != " + metrics.retrievedCount());
        }
    }
}
