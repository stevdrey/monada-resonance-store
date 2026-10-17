package com.monada.evaluation.execution;

import java.io.PrintStream;
import java.util.List;

/**
 * Runs the execution memory open-cost breakdown (issue #156) and prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runOpenCostBreakdownBenchmark -q
 * ./gradlew :monada-evaluation:runOpenCostBreakdownBenchmark -q --args="--sizes=1000,5000,20000,50000 --iterations=10"
 * </pre>
 *
 * <p>Options: {@code --seed=<long>}, {@code --sizes=<n,...>} (ledger events), {@code --warmup=<n>},
 * {@code --iterations=<n>}; defaults are seed 42, sizes 1000/5000/20000/50000, warmup 2 and 5 iterations. It needs no
 * Forge installation, no network and no provider. Ledgers are created in a temporary directory that is removed
 * afterwards. Exit code 0 when every hard check passed (decision {@code OPEN_COST_CHARACTERIZED}), 1 otherwise;
 * timings never change the exit code and are printed last, separately, because they are the only nondeterministic
 * part of the output.
 */
public final class OpenCostBreakdownMain {
    private OpenCostBreakdownMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out, parse(args)));
    }

    static int run(PrintStream out, OpenCostBreakdownEvaluator.Config config) {
        return RecallStressEvaluationMain.runReport(out, "open-cost-breakdown",
                work -> OpenCostBreakdownEvaluator.evaluate(config, work));
    }

    static OpenCostBreakdownEvaluator.Config parse(String... args) {
        OpenCostBreakdownEvaluator.Config d = OpenCostBreakdownEvaluator.Config.defaults();
        long seed = d.seed();
        List<Integer> sizes = d.sizes();
        int warmup = d.warmup();
        int iterations = d.iterations();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("expected --name=value, got: " + arg);
            }
            String value = arg.substring(eq + 1);
            switch (arg.substring(2, eq)) {
                case "seed" -> seed = Long.parseLong(value);
                case "sizes" -> sizes = LedgerGrowthBenchmarkMain.ints(value);
                case "warmup" -> warmup = Integer.parseInt(value);
                case "iterations" -> iterations = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return new OpenCostBreakdownEvaluator.Config(seed, sizes, warmup, iterations);
    }
}
