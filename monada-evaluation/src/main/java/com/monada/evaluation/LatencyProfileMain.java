package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for running the recall latency and scan diagnostic profile:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runLatency -q
 * </pre>
 *
 * <p>Uses the {@link ExpandedTechnologyDataset} and a fresh temporary directory
 * so consecutive runs are independent. The printed report includes both the
 * standard quality metrics and the latency/scan diagnostics block.
 *
 * <p>Timing values are best-effort and are intended to establish a baseline
 * before any bounded top-K or scan optimization is considered.
 */
public final class LatencyProfileMain {

    private LatencyProfileMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-latency-profile-");
        try {
            var report = new LatencyEvaluationRunner()
                    .run(ExpandedTechnologyDataset.get(), workdir);
            System.out.println(TextEvaluationCatalog.EXPANDED_LATENCY.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
