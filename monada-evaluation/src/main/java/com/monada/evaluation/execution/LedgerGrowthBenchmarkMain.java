package com.monada.evaluation.execution;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the execution ledger growth benchmark (issue #126) and prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runLedgerGrowthBenchmark -q
 * ./gradlew :monada-evaluation:runLedgerGrowthBenchmark -q --args="--sizes=100,1000,5000,10000 --iterations=10"
 * </pre>
 *
 * <p>Options: {@code --seed=<long>}, {@code --sizes=<n,...>} (ledger events), {@code --exportPageSizes=<n,...>},
 * {@code --historyPageSizes=<n,...>}, {@code --warmup=<n>}, {@code --iterations=<n>}; defaults are seed 42, sizes
 * 100/1000/5000, export page sizes 1/50/100, history page sizes 1/100/500, warmup 2 and 5 iterations. It needs no
 * Forge installation, no network and no provider. Ledgers are created in a temporary directory that is removed
 * afterwards. Exit code 0 when every hard check passed (decision {@code LEDGER_GROWTH_CHARACTERIZED}), 1 otherwise;
 * timings never change the exit code and are printed last, separately, because they are the only nondeterministic
 * part of the output.
 */
public final class LedgerGrowthBenchmarkMain {
    private LedgerGrowthBenchmarkMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out, parse(args)));
    }

    static int run(PrintStream out, LedgerGrowthBenchmarkEvaluator.Config config) {
        return RecallStressEvaluationMain.runReport(out, "ledger-growth-benchmark",
                work -> LedgerGrowthBenchmarkEvaluator.evaluate(config, work));
    }

    static LedgerGrowthBenchmarkEvaluator.Config parse(String... args) {
        LedgerGrowthBenchmarkEvaluator.Config d = LedgerGrowthBenchmarkEvaluator.Config.defaults();
        long seed = d.seed();
        List<Integer> sizes = d.sizes();
        List<Integer> exportPageSizes = d.exportPageSizes();
        List<Integer> historyPageSizes = d.historyPageSizes();
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
                case "sizes" -> sizes = ints(value);
                case "exportPageSizes" -> exportPageSizes = ints(value);
                case "historyPageSizes" -> historyPageSizes = ints(value);
                case "warmup" -> warmup = Integer.parseInt(value);
                case "iterations" -> iterations = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return new LedgerGrowthBenchmarkEvaluator.Config(seed, sizes, exportPageSizes, historyPageSizes, warmup,
                iterations);
    }

    static List<Integer> ints(String csv) {
        if (csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }
}
