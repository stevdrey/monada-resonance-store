package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.NoOpTextNormalizer;
import com.monada.encoder.TextNormalizer;

import java.util.Objects;

/**
 * Describes a complete retrieval configuration for A/B evaluation.
 *
 * <p>Three standard profiles are provided as constants:
 * <ul>
 *   <li>{@link #RAW} — no lexical enrichment, no feedback.</li>
 *   <li>{@link #LEXICAL_ENRICHED} — full lexical enrichment pipeline, no feedback.</li>
 *   <li>{@link #LEXICAL_ENRICHED_WITH_FEEDBACK} — full lexical enrichment plus
 *       feedback-aware ranking with deterministic seed events.</li>
 * </ul>
 *
 * <p>Custom profiles can be built via the constructor for future experiments
 * (e.g. embedding encoder, different language resources, etc.).
 */
public record EvaluationProfile(
        String name,
        TextNormalizer textNormalizer,
        boolean feedbackAware,
        LexicalExpansionOptions expansionOptions
) {

    public EvaluationProfile(String name, TextNormalizer textNormalizer, boolean feedbackAware) {
        this(name, textNormalizer, feedbackAware, LexicalExpansionOptions.DEFAULT);
    }

    /** Raw profile: no lexical enrichment, no feedback-aware ranking. */
    public static final EvaluationProfile RAW =
            new EvaluationProfile("RAW", new NoOpTextNormalizer(), false);

    /** Lexical enrichment only: {@link LexicalEnrichmentPipeline}, no feedback. */
    public static final EvaluationProfile LEXICAL_ENRICHED =
            new EvaluationProfile("LEXICAL_ENRICHED", new LexicalEnrichmentPipeline(), false);

    /**
     * Lexical enrichment with feedback-aware ranking.
     *
     * <p>The {@link EvaluationProfileRunner} will seed one deterministic positive
     * feedback event per query (query text → first expected label) before measuring,
     * so that the effect of feedback on ranking is visible in the comparison report.
     */
    public static final EvaluationProfile LEXICAL_ENRICHED_WITH_FEEDBACK =
            new EvaluationProfile("LEXICAL_ENRICHED_WITH_FEEDBACK", new LexicalEnrichmentPipeline(), true);

    /** Unweighted lexical enrichment (the old behavior for comparison). */
    public static final EvaluationProfile UNWEIGHTED_LEXICAL_ENRICHED =
            new EvaluationProfile("UNWEIGHTED_LEXICAL_ENRICHED", new LexicalEnrichmentPipeline(), false,
                    new LexicalExpansionOptions(1.0, 1.0));

    public EvaluationProfile {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(textNormalizer, "textNormalizer");
        Objects.requireNonNull(expansionOptions, "expansionOptions");
    }

    /** Returns the {@link MonadaMemoryOptions} that correspond to this profile. */
    public MonadaMemoryOptions toMemoryOptions() {
        return new MonadaMemoryOptions(textNormalizer, feedbackAware, expansionOptions);
    }
}
