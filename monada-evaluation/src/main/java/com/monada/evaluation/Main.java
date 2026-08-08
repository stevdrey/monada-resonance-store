package com.monada.evaluation;

import com.monada.evaluation.datasets.DefaultDatabasesDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for running the evaluation harness from the command line:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:run
 * </pre>
 *
 * <p>Uses a temporary directory so consecutive runs are independent and reproducible.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path workdir = Files.createTempDirectory("monada-evaluation-");
        try {
            EvaluationReport report = new EvaluationRunner()
                    .run(DefaultDatabasesDataset.get(), workdir);
            var versionedReport = new VersionedTextEvaluationReport(
                    TextEvaluationCatalog.DEFAULT_DATABASES, report);
            System.out.println(versionedReport.render());
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
