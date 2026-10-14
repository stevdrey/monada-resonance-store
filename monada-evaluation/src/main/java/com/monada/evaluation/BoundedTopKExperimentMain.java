package com.monada.evaluation;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for the exploratory exact bounded top-K experiment (Issue #120):
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runBoundedTopKExperiment -q
 * </pre>
 *
 * <p>Checks the experimental bounded selector against the production full-sort oracle, then compares
 * selection-phase and end-to-end latency, retained candidates and allocation at N=100/1,000/5,000/10,000
 * and K=1/5 (the #119 phase-breakdown configuration). Production defaults are never changed.
 */
public final class BoundedTopKExperimentMain {

    private BoundedTopKExperimentMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-bounded-topk-experiment-");
        try {
            var report = new BoundedTopKExperimentRunner().run(workdir);
            System.out.println(TextEvaluationCatalog.BOUNDED_TOP_K_EXPERIMENT.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
