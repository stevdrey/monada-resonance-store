package com.monada.evaluation.execution;

import com.monada.evaluation.execution.RecallStressReport.Hit;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import com.monada.evaluation.execution.ThresholdSweepReport.QuerySweep;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation-only sweep of host-owned relevance thresholds over experience recall (issue #123). It consumes the
 * fixed rankings and hand judgments of the #122 stress set and applies each declared threshold AFTER recall, as a
 * host would: nothing is configured on {@code ExecutionMemory.recall}, whose default threshold, projection and limit
 * stay unchanged.
 *
 * <p>Filter semantics: a hit is kept when {@code similarity >= threshold} (inclusive, exact {@code double} comparison,
 * no epsilon), then the first {@code deepestK} kept hits are read. Recall ranks by similarity descending with ties by
 * atom id, so the kept hits are a prefix of the ranking: their order never changes and hits tied at the threshold
 * are kept or dropped together. Threshold {@code 0.0} is the current behaviour, zero-similarity fill included.
 */
public final class ThresholdSweep {
    /**
     * The declared grid: the {@code 0.0} baseline and candidates around the similarities observed in #122 — relevant
     * paraphrase hits from about 0.06 to 0.18 and no-relevant neighbours up to about 0.21 (Q12), 0.25 (S17),
     * 0.33 (S19) and 0.40 (S18). Fixed before the sweep ran; never derived from its output.
     */
    public static final List<Double> GRID = List.of(0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45);

    /** Minimum number of candidates besides the {@code 0.0} baseline. */
    static final int MIN_CANDIDATES = 5;

    private ThresholdSweep() {
    }

    /** Runs the #122 stress evaluation in fresh stores below {@code workDir} and sweeps {@link #GRID} over it. */
    public static ThresholdSweepReport evaluate(RecallStressFixture fixture, Path workDir) {
        long t = System.nanoTime();
        RecallStressReport stress = RecallStressEvaluator.evaluate(fixture, workDir);
        long recall = System.nanoTime() - t;
        t = System.nanoTime();
        ThresholdSweepReport report = sweep(stress, GRID);
        Map<String, Long> elapsed = new LinkedHashMap<>();
        elapsed.put("stress evaluation (#122)", recall);
        elapsed.put("threshold sweep", System.nanoTime() - t);
        return report.withElapsed(elapsed);
    }

    /** Applies every threshold of {@code grid} to the fixed rankings of {@code stress}; pure and deterministic. */
    public static ThresholdSweepReport sweep(RecallStressReport stress, List<Double> grid) {
        validateGrid(grid);
        int k = stress.deepestK();
        List<QuerySweep> rows = new ArrayList<>();
        for (QueryOutcome q : stress.queries()) {
            List<List<Hit>> kept = new ArrayList<>();
            for (double threshold : grid) {
                kept.add(retained(q.hits(), threshold, k));
            }
            rows.add(new QuerySweep(q, kept));
        }
        List<ForgeGate> gates = List.of(stressChecksGate(stress), baselineIdentityGate(rows, k),
                monotonicGate(rows, grid));
        return new ThresholdSweepReport(stress, grid, rows, gates, Map.of());
    }

    /** The hits a host keeps: similarity at or above {@code threshold}, then the first {@code k} in rank order. */
    static List<Hit> retained(List<Hit> ranked, double threshold, int k) {
        return ranked.stream().filter(h -> h.similarity() >= threshold).limit(k).toList();
    }

    /** A grid starts at the {@code 0.0} baseline, is strictly ascending and has enough finite candidates. */
    static void validateGrid(List<Double> grid) {
        if (grid.isEmpty() || grid.get(0) != 0.0) {
            throw new IllegalArgumentException("the grid starts with the 0.0 baseline: " + grid);
        }
        if (grid.size() < MIN_CANDIDATES + 1) {
            throw new IllegalArgumentException("the grid needs at least " + MIN_CANDIDATES
                    + " candidates besides 0.0: " + grid);
        }
        for (int i = 1; i < grid.size(); i++) {
            double v = grid.get(i);
            if (!Double.isFinite(v) || v <= grid.get(i - 1)) {
                throw new IllegalArgumentException("grid values must be finite and strictly ascending: " + grid);
            }
        }
    }

    static ForgeGate stressChecksGate(RecallStressReport stress) {
        List<String> violations = stress.failedGates().stream().map(id -> "stress check " + id + " failed").toList();
        return ForgeGate.of(ThresholdSweepReport.STRESS_CHECKS,
                "the #122 rankings under the sweep pass RS1-RS4 (scoped, blank, deterministic, #102 parity)",
                violations, List.of("stress decision: " + stress.decision()));
    }

    static ForgeGate baselineIdentityGate(List<QuerySweep> rows, int k) {
        List<String> violations = new ArrayList<>();
        for (QuerySweep row : rows) {
            List<Hit> unfiltered = row.outcome().hits().stream().limit(k).toList();
            if (!row.kept().get(0).equals(unfiltered)) {
                violations.add(row.id() + ": threshold 0.0 changes the top " + k);
            }
        }
        return ForgeGate.of(ThresholdSweepReport.BASELINE_IDENTITY,
                "threshold 0.0 keeps exactly the unfiltered top " + k + " of every query (current behaviour)",
                violations, List.of("queries compared: " + rows.size()));
    }

    static ForgeGate monotonicGate(List<QuerySweep> rows, List<Double> grid) {
        List<String> violations = new ArrayList<>();
        for (QuerySweep row : rows) {
            for (int i = 1; i < grid.size(); i++) {
                List<Hit> lower = row.kept().get(i - 1);
                List<Hit> higher = row.kept().get(i);
                if (higher.size() > lower.size() || !lower.subList(0, higher.size()).equals(higher)) {
                    violations.add(row.id() + ": hits kept at " + grid.get(i) + " are not a prefix of those kept at "
                            + grid.get(i - 1));
                }
            }
        }
        return ForgeGate.of(ThresholdSweepReport.MONOTONIC,
                "a higher threshold keeps a prefix of what a lower one keeps (order unchanged)", violations,
                List.of("thresholds: " + grid.size() + "; queries: " + rows.size()));
    }
}
