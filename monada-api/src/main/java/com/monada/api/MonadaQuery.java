package com.monada.api;

import com.monada.core.MonadaRecall;
import com.monada.encoder.FrequencyEncoder;
import com.monada.encoder.LexicalExpansionOptions;
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
    private final boolean feedbackAwareRanking;
    private final LexicalExpansionOptions expansionOptions;
    private final FeedbackQueryKeyStrategy feedbackQueryKeyStrategy;
    private int topK = 10;
    private double threshold = 0.0;

    MonadaQuery(String query, FrequencyEncoder encoder, TextNormalizer textNormalizer,
                ResonanceIndex resonanceIndex, FeedbackStore feedbackStore, boolean feedbackAwareRanking) {
        this(query, encoder, textNormalizer, resonanceIndex, feedbackStore, feedbackAwareRanking, LexicalExpansionOptions.DEFAULT);
    }

    MonadaQuery(String query, FrequencyEncoder encoder, TextNormalizer textNormalizer,
                ResonanceIndex resonanceIndex, FeedbackStore feedbackStore, boolean feedbackAwareRanking,
                LexicalExpansionOptions expansionOptions) {
        this(query, encoder, textNormalizer, resonanceIndex, feedbackStore, feedbackAwareRanking,
                expansionOptions, new ExactQueryKeyStrategy());
    }

    MonadaQuery(String query, FrequencyEncoder encoder, TextNormalizer textNormalizer,
                ResonanceIndex resonanceIndex, FeedbackStore feedbackStore, boolean feedbackAwareRanking,
                LexicalExpansionOptions expansionOptions, FeedbackQueryKeyStrategy feedbackQueryKeyStrategy) {
        this.query = Objects.requireNonNull(query, "query");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer");
        this.resonanceIndex = Objects.requireNonNull(resonanceIndex, "resonanceIndex");
        this.feedbackStore = Objects.requireNonNull(feedbackStore, "feedbackStore");
        this.feedbackAwareRanking = feedbackAwareRanking;
        this.expansionOptions = Objects.requireNonNull(expansionOptions, "expansionOptions");
        this.feedbackQueryKeyStrategy = Objects.requireNonNull(feedbackQueryKeyStrategy, "feedbackQueryKeyStrategy");
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
            ResonanceIndex index = feedbackAwareRanking
                    ? new FeedbackAwareResonanceIndex(
                    resonanceIndex, feedbackStore, query, feedbackQueryKeyFor(query))
                    : resonanceIndex;
            var normalizedText = textNormalizer.normalize(query);
            if (normalizedText.enrichedText().isBlank()) {
                return new MonadaRecall(List.of());
            }
            var queryVector = encoder.encode(normalizedText.toWeightedText(expansionOptions));
            return new MonadaRecall(index.search(queryVector, topK, threshold));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String feedbackQueryKeyFor(String query) {
        var key = Objects.requireNonNull(feedbackQueryKeyStrategy.keyFor(query), "feedback query key");
        if (key.isBlank()) {
            return query;
        }
        return key;
    }
}
