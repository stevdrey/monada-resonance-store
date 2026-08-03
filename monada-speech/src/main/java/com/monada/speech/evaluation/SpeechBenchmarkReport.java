package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A mode-labeled speech-benchmark report.
 *
 * <p>Wraps an underlying {@link SpeechEvaluationReport} and tags it with the
 * {@link SpeechBenchmarkMode} that produced it plus the minimum metadata needed to
 * compare runs over time: a human-readable {@code label}, the {@code corpusSize}
 * (number of stored samples scanned), and the evaluation {@code k}.
 *
 * <p>{@link #render()} is deterministic and contains no wall-clock timestamps, so its
 * output can be saved and diffed across runs. The mode is shown prominently so protected
 * (CI-enforced) output is never confused with exploratory (local real-data) output.
 *
 * @param mode             benchmark mode that produced this report
 * @param label            human-readable label for the corpus/run (non-blank)
 * @param corpusSize       number of stored samples available to the retriever (non-negative)
 * @param k                evaluation k used to compute the metrics (positive)
 * @param evaluationReport underlying deterministic evaluation report
 */
public record SpeechBenchmarkReport(
        SpeechBenchmarkMode mode,
        String label,
        int corpusSize,
        int k,
        SpeechEvaluationReport evaluationReport
) {
    public SpeechBenchmarkReport {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (corpusSize < 0) {
            throw new IllegalArgumentException("corpusSize must be non-negative: " + corpusSize);
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        Objects.requireNonNull(evaluationReport, "evaluationReport");
    }

    /** Number of queries evaluated, taken from the underlying report. */
    public int queryCount() {
        return evaluationReport.queryCount();
    }

    /**
     * Renders a deterministic, human-readable benchmark report. The mode banner
     * makes protected and exploratory output unambiguous, and the metadata block
     * carries the minimum comparable fields (corpus size, query count, k).
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Monada Speech Benchmark Report\n");
        sb.append("==============================\n");
        sb.append("Mode: ").append(mode.name()).append('\n');
        if (mode == SpeechBenchmarkMode.PROTECTED) {
            sb.append("Policy: protected generated-fixture baseline (enforced in CI)\n");
        } else {
            sb.append("Policy: exploratory local real-data run (NOT enforced in CI)\n");
        }
        sb.append("Label: ").append(label).append('\n');
        sb.append("Corpus size: ").append(corpusSize).append('\n');
        sb.append("Query count: ").append(queryCount()).append('\n');
        sb.append("k: ").append(k).append("\n\n");

        sb.append("Aggregate metrics\n");
        sb.append("-----------------\n");
        appendScalar(sb, "Precision@k", evaluationReport.precisionAtK());
        appendScalar(sb, "Recall@k", evaluationReport.recallAtK());
        appendScalar(sb, "Hit rate@k", evaluationReport.hitRateAtK());
        appendScalar(sb, "MRR", evaluationReport.meanReciprocalRank());
        sb.append('\n');

        appendGroupedByCondition(sb, evaluationReport.metricsByCondition());
        appendGroupedByTaskType(sb, evaluationReport.metricsByTaskType());

        if (!evaluationReport.queryResults().isEmpty()) {
            sb.append("Per-query diagnostics\n");
            sb.append("---------------------\n");
            for (SpeechQueryEvaluationResult r : evaluationReport.queryResults()) {
                sb.append(r.queryId()).append(": retrieved=").append(r.retrievedSampleIds());
                sb.append(" missed=").append(r.missedRelevantSampleIds());
                sb.append(String.format(Locale.ROOT, " hit=%b rr=%.4f", r.hitAtK(), r.reciprocalRank()));
                sb.append('\n');
                appendAcousticDiagnostic(sb, r.acousticDiagnostic());
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private static void appendAcousticDiagnostic(
            StringBuilder sb,
            SpeechQueryAcousticDiagnostic diagnostic
    ) {
        if (diagnostic == null) {
            return;
        }
        var retrieval = diagnostic.retrieval();
        sb.append(String.format(
                Locale.ROOT,
                "  scan: vectors=%d orphan=%d incompatible=%d filtered=%d scored=%d ties=%d%n",
                retrieval.scannedVectorCount(),
                retrieval.orphanVectorCount(),
                retrieval.incompatibleDimensionCount(),
                retrieval.metadataFilteredCandidateCount(),
                retrieval.scoredCandidateCount(),
                retrieval.tieCount()));
        sb.append(String.format(
                Locale.ROOT,
                "  scores: min=%.4f mean=%.4f max=%.4f top=%.4f top-gap=%.4f%n",
                retrieval.minimumScore(),
                retrieval.meanScore(),
                retrieval.maximumScore(),
                retrieval.topResultScore(),
                retrieval.topScoreGap()));
        for (SpeechRelevantCandidateDiagnostic relevant : diagnostic.relevantCandidates()) {
            sb.append("  relevant ").append(relevant.sampleId());
            sb.append(": status=").append(relevant.status().name());
            if (!relevant.failedFilters().isEmpty()) {
                sb.append(" filters=").append(relevant.failedFilters());
            }
            if (relevant.rank() > 0) {
                sb.append(String.format(
                        Locale.ROOT,
                        " rank=%d score=%.4f",
                        relevant.rank(),
                        relevant.score()));
            }
            sb.append('\n');
        }
    }

    private static void appendGroupedByCondition(
            StringBuilder sb, Map<SpeechCondition, SpeechEvaluationMetrics> metrics) {
        if (metrics.isEmpty()) {
            return;
        }
        sb.append("Metrics by condition\n");
        sb.append("--------------------\n");
        for (Map.Entry<SpeechCondition, SpeechEvaluationMetrics> e : metrics.entrySet()) {
            appendGroupedLine(sb, e.getKey().name(), e.getValue());
        }
        sb.append('\n');
    }

    private static void appendGroupedByTaskType(
            StringBuilder sb, Map<SpeechTaskType, SpeechEvaluationMetrics> metrics) {
        if (metrics.isEmpty()) {
            return;
        }
        sb.append("Metrics by task type\n");
        sb.append("--------------------\n");
        for (Map.Entry<SpeechTaskType, SpeechEvaluationMetrics> e : metrics.entrySet()) {
            appendGroupedLine(sb, e.getKey().name(), e.getValue());
        }
        sb.append('\n');
    }

    private static void appendGroupedLine(StringBuilder sb, String name, SpeechEvaluationMetrics m) {
        sb.append(String.format(
                Locale.ROOT,
                "%s (n=%d): P=%.4f R=%.4f Hit=%.4f MRR=%.4f\n",
                name, m.queryCount(), m.precisionAtK(), m.recallAtK(), m.hitRateAtK(), m.mrr()));
    }

    private static void appendScalar(StringBuilder sb, String label, double value) {
        sb.append(String.format(Locale.ROOT, "%s: %.4f\n", label, value));
    }
}
