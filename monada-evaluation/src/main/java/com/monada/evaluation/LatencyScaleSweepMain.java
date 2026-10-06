package com.monada.evaluation;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for running the exploratory scaled linear-scan benchmark:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runLatencyScaleSweep -q
 * </pre>
 *
 * <p>Sweeps across multiple corpus scale points (100, 1,000, 5,000, 10,000) and top-K
 * configurations (K=1, K=5) to generate structural scan metrics, timing curves,
 * retrieval quality, ranking stability analysis, and an optimization decision gate.
 */
public final class LatencyScaleSweepMain {

    private LatencyScaleSweepMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-latency-scale-sweep-");
        try {
            var report = new LatencyScaleSweepRunner().run(workdir);
            System.out.println(TextEvaluationCatalog.LATENCY_SCALE_SWEEP.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
