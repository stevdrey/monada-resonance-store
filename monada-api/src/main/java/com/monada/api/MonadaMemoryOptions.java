package com.monada.api;

import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.TextNormalizer;

import java.util.Objects;

/**
 * Configuration options for opening a {@link MonadaMemory} instance.
 *
 * <p>The primary use-case is the A/B evaluation harness: callers can override
 * the {@link TextNormalizer} (e.g. use a no-op normalizer for the RAW profile)
 * and disable feedback-aware ranking (e.g. to isolate the effect of lexical
 * enrichment independently from feedback).
 *
 * <p>The production default via {@link MonadaMemory#open(java.nio.file.Path)}
 * is equivalent to {@link #defaults()}: {@link LexicalEnrichmentPipeline} as
 * the normalizer and feedback-aware ranking enabled.
 */
public record MonadaMemoryOptions(
        TextNormalizer textNormalizer,
        boolean feedbackAwareRanking,
        LexicalExpansionOptions expansionOptions,
        FeedbackQueryKeyStrategy feedbackQueryKeyStrategy
) {
    public MonadaMemoryOptions(TextNormalizer textNormalizer, boolean feedbackAwareRanking) {
        this(textNormalizer, feedbackAwareRanking, LexicalExpansionOptions.DEFAULT, new ExactQueryKeyStrategy());
    }

    public MonadaMemoryOptions(
            TextNormalizer textNormalizer,
            boolean feedbackAwareRanking,
            LexicalExpansionOptions expansionOptions) {
        this(textNormalizer, feedbackAwareRanking, expansionOptions, new ExactQueryKeyStrategy());
    }

    public MonadaMemoryOptions {
        Objects.requireNonNull(textNormalizer, "textNormalizer");
        Objects.requireNonNull(expansionOptions, "expansionOptions");
        Objects.requireNonNull(feedbackQueryKeyStrategy, "feedbackQueryKeyStrategy");
    }

    /**
     * Returns the default production options: {@link LexicalEnrichmentPipeline}
     * normalizer with feedback-aware ranking enabled.
     */
    public static MonadaMemoryOptions defaults() {
        return new MonadaMemoryOptions(
                new LexicalEnrichmentPipeline(),
                true,
                LexicalExpansionOptions.DEFAULT,
                new ExactQueryKeyStrategy());
    }

    public MonadaMemoryOptions withFeedbackQueryKeyStrategy(FeedbackQueryKeyStrategy feedbackQueryKeyStrategy) {
        return new MonadaMemoryOptions(
                textNormalizer,
                feedbackAwareRanking,
                expansionOptions,
                feedbackQueryKeyStrategy);
    }
}
