package com.monada.evaluation.execution;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

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

    static int run(PrintStream out) {
        Path work = null;
        try {
            work = Files.createTempDirectory("forge-recall-stress");
            RecallStressReport report = RecallStressEvaluator.evaluate(RecallStressFixture.load(), work);
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
