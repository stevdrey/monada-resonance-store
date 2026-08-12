package com.monada.evaluation;

import com.monada.evaluation.datasets.ProjectMemoryDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Command-line entry point for project-memory normalized feedback validation. */
public final class ProjectMemoryFeedbackKeyValidationMain {

    private static final String FIXTURE_RESOURCE =
            "/com/monada/evaluation/feedback/project-memory-feedback-key-validation-v1.tsv";

    private ProjectMemoryFeedbackKeyValidationMain() {
    }

    public static void main(String[] args) throws IOException {
        var basePath = Files.createTempDirectory("monada-project-memory-feedback-key-validation-");
        try {
            var report = new ProjectMemoryFeedbackKeyValidationRunner().run(
                    ProjectMemoryDataset.get(), loadFixture(), basePath);
            System.out.println(TextEvaluationCatalog.PROJECT_MEMORY_FEEDBACK_KEY_VALIDATION
                    .render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(basePath);
        }
    }

    static List<FeedbackQueryKeyComparisonCase> loadFixture() throws IOException {
        var input = ProjectMemoryFeedbackKeyValidationMain.class.getResourceAsStream(FIXTURE_RESOURCE);
        if (input == null) {
            throw new IOException("Missing project-memory feedback-key fixture: " + FIXTURE_RESOURCE);
        }
        return new FeedbackQueryKeyComparisonFixtureParser(input, FIXTURE_RESOURCE).parse();
    }
}
