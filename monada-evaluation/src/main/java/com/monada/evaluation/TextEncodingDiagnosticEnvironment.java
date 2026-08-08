package com.monada.evaluation;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Parses CLI environment variables without coupling library execution to global state. */
final class TextEncodingDiagnosticEnvironment {

    static final String ENABLED = "MONADA_EVALUATION_ENCODING_DIAGNOSTICS";
    static final String MAX_TOP_RESULTS = "MONADA_EVALUATION_ENCODING_DIAGNOSTICS_TOP_RESULTS";
    static final String MAX_MISSED_EXPECTED = "MONADA_EVALUATION_ENCODING_DIAGNOSTICS_MISSED_EXPECTED";
    static final String MAX_TERMS = "MONADA_EVALUATION_ENCODING_DIAGNOSTICS_TERMS";

    private TextEncodingDiagnosticEnvironment() {
    }

    static TextEncodingDiagnosticOptions parse(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        boolean enabled = parseBoolean(environment.get(ENABLED));
        if (!enabled) {
            return TextEncodingDiagnosticOptions.disabled();
        }
        return new TextEncodingDiagnosticOptions(
                true,
                parsePositive(environment.get(MAX_TOP_RESULTS),
                        TextEncodingDiagnosticOptions.DEFAULT_MAX_TOP_RESULTS, MAX_TOP_RESULTS),
                parsePositive(environment.get(MAX_MISSED_EXPECTED),
                        TextEncodingDiagnosticOptions.DEFAULT_MAX_MISSED_EXPECTED, MAX_MISSED_EXPECTED),
                parsePositive(environment.get(MAX_TERMS),
                        TextEncodingDiagnosticOptions.DEFAULT_MAX_TERMS_PER_REPRESENTATION, MAX_TERMS));
    }

    private static boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    ENABLED + " must be true or false: " + value);
        };
    }

    private static int parsePositive(String value, int defaultValue, String name) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer: " + value, e);
        }
    }
}
