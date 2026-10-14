package com.monada.evaluation;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for the exploratory vector-load breakdown:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runVectorLoadBreakdown -q
 * </pre>
 *
 * <p>Splits {@code FileFrequencyStore.findAll()} into index parse, seek, per-float read, bulk read,
 * float decode and materialization at N=100/1,000/5,000/10,000 and decides whether a bulk frame read
 * is justified.
 */
public final class VectorLoadBreakdownMain {

    private VectorLoadBreakdownMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-vector-load-breakdown-");
        try {
            var report = new VectorLoadBreakdownRunner().run(workdir);
            System.out.println(TextEvaluationCatalog.VECTOR_LOAD_BREAKDOWN.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
