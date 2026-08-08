package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;

import java.io.IOException;
import java.nio.file.Files;

/** Command-line entry point for deterministic persisted-feedback replay evaluation. */
public final class FeedbackReplayMain {

    private static final String FIXTURE_RESOURCE =
            "/com/monada/evaluation/feedback/expanded-technology-v1.tsv";

    private FeedbackReplayMain() {
    }

    public static void main(String[] args) throws IOException {
        var basePath = Files.createTempDirectory("monada-feedback-replay-");
        try {
            var events = loadFixture();
            var report = new FeedbackReplayEvaluationRunner()
                    .run(ExpandedTechnologyDataset.get(), events, basePath);
            System.out.println(TextEvaluationCatalog.EXPANDED_FEEDBACK_REPLAY
                    .render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(basePath);
        }
    }

    static java.util.List<FeedbackReplayEvent> loadFixture() throws IOException {
        var input = FeedbackReplayMain.class.getResourceAsStream(FIXTURE_RESOURCE);
        if (input == null) {
            throw new IOException("Missing feedback replay fixture: " + FIXTURE_RESOURCE);
        }
        return new FeedbackReplayFixtureParser(input, FIXTURE_RESOURCE).parse();
    }
}
