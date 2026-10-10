package com.monada.api.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Canonical projection text, scheme {@code summary-text-v1}: the caller-approved task summary, then the
 * solution summary and the lesson summary when present, joined by a line feed. No identifiers, outcomes,
 * gate states, usage, costs, routes or artifacts are included, so they never weigh on similarity. Each part
 * is bounded by {@code ExecutionLimits.MAX_SUMMARY_CODE_POINTS}.
 */
final class ProjectionText {
    private ProjectionText() {
    }

    static String of(String taskSummary, Optional<String> solution, Optional<String> lesson) {
        List<String> parts = new ArrayList<>(3);
        parts.add(taskSummary);
        solution.ifPresent(parts::add);
        lesson.ifPresent(parts::add);
        return String.join("\n", parts);
    }
}
