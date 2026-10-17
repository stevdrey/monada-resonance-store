package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * G7 (and MA6 of the multi-attempt evaluation) compares two runs inside one JVM, where hash-seeded iteration orders
 * (Map.copyOf, Set.of) are identical, so it cannot see an ordering that differs between JVMs: that kind of
 * nondeterminism was found once only by diffing the report across separate processes by hand. This test does that
 * diff for every evaluation command: it starts several JVMs at the same time on the module's runtime classpath, runs
 * the command in each, and requires the same deterministic report.
 *
 * <p>The check is probabilistic by nature: a random per-JVM order that has two possible outcomes is caught with
 * probability {@code 1 - 2 / 2^JVMS} (97% with six JVMs), never falsely reported.
 */
class ForgeCrossJvmDeterminismTest {
    private static final int JVMS = 6;
    private static final String TIMING = "== Elapsed timing";

    /** Commands in this package that print no evaluation report, so there is nothing to compare across JVMs. */
    private static final Set<Class<?>> NOT_REPORT_COMMANDS = Set.of(ExperienceExportMain.class);
    /**
     * Arguments of commands whose default run is too heavy for {@value #JVMS} concurrent JVMs (each fsyncs its own
     * store). The scale sweep keeps three sizes and every K, and the ledger growth benchmark three sizes and every
     * default page size, but both use smaller sizes and fewer timed iterations; their deterministic reports are
     * still compared in full.
     */
    private static final Map<Class<?>, List<String>> COMMAND_ARGS = Map.of(ExecutionScaleSweepMain.class,
            List.of("--sizes=25,100,250", "--warmup=1", "--iterations=2"),
            LedgerGrowthBenchmarkMain.class, List.of("--sizes=50,200,500", "--warmup=0", "--iterations=1"));

    private record Result(int exit, String out, String err) {
    }

    /** Each evaluation command with the decision its report prints when every gate passes. */
    static Stream<Arguments> commands() {
        return Stream.of(Arguments.of(ForgeExperienceEvaluationMain.class, "READY_FOR_HOST_ADAPTER_EXPERIMENT"),
                Arguments.of(MultiAttemptEvaluationMain.class, "ATTEMPT_JOINS_VERIFIED"),
                Arguments.of(RecallStressEvaluationMain.class, "STRESS_SET_CHARACTERIZED"),
                Arguments.of(ThresholdSweepMain.class, "SWEEP_CHARACTERIZED"),
                Arguments.of(QueryExpansionMain.class, "COMPARISON_CHARACTERIZED"),
                Arguments.of(ExecutionScaleSweepMain.class, "SCALE_SWEEP_CHARACTERIZED"),
                Arguments.of(LedgerGrowthBenchmarkMain.class, "LEDGER_GROWTH_CHARACTERIZED"));
    }

    /** A new evaluation command fails here until it is added to {@link #commands()} or, if it prints no report, excluded. */
    @Test
    void everyEvaluationCommandIsCovered() throws Exception {
        Path classes = Path.of(ThresholdSweepMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path dir = classes.resolve(ThresholdSweepMain.class.getPackageName().replace('.', '/'));
        Set<String> found;
        try (Stream<Path> files = Files.list(dir)) {
            found = files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith("Main.class") && !n.contains("$"))
                    .map(n -> ThresholdSweepMain.class.getPackageName() + "." + n.substring(0, n.length() - 6))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }
        Set<String> covered = Stream.concat(commands().map(a -> (Class<?>) a.get()[0]), NOT_REPORT_COMMANDS.stream())
                .map(Class::getName)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(found, covered, "every *Main in " + dir + " must be in commands() or NOT_REPORT_COMMANDS");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commands")
    void separateJvmsProduceTheSameDeterministicReport(Class<?> command, String decision) throws Exception {
        String classpath = System.getProperty("forge.runtime.classpath");
        assertNotNull(classpath, "the Gradle test task must pass forge.runtime.classpath");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<Process> processes = new ArrayList<>();
        List<CompletableFuture<String>> outs = new ArrayList<>();
        List<CompletableFuture<String>> errs = new ArrayList<>();
        for (int i = 0; i < JVMS; i++) {
            List<String> commandLine = new ArrayList<>(List.of(java, "-cp", classpath, command.getName()));
            commandLine.addAll(COMMAND_ARGS.getOrDefault(command, List.of()));
            Process p = new ProcessBuilder(commandLine).start();
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
        assertTrue(first.contains(decision), first);
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
