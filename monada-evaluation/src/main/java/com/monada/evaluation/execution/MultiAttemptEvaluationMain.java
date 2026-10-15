package com.monada.evaluation.execution;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Runs the multi-attempt evaluation (issue #121) over fixture {@code forge-multi-attempt-v1} and prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runMultiAttemptExecutionEvaluation -q
 * </pre>
 *
 * <p>It needs no Forge installation, no network and no provider. Stores are created in a temporary directory that
 * is removed afterwards. Exit code 0 when every gate passed (decision {@code ATTEMPT_JOINS_VERIFIED}), 1 otherwise.
 * Elapsed timing is printed last, separately, because it is the only nondeterministic part of the output. The
 * version-1 evaluation ({@link ForgeExperienceEvaluationMain}) is a separate command and is not affected.
 */
public final class MultiAttemptEvaluationMain {
    private MultiAttemptEvaluationMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out));
    }

    static int run(PrintStream out) {
        Path work = null;
        try {
            work = Files.createTempDirectory("forge-multi-attempt-evaluation");
            MultiAttemptEvaluationReport report = MultiAttemptEvaluator.evaluate(MultiAttemptFixture.load(), work);
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
