package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

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
            var report = new EvaluationRunner()
                    .run(ExpandedTechnologyDataset.get(), workdir);
            System.out.println(report.render());
        } finally {
            deleteRecursively(workdir);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        }
    }
}
