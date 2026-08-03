package com.monada.evaluation;

import java.util.Objects;

/** Stable identity and enforcement policy for a text evaluation run. */
record TextEvaluationMetadata(
        String datasetName,
        String datasetVersion,
        String profileName,
        TextEvaluationMode mode
) {
    TextEvaluationMetadata {
        datasetName = requireNonBlank(datasetName, "datasetName");
        datasetVersion = requireNonBlank(datasetVersion, "datasetVersion");
        profileName = requireNonBlank(profileName, "profileName");
        Objects.requireNonNull(mode, "mode");
    }

    String render(String reportBody) {
        Objects.requireNonNull(reportBody, "reportBody");
        var sb = new StringBuilder();
        sb.append("Text Evaluation Context\n");
        sb.append("=======================\n");
        sb.append("Mode: ").append(mode.name()).append('\n');
        if (mode == TextEvaluationMode.PROTECTED) {
            sb.append("Policy: versioned baseline enforced in CI\n");
        } else {
            sb.append("Policy: exploratory results (NOT enforced in CI)\n");
        }
        sb.append("Dataset: ").append(datasetName).append('\n');
        sb.append("Dataset version: ").append(datasetVersion).append('\n');
        sb.append("Profile: ").append(profileName).append("\n\n");
        sb.append(reportBody);
        return sb.toString();
    }

    private String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
