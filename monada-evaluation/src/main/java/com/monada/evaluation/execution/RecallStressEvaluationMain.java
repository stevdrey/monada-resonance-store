package com.monada.evaluation.execution;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Runs the judged Forge recall stress set (issue #122) over the unchanged {@code forge-experience-v1} corpus and
 * prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runForgeRecallStress -q
 * </pre>
 *
 * <p>It needs no Forge installation, no network and no provider. Stores are created in a temporary directory that
 * is removed afterwards. Exit code 0 when every hard check passed (decision {@code STRESS_SET_CHARACTERIZED}),
 * 1 otherwise; the exploratory metrics never change the exit code. Elapsed timing is printed last, separately,
 * because it is the only nondeterministic part of the output.
 */
public final class RecallStressEvaluationMain {
    private RecallStressEvaluationMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out));
    }

    /** What a report command prints: deterministic text, then nondeterministic timing, and the exit decision. */
    interface Report {
        String render();

        String renderTiming();

        boolean allGatesPassed();
    }

    static int run(PrintStream out) {
        return runReport(out, "forge-recall-stress", work -> RecallStressEvaluator.evaluate(RecallStressFixture.load(),
                work));
    }

    /**
     * Evaluates in a fresh temporary directory named after {@code tempPrefix}, prints the report then its timing and
     * returns 0 when every hard check passed, 1 otherwise. The directory is removed afterwards.
     */
    static int runReport(PrintStream out, String tempPrefix, Function<Path, ? extends Report> evaluate) {
        Path work = null;
        try {
            work = Files.createTempDirectory(tempPrefix);
            Report report = evaluate.apply(work);
            out.print(report.render());
            out.print("\n");
            out.print(report.renderTiming());
            return report.allGatesPassed() ? 0 : 1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(work);
        }
    }

    /** Parses a comma-separated list of integers for the evaluation commands' list options; blank is empty. */
    static List<Integer> ints(String csv) {
        if (csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best-effort cleanup of a temporary directory
        }
    }
}
