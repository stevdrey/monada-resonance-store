package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for running the expanded evaluation dataset from the command line:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runExpanded -q
 * </pre>
 *
 * <p>Uses a temporary directory so consecutive runs are independent and reproducible.
 * Metric values from this dataset are exploratory and not protected as a strict baseline.
 */
public final class ExpandedMain {

    private ExpandedMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-evaluation-expanded-");
        try {
            var diagnosticOptions = TextEncodingDiagnosticEnvironment.parse(System.getenv());
            var report = new EvaluationRunner(diagnosticOptions)
                    .run(ExpandedTechnologyDataset.get(), workdir);
            var versionedReport = new VersionedTextEvaluationReport(
                    TextEvaluationCatalog.EXPANDED_TECHNOLOGY, report);
            System.out.println(versionedReport.render());
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
