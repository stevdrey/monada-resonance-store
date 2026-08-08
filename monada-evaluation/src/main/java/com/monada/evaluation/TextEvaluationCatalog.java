package com.monada.evaluation;

/** Canonical metadata for the repository's text evaluation entry points. */
final class TextEvaluationCatalog {

    static final String PRODUCTION_DEFAULTS_PROFILE = "PRODUCTION_DEFAULTS";

    static final TextEvaluationMetadata DEFAULT_DATABASES = new TextEvaluationMetadata(
            "default-databases", "1", PRODUCTION_DEFAULTS_PROFILE, TextEvaluationMode.PROTECTED);
    static final TextEvaluationMetadata EXPANDED_TECHNOLOGY = new TextEvaluationMetadata(
            "expanded-technology", "1", PRODUCTION_DEFAULTS_PROFILE, TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata PROJECT_MEMORY = new TextEvaluationMetadata(
            "project-memory", "1", PRODUCTION_DEFAULTS_PROFILE, TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata EXPANDED_PROFILE_COMPARISON = new TextEvaluationMetadata(
            "expanded-technology", "1", "MULTI_PROFILE_COMPARISON", TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata EXPANDED_FEEDBACK_REPLAY = new TextEvaluationMetadata(
            "expanded-technology", "1", "FEEDBACK_REPLAY_COMPARISON", TextEvaluationMode.EXPLORATORY);
    static final TextEvaluationMetadata EXPANDED_LATENCY = new TextEvaluationMetadata(
            "expanded-technology", "1", EvaluationProfile.LEXICAL_ENRICHED.name(),
            TextEvaluationMode.EXPLORATORY);

    private TextEvaluationCatalog() {
    }
}
