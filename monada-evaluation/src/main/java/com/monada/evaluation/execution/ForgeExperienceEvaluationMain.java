package com.monada.evaluation.execution;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Runs the reproducible Forge experience-memory evaluation (issue #102) and prints the report:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runForgeExperienceEvaluation -q
 * </pre>
 *
 * <p>It needs no Forge installation, no network and no provider. Stores are created in a temporary directory that
 * is removed afterwards. Exit code 0 when every hard gate passed (decision
 * {@code READY_FOR_HOST_ADAPTER_EXPERIMENT}), 1 otherwise. Elapsed timing is printed last, separately, because it
 * is the only nondeterministic part of the output.
 */
public final class ForgeExperienceEvaluationMain {
    private ForgeExperienceEvaluationMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out));
    }

    static int run(PrintStream out) {
        Path work = null;
        try {
            work = Files.createTempDirectory("forge-experience-evaluation");
            ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(ForgeFixture.load(), work);
            out.print(report.render());
            out.println();
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
