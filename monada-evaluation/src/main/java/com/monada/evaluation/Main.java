package com.monada.evaluation;

import com.monada.evaluation.datasets.DefaultDatabasesDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

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
            System.out.println(report.render());
        } finally {
            deleteRecursively(workdir);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
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
