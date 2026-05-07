package com.monada.api;

import com.monada.core.MonadaRecall;
import com.monada.encoder.FrequencyEncoder;
import com.monada.encoder.TextNormalizer;
import com.monada.index.FeedbackAwareResonanceIndex;
import com.monada.index.ResonanceIndex;
import com.monada.storage.feedback.FeedbackStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

public class MonadaQuery {

    private final String query;
    private final FrequencyEncoder encoder;
    private final TextNormalizer textNormalizer;
    private final ResonanceIndex resonanceIndex;
    private final FeedbackStore feedbackStore;
    private int topK = 10;
    private double threshold = 0.0;

    MonadaQuery(String query, FrequencyEncoder encoder, TextNormalizer textNormalizer,
                ResonanceIndex resonanceIndex, FeedbackStore feedbackStore) {
        this.query = Objects.requireNonNull(query, "query");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer");
        this.resonanceIndex = Objects.requireNonNull(resonanceIndex, "resonanceIndex");
        this.feedbackStore = Objects.requireNonNull(feedbackStore, "feedbackStore");
    }

    public MonadaQuery topK(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }
        this.topK = topK;
        return this;
    }

    public MonadaQuery threshold(double threshold) {
        this.threshold = threshold;
        return this;
    }

    public MonadaRecall execute() {
        try {
            ResonanceIndex index = new FeedbackAwareResonanceIndex(resonanceIndex, feedbackStore, query);
            var normalizedText = textNormalizer.normalize(query);
            var enrichedQuery = normalizedText.enrichedText();
            if (enrichedQuery.isBlank()) {
                return new MonadaRecall(List.of());
            }
            var queryVector = encoder.encode(enrichedQuery);
            return new MonadaRecall(index.search(queryVector, topK, threshold));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
