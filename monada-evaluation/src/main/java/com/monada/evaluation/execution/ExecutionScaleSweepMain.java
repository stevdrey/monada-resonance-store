package com.monada.evaluation.execution;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the scoped execution recall scale sweep (issue #125) and prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runExecutionScaleSweep -q
 * ./gradlew :monada-evaluation:runExecutionScaleSweep -q --args="--sizes=25,250,1000,2500 --iterations=10"
 * </pre>
 *
 * <p>Options: {@code --seed=<long>}, {@code --sizes=<n,...>} (experiences per scope), {@code --ks=<k,...>},
 * {@code --warmup=<n>}, {@code --iterations=<n>}; defaults are seed 42, sizes 25/250/1000, K 1/5/20, warmup 2 and
 * 5 iterations. It needs no Forge installation, no network and no provider. Stores are created in a temporary
 * directory that is removed afterwards. Exit code 0 when every hard check passed (decision
 * {@code SCALE_SWEEP_CHARACTERIZED}), 1 otherwise; timings never change the exit code and are printed last,
 * separately, because they are the only nondeterministic part of the output.
 */
public final class ExecutionScaleSweepMain {
    private ExecutionScaleSweepMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out, parse(args)));
    }

    static int run(PrintStream out, ExecutionScaleSweepEvaluator.Config config) {
        return RecallStressEvaluationMain.runReport(out, "execution-scale-sweep",
                work -> ExecutionScaleSweepEvaluator.evaluate(config, work));
    }

    static ExecutionScaleSweepEvaluator.Config parse(String... args) {
        ExecutionScaleSweepEvaluator.Config d = ExecutionScaleSweepEvaluator.Config.defaults();
        long seed = d.seed();
        List<Integer> sizes = d.sizes();
        List<Integer> ks = d.ks();
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
                case "ks" -> ks = ints(value);
                case "warmup" -> warmup = Integer.parseInt(value);
                case "iterations" -> iterations = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return new ExecutionScaleSweepEvaluator.Config(seed, sizes, ks, warmup, iterations);
    }

    private static List<Integer> ints(String csv) {
        if (csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }
}
