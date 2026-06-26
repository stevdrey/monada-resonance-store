package com.monada.speech.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Protected expected metrics for a deterministic generated-fixture speech benchmark.
 *
 * <p>This record encodes the threshold policy for the {@link SpeechBenchmarkMode#PROTECTED}
 * workflow: the structural shape of the run (corpus size, query count, k) must match exactly
 * and each aggregate metric must match the pinned value within {@code tolerance}. The pinned
 * values are intentional regression guards; a legitimate change that alters them must update
 * this baseline in the same commit with a documented rationale.
 *
 * @param expectedCorpusSize  expected number of stored samples scanned (non-negative)
 * @param expectedQueryCount  expected number of evaluated queries (non-negative)
 * @param k                   evaluation k the baseline was measured at (positive)
 * @param expectedPrecisionAtK pinned aggregate precision at k
 * @param expectedRecallAtK    pinned aggregate recall at k
 * @param expectedHitRateAtK   pinned aggregate hit rate at k
 * @param expectedMrr          pinned aggregate mean reciprocal rank
 * @param tolerance           absolute tolerance applied to each metric comparison (non-negative)
 */
public record SpeechBenchmarkBaseline(
        int expectedCorpusSize,
        int expectedQueryCount,
        int k,
        double expectedPrecisionAtK,
        double expectedRecallAtK,
        double expectedHitRateAtK,
        double expectedMrr,
        double tolerance
) {
    public SpeechBenchmarkBaseline {
        if (expectedCorpusSize < 0) {
            throw new IllegalArgumentException("expectedCorpusSize must be non-negative");
        }
        if (expectedQueryCount < 0) {
            throw new IllegalArgumentException("expectedQueryCount must be non-negative");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        requireFinite(expectedPrecisionAtK, "expectedPrecisionAtK");
        requireFinite(expectedRecallAtK, "expectedRecallAtK");
        requireFinite(expectedHitRateAtK, "expectedHitRateAtK");
        requireFinite(expectedMrr, "expectedMrr");
        if (!Double.isFinite(tolerance) || tolerance < 0.0) {
            throw new IllegalArgumentException("tolerance must be finite and non-negative: " + tolerance);
        }
    }

    /**
     * Compares a benchmark report against this baseline, including the run's actual k.
     *
     * @param report the labeled report produced by a protected benchmark run
     * @return a comparison describing whether every field matched within tolerance
     */
    public SpeechBenchmarkComparison compare(SpeechBenchmarkReport report) {
        return compare(report.evaluationReport(), report.corpusSize(), report.k());
    }

    /**
     * Compares an actual evaluation report against this baseline.
     *
     * @param report           the evaluation report produced by a protected benchmark run
     * @param actualCorpusSize number of stored samples scanned during the run
     * @param actualK          the evaluation k the run was measured at
     * @return a comparison describing whether every field matched within tolerance
     */
    public SpeechBenchmarkComparison compare(SpeechEvaluationReport report, int actualCorpusSize, int actualK) {
        List<String> mismatches = new ArrayList<>();
        checkExact("corpusSize", expectedCorpusSize, actualCorpusSize, mismatches);
        checkExact("k", k, actualK, mismatches);
        checkExact("queryCount", expectedQueryCount, report.queryCount(), mismatches);
        checkMetric("precisionAtK", expectedPrecisionAtK, report.precisionAtK(), mismatches);
        checkMetric("recallAtK", expectedRecallAtK, report.recallAtK(), mismatches);
        checkMetric("hitRateAtK", expectedHitRateAtK, report.hitRateAtK(), mismatches);
        checkMetric("meanReciprocalRank", expectedMrr, report.meanReciprocalRank(), mismatches);
        return new SpeechBenchmarkComparison(mismatches.isEmpty(), mismatches);
    }

    private void checkExact(String name, int expected, int actual, List<String> mismatches) {
        if (expected != actual) {
            mismatches.add(String.format(Locale.ROOT, "%s: expected %d but was %d", name, expected, actual));
        }
    }

    private void checkMetric(String name, double expected, double actual, List<String> mismatches) {
        double delta = Math.abs(expected - actual);
        if (delta > tolerance) {
            mismatches.add(String.format(
                    Locale.ROOT,
                    "%s: expected %.6f but was %.6f (delta %.3e exceeds tolerance %.3e)",
                    name, expected, actual, delta, tolerance));
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
