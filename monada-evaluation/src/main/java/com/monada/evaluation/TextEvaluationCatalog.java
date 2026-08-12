package com.monada.evaluation;

/** Canonical metadata for the repository's text evaluation entry points. */
final class TextEvaluationCatalog {

    static final String PRODUCTION_DEFAULTS_PROFILE = "PRODUCTION_DEFAULTS";

    static final TextEvaluationMetadata DEFAULT_DATABASES = new TextEvaluationMetadata(
            "default-databases", "1", PRODUCTION_DEFAULTS_PROFILE, TextEvaluationMode.PROTECTED);
    static final TextEvaluationMetadata EXPANDED_TECHNOLOGY = new TextEvaluationMetadata(
            "expanded-technology", "1", PRODUCTION_DEFAULTS_PROFILE, TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata PROJECT_MEMORY = new TextEvaluationMetadata(
            "project-memory", "2", PRODUCTION_DEFAULTS_PROFILE, TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata EXPANDED_PROFILE_COMPARISON = new TextEvaluationMetadata(
            "expanded-technology", "1", "MULTI_PROFILE_COMPARISON", TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata EXPANDED_FEEDBACK_REPLAY = new TextEvaluationMetadata(
            "expanded-technology", "1", "FEEDBACK_REPLAY_COMPARISON", TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata FEEDBACK_QUERY_KEY_COMPARISON = new TextEvaluationMetadata(
            "feedback-query-key-comparison", "1", "QUERY_KEY_STRATEGY_COMPARISON",
            TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata PROJECT_MEMORY_FEEDBACK_KEY_VALIDATION = new TextEvaluationMetadata(
            "project-memory", "2", "NORMALIZED_FEEDBACK_KEY_VALIDATION",
            TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata NORMALIZED_QUERY_KEY_STRESS = new TextEvaluationMetadata(
            "normalized-query-key-stress", "1", "NORMALIZED_QUERY_KEY_STRESS",
            TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata EXPANDED_LATENCY = new TextEvaluationMetadata(
            "expanded-technology", "1", EvaluationProfile.LEXICAL_ENRICHED.name(),
            TextEvaluationMode.EXPLORATORY);

    private TextEvaluationCatalog() {
    }
}
