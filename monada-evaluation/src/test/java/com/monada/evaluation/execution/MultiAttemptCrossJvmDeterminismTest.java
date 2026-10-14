package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * MA6 compares two runs inside one JVM, where hash-seeded iteration orders (Map.copyOf, Set.of) are identical, so it
 * cannot see an ordering that differs between JVMs. As {@link ForgeCrossJvmDeterminismTest} does for the version-1
 * command, this test starts several JVMs at the same time on the module's runtime classpath, runs
 * {@link MultiAttemptEvaluationMain} in each, and requires the same deterministic report.
 *
 * <p>The check is probabilistic by nature: a random per-JVM order that has two possible outcomes is caught with
 * probability {@code 1 - 2 / 2^JVMS} (97% with six JVMs), never falsely reported.
 */
class MultiAttemptCrossJvmDeterminismTest {
    private static final int JVMS = 6;
    private static final String TIMING = "== Elapsed timing";

    private record Result(int exit, String out, String err) {
    }

    @Test
    void separateJvmsProduceTheSameDeterministicReport() throws Exception {
        String classpath = System.getProperty("forge.runtime.classpath");
        assertNotNull(classpath, "the Gradle test task must pass forge.runtime.classpath");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<Process> processes = new ArrayList<>();
        List<CompletableFuture<String>> outs = new ArrayList<>();
        List<CompletableFuture<String>> errs = new ArrayList<>();
        for (int i = 0; i < JVMS; i++) {
            Process p = new ProcessBuilder(java, "-cp", classpath, MultiAttemptEvaluationMain.class.getName())
                    .start();
            processes.add(p);
            outs.add(CompletableFuture.supplyAsync(() -> read(p.getInputStream())));
            errs.add(CompletableFuture.supplyAsync(() -> read(p.getErrorStream())));
        }
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < JVMS; i++) {
            Process p = processes.get(i);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                processes.forEach(Process::destroyForcibly);
                throw new AssertionError("JVM " + i + " did not finish within 120 s");
            }
            results.add(new Result(p.exitValue(), outs.get(i).get(), errs.get(i).get()));
        }

        for (int i = 0; i < JVMS; i++) {
            Result r = results.get(i);
            assertEquals(0, r.exit(), "JVM " + i + " failed: " + r.err() + r.out());
            assertTrue(r.out().contains(TIMING), "the report ends with its timing section");
            assertFalse(r.out().contains("\r"), "JVM " + i + " printed a carriage return");
        }
        String first = deterministicPart(results.get(0).out());
        assertTrue(first.contains("ATTEMPT_JOINS_VERIFIED"), first);
        for (int i = 1; i < JVMS; i++) {
            assertEquals(first, deterministicPart(results.get(i).out()),
                    "JVM " + i + " printed a different deterministic report than JVM 0");
        }
    }

    private static String deterministicPart(String report) {
        return report.substring(0, report.indexOf(TIMING));
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
