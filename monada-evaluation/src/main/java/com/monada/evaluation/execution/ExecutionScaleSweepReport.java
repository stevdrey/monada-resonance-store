package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeEvaluationReport.fmt;

import com.monada.evaluation.ScaleTimingStatistics;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.Pattern;
import com.monada.storage.execution.ProjectionCheckpoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Report of the scoped execution recall scale sweep (issue #125). {@link #render()} is deterministic for a fixed
 * configuration: corpus shape, structural counts, projection generations, hard checks and the decision.
 * {@link #renderTiming()} holds everything measured on the clock or the heap (phase timings, recall latency
 * percentiles, heap and on-disk sizes, JVM and environment); it is informational and never gates the decision.
 */
public record ExecutionScaleSweepReport(ExecutionScaleSweepEvaluator.Config config, List<SizeResult> sizes,
                                        List<ForgeGate> gates, List<SizeTiming> timings,
                                        Map<String, String> environment)
        implements RecallStressEvaluationMain.Report {
    public static final String SCOPE_ISOLATION = "S1-SCOPE-ISOLATION";
    public static final String EXACT_REFERENCES = "S2-EXACT-REFERENCES";
    public static final String DETERMINISTIC_TIES = "S3-DETERMINISTIC-TIES";
    public static final String PARITY = "S4-REBUILD-REOPEN-PARITY";
    public static final String STRUCTURAL_ACCOUNTING = "S5-STRUCTURAL-ACCOUNTING";

    /** Only the hard checks decide; timing and memory never do. */
    public enum Decision { SCALE_SWEEP_CHARACTERIZED, GAPS_REQUIRE_FOLLOW_UP }

    /** Deterministic structure of one scope at one size, from the generated corpus and the published checkpoint. */
    public record ScopeStructure(String scope, int ledgerEvents, int executions, Map<Pattern, Integer> patterns,
                                 int attemptsStarted, int unfinishedAttempts, int correctedFinishes,
                                 int activeAttempts, int projectedRefs, int liveAtoms, int retiredAtomsAfterAppend,
                                 int retiredAtomsPublished, int dimensions, int structuralCandidates,
                                 List<String> generations, int queries) {
        public ScopeStructure {
            patterns = Map.copyOf(patterns);
            generations = List.copyOf(generations);
        }
    }

    /** One size of the sweep; {@code selfRecallAtOne} is exploratory and never gated. */
    public record SizeResult(int experiencesPerScope, int scopeCount, List<ScopeStructure> scopes,
                             int selfRecallTargets, int selfRecallAtOne) {
        public SizeResult {
            scopes = List.copyOf(scopes);
        }
    }

    /** On-disk bytes of one scope: ledger (everything but projections) and projection generations. */
    public record Disk(long ledgerBytes, long projectionBytes) {
    }

    /** Nondeterministic measurements of one size. */
    public record SizeTiming(int experiencesPerScope, Map<String, Long> phaseNanos,
                             Map<Integer, ScaleTimingStatistics> recallLatency, long readOnlyHeapDeltaBytes,
                             Map<String, Disk> disk) {
        public SizeTiming {
            phaseNanos = new LinkedHashMap<>(phaseNanos);
            recallLatency = new LinkedHashMap<>(recallLatency);
            disk = new LinkedHashMap<>(disk);
        }
    }

    public ExecutionScaleSweepReport {
        sizes = List.copyOf(sizes);
        gates = List.copyOf(gates);
        timings = List.copyOf(timings);
        environment = new LinkedHashMap<>(environment);
    }

    public Decision decision() {
        return allGatesPassed() ? Decision.SCALE_SWEEP_CHARACTERIZED : Decision.GAPS_REQUIRE_FOLLOW_UP;
    }

    @Override
    public boolean allGatesPassed() {
        return gates.stream().allMatch(ForgeGate::passed);
    }

    /** Names of the failed gates, in gate order. */
    public List<String> failedGates() {
        return gates.stream().filter(g -> !g.passed()).map(ForgeGate::id).toList();
    }

    public static Set<String> expectedGateIds() {
        return Set.of(SCOPE_ISOLATION, EXACT_REFERENCES, DETERMINISTIC_TIES, PARITY, STRUCTURAL_ACCOUNTING);
    }

    @Override
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("Monada execution memory — scoped recall scale sweep (issue #125)\n");
        out.append("Public ExecutionMemory APIs only; synthetic corpus from an explicit seed; no network, provider "
                + "or Forge. Defaults, limits, index and formats are unchanged.\n");

        out.append("\n== Configuration ==\n");
        out.append(fmt("  seed=%d sizes(N experiences per scope)=%s scopes=%d K=%s\n", config.seed(),
                config.sizes(), ExecutionScaleCorpusGenerator.SCOPES.size(), config.ks()));
        out.append(fmt("  dimensions=%d text scheme=%s recall MAX_LIMIT=%d\n",
                ProjectionCheckpoint.PROJECTION_DIMENSIONS, ProjectionCheckpoint.TEXT_SCHEME,
                com.monada.api.execution.ExperienceRecall.MAX_LIMIT));
        out.append(fmt("  latency: warmup=%d untimed + iterations=%d timed recalls per query and K, read-only "
                + "snapshots\n", config.warmup(), config.iterations()));
        out.append("  scopes: ").append(String.join(", ", ExecutionScaleCorpusGenerator.SCOPES)).append('\n');
        out.append("  patterns: PROBE (identical text in every scope), SINGLE, REPAIR (fail then repair), "
                + "UNFINISHED_REPAIR (repair never finished), CORRECTED (finish corrected r1->r2, r1 text retired), "
                + "DUPLICATE (independent executions with identical text: one atom, several refs)\n");

        out.append("\n== Structure per size (deterministic) ==\n");
        out.append("  ledger=ledger events; active=finished attempts at latest revision (recallable); "
                + "retired=retired atoms after append / in the published generation; candidates=live+retired "
                + "atoms in the published generation (atoms a linear-scan recall visits)\n");
        for (SizeResult size : sizes) {
            out.append(fmt("\nN=%d per scope x %d scopes\n", size.experiencesPerScope(), size.scopeCount()));
            for (ScopeStructure s : size.scopes()) {
                out.append(fmt("  %-11s ledger=%d executions=%d attempts=%d unfinished=%d corrected=%d active=%d "
                                + "refs=%d live-atoms=%d retired=%d/%d candidates=%d dims=%d queries=%d\n",
                        s.scope(), s.ledgerEvents(), s.executions(), s.attemptsStarted(), s.unfinishedAttempts(),
                        s.correctedFinishes(), s.activeAttempts(), s.projectedRefs(), s.liveAtoms(),
                        s.retiredAtomsAfterAppend(), s.retiredAtomsPublished(), s.structuralCandidates(),
                        s.dimensions(), s.queries()));
                List<String> patterns = new ArrayList<>();
                for (Pattern p : Pattern.values()) {
                    patterns.add(p + "=" + s.patterns().getOrDefault(p, 0));
                }
                out.append("              patterns ").append(String.join(" ", patterns)).append('\n');
                out.append("              generations ").append(String.join(" ", s.generations())).append('\n');
            }
            out.append(fmt("  exploratory (NOT gated): exact-text queries whose rank-1 hit is the target or an "
                    + "identical-text twin at K=%d: %d/%d\n", config.ks().get(config.ks().size() - 1),
                    size.selfRecallAtOne(), size.selfRecallTargets()));
        }

        out.append("\n== Hard correctness checks (every size; any failure blocks the decision) ==\n");
        for (ForgeGate g : gates) {
            out.append(fmt("  [%s] %s — %s\n", g.passed() ? "PASS" : "FAIL", g.id(), g.name()));
            int shown = 0;
            for (String d : g.details()) {
                if (shown++ == 20) {
                    out.append(fmt("        ... %d more\n", g.details().size() - 20));
                    break;
                }
                out.append("        ").append(d).append('\n');
            }
        }

        out.append("\n== Decision ==\n");
        out.append("  ").append(decision()).append('\n');
        out.append("  Timing, latency percentiles, heap and disk sizes below are informational only.\n");
        return out.toString();
    }

    @Override
    public String renderTiming() {
        StringBuilder out = new StringBuilder("== Elapsed timing (nondeterministic; excluded from every gate) ==\n");
        out.append("Environment\n");
        environment.forEach((k, v) -> out.append(fmt("  %-22s %s\n", k, v)));
        for (SizeTiming t : timings) {
            out.append(fmt("\nN=%d per scope\n", t.experiencesPerScope()));
            t.phaseNanos().forEach((phase, nanos) -> out.append(fmt("  %-22s %10.1f ms (all scopes)\n", phase,
                    nanos / 1_000_000.0)));
            t.recallLatency().forEach((k, s) -> out.append(fmt("  recall K=%-3d samples=%-5d p50=%8.1f us "
                            + "p95=%8.1f us max=%8.1f us avg=%8.1f us\n", k, s.sampleCount(),
                    s.medianNanos() / 1_000.0, s.p95Nanos() / 1_000.0, s.maxNanos() / 1_000.0,
                    s.avgNanos() / 1_000.0)));
            out.append(fmt("  heap proxy: used-heap delta after opening %d read-only snapshots (after System.gc()) "
                    + "%.1f KiB\n", t.disk().size(), t.readOnlyHeapDeltaBytes() / 1024.0));
            t.disk().forEach((scope, d) -> out.append(fmt("  disk %-11s ledger=%.1f KiB projection=%.1f KiB\n",
                    scope, d.ledgerBytes() / 1024.0, d.projectionBytes() / 1024.0)));
        }
        return out.toString();
    }

    /** The JVM and machine the timings were measured on. */
    static Map<String, String> currentEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("java.version", System.getProperty("java.version"));
        env.put("java.vm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        env.put("java.vendor", System.getProperty("java.vendor"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        env.put("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        env.put("max heap", fmt("%.0f MiB", Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0)));
        return env;
    }
}
