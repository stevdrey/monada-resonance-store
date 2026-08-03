package com.monada.evaluation;

import java.util.Objects;

/** Associates an evaluation report with its stable dataset/profile identity. */
record VersionedTextEvaluationReport(
        TextEvaluationMetadata metadata,
        EvaluationReport evaluationReport
) {
    VersionedTextEvaluationReport {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(evaluationReport, "evaluationReport");
    }

    String render() {
        return metadata.render(evaluationReport.render());
    }
}
