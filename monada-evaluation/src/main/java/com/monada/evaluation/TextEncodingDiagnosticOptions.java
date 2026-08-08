package com.monada.evaluation;

/**
 * Bounds for optional lexical encoding contribution diagnostics.
 *
 * <p>Diagnostics are disabled by default so existing evaluation output and
 * execution behavior remain unchanged.
 */
public record TextEncodingDiagnosticOptions(
        boolean enabled,
        int maxTopResults,
        int maxMissedExpected,
        int maxTermsPerRepresentation
) {
    public static final int DEFAULT_MAX_TOP_RESULTS = 3;
    public static final int DEFAULT_MAX_MISSED_EXPECTED = 3;
    public static final int DEFAULT_MAX_TERMS_PER_REPRESENTATION = 20;

    public TextEncodingDiagnosticOptions {
        requirePositive(maxTopResults, "maxTopResults");
        requirePositive(maxMissedExpected, "maxMissedExpected");
        requirePositive(maxTermsPerRepresentation, "maxTermsPerRepresentation");
    }

    public static TextEncodingDiagnosticOptions disabled() {
        return new TextEncodingDiagnosticOptions(
                false,
                DEFAULT_MAX_TOP_RESULTS,
                DEFAULT_MAX_MISSED_EXPECTED,
                DEFAULT_MAX_TERMS_PER_REPRESENTATION);
    }

    public static TextEncodingDiagnosticOptions enabledDefaults() {
        return new TextEncodingDiagnosticOptions(
                true,
                DEFAULT_MAX_TOP_RESULTS,
                DEFAULT_MAX_MISSED_EXPECTED,
                DEFAULT_MAX_TERMS_PER_REPRESENTATION);
    }

    private void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
