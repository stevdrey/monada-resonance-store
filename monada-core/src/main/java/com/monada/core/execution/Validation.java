package com.monada.core.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Shared, side-effect free validation helpers for the execution domain records. */
final class Validation {

    private Validation() {
    }

    /** Contract section 1: 1-128 code points, no control characters, no surrounding whitespace. */
    static String identifier(String value, String field) {
        return text(value, field, ExecutionLimits.MAX_IDENTIFIER_CODE_POINTS);
    }

    /** Short opaque descriptor (fingerprint, version, name, unit): same rules, larger bound. */
    static String opaque(String value, String field) {
        return text(value, field, ExecutionLimits.MAX_OPAQUE_CODE_POINTS);
    }

    /** Caller-approved free text. Multi-line allowed; must be non-blank and losslessly encodable. */
    static String summary(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.codePoints().allMatch(Validation::isSpace)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.codePointCount(0, value.length()) > ExecutionLimits.MAX_SUMMARY_CODE_POINTS) {
            throw new IllegalArgumentException(field + " exceeds "
                    + ExecutionLimits.MAX_SUMMARY_CODE_POINTS + " code points");
        }
        requireWellFormed(value, field);
        return value;
    }

    private static String text(String value, String field, int maxCodePoints) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        int length = value.codePointCount(0, value.length());
        if (length > maxCodePoints) {
            throw new IllegalArgumentException(field + " exceeds " + maxCodePoints + " code points");
        }
        requireWellFormed(value, field);
        if (isSpace(value.codePointAt(0)) || isSpace(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException(field + " must not have leading or trailing whitespace");
        }
        value.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        });
        return value;
    }

    /**
     * Space set of the contract (section 1): {@code Character.isWhitespace} or {@code Character.isSpaceChar}
     * or U+0085 NEXT LINE, which neither Java predicate covers.
     */
    private static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x0085;
    }

    private static void requireWellFormed(String value, String field) {
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
            i += Character.charCount(cp);
        }
    }

    static double finiteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
        return value;
    }

    static <T> List<T> boundedList(List<T> source, String field, int max) {
        Objects.requireNonNull(source, field);
        List<T> copy = List.copyOf(source);
        if (copy.size() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " entries");
        }
        return copy;
    }

    static <T, K> void requireUnique(List<T> items, Function<T, K> key, String field) {
        Set<K> seen = new HashSet<>();
        for (T item : items) {
            if (!seen.add(key.apply(item))) {
                throw new IllegalArgumentException(field + " must be unique: " + key.apply(item));
            }
        }
    }
}
