package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/** Result of comparing a protected text evaluation run with its snapshot. */
record TextBaselineComparison(
        TextEvaluationMetadata metadata,
        boolean passed,
        List<String> mismatches
) {
    TextBaselineComparison {
        Objects.requireNonNull(metadata, "metadata");
        mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
        if (passed && !mismatches.isEmpty()) {
            throw new IllegalArgumentException("passed comparison must not contain mismatches");
        }
        if (!passed && mismatches.isEmpty()) {
            throw new IllegalArgumentException("failed comparison must contain at least one mismatch");
        }
    }

    String render() {
        var sb = new StringBuilder();
        sb.append("Text Evaluation Baseline Comparison\n");
        sb.append("===================================\n");
        sb.append("Dataset: ").append(metadata.datasetName()).append('\n');
        sb.append("Dataset version: ").append(metadata.datasetVersion()).append('\n');
        sb.append("Profile: ").append(metadata.profileName()).append('\n');
        sb.append("Result: ").append(passed ? "PASSED" : "FAILED").append('\n');
        for (var mismatch : mismatches) {
            sb.append("- ").append(mismatch).append('\n');
        }
        return sb.toString();
    }
}
