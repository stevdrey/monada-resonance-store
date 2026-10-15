package com.monada.evaluation.execution;

import java.io.PrintStream;

/**
 * Compares exact queries with the bounded query-side lexical expansion of issue #124 over the judged recall stress set
 * (issue #122) and prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runForgeQueryExpansionComparison -q
 * </pre>
 *
 * <p>It needs no Forge installation, no network and no provider. Stores are created in a temporary directory that
 * is removed afterwards. Exit code 0 when every hard check passed (decision {@code COMPARISON_CHARACTERIZED}),
 * 1 otherwise; candidate verdicts are exploratory and never change the exit code. Elapsed timing is printed last,
 * separately, because it is the only nondeterministic part of the output.
 */
public final class QueryExpansionMain {
    private QueryExpansionMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out));
    }

    static int run(PrintStream out) {
        return RecallStressEvaluationMain.runReport(out, "forge-query-expansion",
                work -> QueryExpansionEvaluator.evaluate(RecallStressFixture.load(), QueryExpansion.load(), work));
    }
}
