package com.monada.speech.importer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Aggregates all warnings of a single {@link WarningCategory} observed during
 * a corpus scan.
 *
 * <p>To keep the report compact and deterministic, {@code pathExamples} is
 * capped at {@value #MAX_EXAMPLES} entries. Paths are taken from the sorted
 * (lexicographic) WAV discovery order, so the examples are stable across runs
 * on the same dataset layout.
 */
public record TorgoAuditWarningGroup(
        WarningCategory category,
        int count,
        List<Path> pathExamples
) {

    /** Maximum number of example paths retained per warning category. */
    public static final int MAX_EXAMPLES = 5;

    public TorgoAuditWarningGroup {
        Objects.requireNonNull(category, "category");
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        Objects.requireNonNull(pathExamples, "pathExamples");
        if (pathExamples.size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException(
                    "pathExamples must not exceed " + MAX_EXAMPLES + " entries");
        }
        pathExamples = List.copyOf(pathExamples);
    }
}
