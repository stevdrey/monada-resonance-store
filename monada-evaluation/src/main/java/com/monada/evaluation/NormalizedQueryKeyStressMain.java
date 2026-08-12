package com.monada.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Command-line entry point for adversarial normalized query-key stress evidence. */
public final class NormalizedQueryKeyStressMain {

    private static final String FIXTURE_RESOURCE =
            "/com/monada/evaluation/feedback/normalized-query-key-stress-v1.tsv";

    private NormalizedQueryKeyStressMain() {
    }

    public static void main(String[] args) throws IOException {
        var basePath = Files.createTempDirectory("monada-normalized-query-key-stress-");
        try {
            var report = new NormalizedQueryKeyStressRunner().run(loadFixture(), basePath);
            System.out.println(TextEvaluationCatalog.NORMALIZED_QUERY_KEY_STRESS.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(basePath);
        }
    }

    static List<NormalizedQueryKeyStressCase> loadFixture() throws IOException {
        var input = NormalizedQueryKeyStressMain.class.getResourceAsStream(FIXTURE_RESOURCE);
        if (input == null) {
            throw new IOException("Missing normalized query-key stress fixture: " + FIXTURE_RESOURCE);
        }
        return new NormalizedQueryKeyStressFixtureParser(input, FIXTURE_RESOURCE).parse();
    }
}
