package com.monada.evaluation;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for the exploratory linear-scan phase breakdown:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runScanPhaseBreakdown -q
 * </pre>
 *
 * <p>Isolates atom load, vector load, cosine scoring, dedup, full sort and truncation at
 * N=100/1,000/5,000/10,000 and K=1/5 before any top-K optimization is considered.
 */
public final class ScanPhaseBreakdownMain {

    private ScanPhaseBreakdownMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-scan-phase-breakdown-");
        try {
            var report = new ScanPhaseBreakdownRunner().run(workdir);
            System.out.println(TextEvaluationCatalog.SCAN_PHASE_BREAKDOWN.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
