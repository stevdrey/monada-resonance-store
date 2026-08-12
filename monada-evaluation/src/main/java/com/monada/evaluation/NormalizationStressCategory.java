package com.monada.evaluation;

/** Normalization rule family exercised by one adversarial query pair. */
public enum NormalizationStressCategory {
    CASING,
    PUNCTUATION_SEPARATOR,
    WHITESPACE,
    PLURAL_MAPPING,
    HARMLESS_STOP_WORD,
    STOP_WORD_COORDINATION,
    STOP_WORD_RELATION,
    TOKEN_ORDER,
    NUMERIC_VERSION_IDENTIFIER,
    NEGATION_EXCLUSION,
    TECHNICAL_MODULE_TERM,
    BLANK_FALLBACK_BOUNDARY
}
