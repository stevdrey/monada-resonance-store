package com.monada.index;

import com.monada.core.ResonanceResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Experimental exact bounded top-K selector (Issue #120).
 *
 * <p>Selects the first {@code topK} results of {@link #RANKING_ORDER} from an already
 * deduplicated candidate collection while retaining at most {@code topK} candidates,
 * instead of sorting every candidate and truncating. The output must be identical to
 * the full-sort oracle used by {@link LinearScanResonanceIndex}: same atom IDs, same
 * score bits, same order.
 *
 * <p><strong>Experimental — not used by any default path.</strong> Production recall
 * keeps the full sort; this selector is only reachable through
 * {@link ExperimentalBoundedTopKLinearScanIndex} and evaluation tooling.
 *
 * <p>Callers must pass candidates with unique atom IDs (one entry per atom, as produced
 * by the linear-scan max-score deduplication). Under that precondition
 * {@link #RANKING_ORDER} is a total order, so the result does not depend on the
 * iteration order of {@code candidates}.
 */
public final class ExactBoundedTopKSelector {

    /**
     * The production ranking order: score descending ({@link Double#compare} semantics,
     * so {@code -0.0} ranks below {@code 0.0}), then atom ID ascending.
     */
    public static final Comparator<ResonanceResult> RANKING_ORDER =
            Comparator.comparingDouble(ResonanceResult::score).reversed()
                    .thenComparing(result -> result.atom().id());

    private ExactBoundedTopKSelector() {
    }

    /**
     * Result of a bounded selection.
     *
     * @param results      the selected results in {@link #RANKING_ORDER}, immutable
     * @param offered      number of candidates offered to the selector
     * @param peakRetained maximum number of candidates held at once (never above {@code topK})
     */
    public record Selection(List<ResonanceResult> results, int offered, int peakRetained) {
    }

    public static Selection select(Collection<ResonanceResult> candidates, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }
        // Min-heap under the ranking order: the head is the worst retained candidate.
        var worstFirst = RANKING_ORDER.reversed();
        var heap = new PriorityQueue<ResonanceResult>(Math.min(topK, Math.max(1, candidates.size())), worstFirst);
        var offered = 0;
        var peakRetained = 0;
        for (var candidate : candidates) {
            offered++;
            if (heap.size() < topK) {
                heap.add(candidate);
                peakRetained = Math.max(peakRetained, heap.size());
            } else if (RANKING_ORDER.compare(candidate, heap.peek()) < 0) {
                heap.poll();
                heap.add(candidate);
            }
        }
        var results = new ArrayList<>(heap);
        results.sort(RANKING_ORDER);
        return new Selection(List.copyOf(results), offered, peakRetained);
    }
}
