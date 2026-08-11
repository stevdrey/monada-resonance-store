package com.monada.evaluation;

import com.monada.evaluation.datasets.FeedbackQueryKeyComparisonDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Command-line entry point for the exploratory feedback query-key strategy comparison. */
public final class FeedbackQueryKeyComparisonMain {

    private static final String FIXTURE_RESOURCE =
            "/com/monada/evaluation/feedback/query-key-comparison-v1.tsv";

    private FeedbackQueryKeyComparisonMain() {
    }

    public static void main(String[] args) throws IOException {
        var basePath = Files.createTempDirectory("monada-feedback-query-key-comparison-");
        try {
            var report = new FeedbackQueryKeyComparisonRunner().run(
                    FeedbackQueryKeyComparisonDataset.get(), loadFixture(), basePath);
            System.out.println(TextEvaluationCatalog.FEEDBACK_QUERY_KEY_COMPARISON
                    .render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(basePath);
        }
    }

    static List<FeedbackQueryKeyComparisonCase> loadFixture() throws IOException {
        var input = FeedbackQueryKeyComparisonMain.class.getResourceAsStream(FIXTURE_RESOURCE);
        if (input == null) {
            throw new IOException("Missing feedback query-key comparison fixture: " + FIXTURE_RESOURCE);
        }
        return new FeedbackQueryKeyComparisonFixtureParser(input, FIXTURE_RESOURCE).parse();
    }
}
