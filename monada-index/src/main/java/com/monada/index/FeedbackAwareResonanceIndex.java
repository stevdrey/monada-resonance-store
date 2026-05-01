package com.monada.index;

import com.monada.core.FrequencyVector;
import com.monada.core.ResonanceResult;
import com.monada.storage.feedback.FeedbackEvent;
import com.monada.storage.feedback.FeedbackStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decorates a base {@link ResonanceIndex} with query-scoped feedback adjustments.
 *
 * <p>For a single query, all feedback events whose {@code query} field matches
 * exactly are aggregated into a per-atom delta sum. The adjusted score for a
 * result is {@code base + delta}. Results are re-sorted by
 * {@code adjusted score DESC, atom id ASC} to preserve deterministic ordering.
 *
 * <p>To let a positive adjustment promote an atom that would have been filtered
 * out by the caller threshold, the decorator fetches an expanded candidate pool
 * from the delegate (using {@code NEGATIVE_INFINITY} threshold and a larger
 * {@code topK}) and then applies the caller threshold to the adjusted score.
 */
public final class FeedbackAwareResonanceIndex implements ResonanceIndex {

    private static final int CANDIDATE_POOL_MULTIPLIER = 4;

    private final ResonanceIndex delegate;
    private final FeedbackStore feedbackStore;
    private final String query;

    public FeedbackAwareResonanceIndex(ResonanceIndex delegate, FeedbackStore feedbackStore, String query) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.feedbackStore = Objects.requireNonNull(feedbackStore, "feedbackStore");
        this.query = Objects.requireNonNull(query, "query");
    }

    @Override
    public List<ResonanceResult> search(FrequencyVector queryVector, int topK, double threshold) throws IOException {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }

        Map<String, Double> adjustments = buildAdjustments();

        // Pull a larger candidate pool with a permissive threshold so that a
        // positive adjustment can still surface an atom that would have been
        // filtered out by the caller threshold on the base score.
        int poolSize = adjustments.isEmpty()
                ? topK
                : Math.max(topK, Math.min(topK * CANDIDATE_POOL_MULTIPLIER, Integer.MAX_VALUE));
        double basePoolThreshold = adjustments.isEmpty()
                ? threshold
                : Double.NEGATIVE_INFINITY;

        List<ResonanceResult> base = delegate.search(queryVector, poolSize, basePoolThreshold);

        var adjusted = new ArrayList<ResonanceResult>(base.size());
        for (ResonanceResult result : base) {
            double delta = adjustments.getOrDefault(result.atom().id(), 0.0);
            double adjustedScore = result.score() + delta;
            if (adjustedScore >= threshold) {
                adjusted.add(new ResonanceResult(result.atom(), adjustedScore));
            }
        }

        adjusted.sort(
                Comparator.comparingDouble(ResonanceResult::score).reversed()
                        .thenComparing(r -> r.atom().id())
        );

        if (adjusted.size() > topK) {
            return List.copyOf(adjusted.subList(0, topK));
        }
        return List.copyOf(adjusted);
    }

    private Map<String, Double> buildAdjustments() throws IOException {
        List<FeedbackEvent> events = feedbackStore.findByQuery(query);
        if (events.isEmpty()) {
            return Map.of();
        }
        var deltas = new HashMap<String, Double>();
        for (FeedbackEvent event : events) {
            deltas.merge(event.atomId(), event.delta(), Double::sum);
        }
        return deltas;
    }
}
