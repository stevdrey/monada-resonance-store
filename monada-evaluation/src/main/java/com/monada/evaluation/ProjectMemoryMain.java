package com.monada.evaluation;

import com.monada.evaluation.datasets.ProjectMemoryDataset;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for running the project-memory evaluation dataset from the command line:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runProjectMemory -q
 * </pre>
 *
 * <p>Uses a temporary directory so consecutive runs are independent and reproducible.
 * Metric values from this dataset are exploratory and are not protected as a strict baseline.
 */
public final class ProjectMemoryMain {

    private ProjectMemoryMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-evaluation-project-memory-");
        try {
            var diagnosticOptions = TextEncodingDiagnosticEnvironment.parse(System.getenv());
            var report = new EvaluationRunner(diagnosticOptions)
                    .run(ProjectMemoryDataset.get(), workdir);
            var versionedReport = new VersionedTextEvaluationReport(
                    TextEvaluationCatalog.PROJECT_MEMORY, report);
            System.out.println(versionedReport.render());
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
