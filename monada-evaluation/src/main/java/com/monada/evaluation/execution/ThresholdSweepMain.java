package com.monada.evaluation.execution;

import java.io.PrintStream;

/**
 * Sweeps host-owned relevance thresholds (issue #123) over the rankings of the judged recall stress set (issue #122)
 * and prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runForgeRecallThresholdSweep -q
 * </pre>
 *
 * <p>It needs no Forge installation, no network and no provider. Stores are created in a temporary directory that
 * is removed afterwards. Exit code 0 when every hard check passed (decision {@code SWEEP_CHARACTERIZED}),
 * 1 otherwise; the exploratory metrics never change the exit code and no threshold is recommended. Elapsed timing is
 * printed last, separately, because it is the only nondeterministic part of the output.
 */
public final class ThresholdSweepMain {
    private ThresholdSweepMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out));
    }

    static int run(PrintStream out) {
        return RecallStressEvaluationMain.runReport(out, "forge-recall-threshold-sweep",
                work -> ThresholdSweep.evaluate(RecallStressFixture.load(), work));
    }
}
