package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeEvaluationReport.fmt;

import com.monada.api.execution.ExportView;
import com.monada.core.execution.EventKind;
import com.monada.evaluation.ScaleTimingStatistics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Report of the execution ledger growth benchmark (issue #126). {@link #render()} is deterministic for a fixed
 * configuration: corpus shape, ledger bytes and digests, page counts, export digests, hard checks and the decision.
 * {@link #renderTiming()} holds everything measured on the clock or the heap (phase timings, throughput, per
 * operation percentiles, heap proxy, growth exponents, JVM and environment); it is informational and never gates
 * the decision.
 */
public record LedgerGrowthBenchmarkReport(LedgerGrowthBenchmarkEvaluator.Config config, List<SizeResult> sizes,
                                          List<ForgeGate> gates, List<SizeTiming> timings,
                                          Map<String, String> environment)
        implements RecallStressEvaluationMain.Report {
    public static final String APPEND_IDEMPOTENCY = "L1-APPEND-IDEMPOTENCY";
    public static final String REPLAY_FIDELITY = "L2-REOPEN-REPLAY-FIDELITY";
    public static final String EXACT_REFERENCES = "L3-EXACT-REFERENCES";
    public static final String PAGE_EQUALITY = "L4-EXPORT-PAGE-EQUALITY";
    public static final String CUTOFF_STABILITY = "L5-CUTOFF-CURSOR-STABILITY";

    /** Only the hard checks decide; timing and memory never do. */
    public enum Decision { LEDGER_GROWTH_CHARACTERIZED, GAPS_REQUIRE_FOLLOW_UP }

    /** Deterministic shape and fingerprints of one ledger size. */
    public record SizeResult(int events, int tailEvents, int sourceExperiences, int executions,
                             int executionsAfterTail, Map<EventKind, Integer> kinds, long segmentBytes,
                             long segmentBytesAfterTail, String segmentDigest, int idempotencyProbes,
                             Map<Integer, Integer> historyPages, int samples, String samplesDigest,
                             String prefixDigest, Map<Integer, Integer> exportPages,
                             Map<Integer, String> exportDigests) {
        public SizeResult {
            kinds = new LinkedHashMap<>(kinds);
            historyPages = new TreeMap<>(historyPages);
            exportPages = new TreeMap<>(exportPages);
            exportDigests = new TreeMap<>(exportDigests);
        }
    }

    /** One single-shot phase: elapsed nanos over {@code units} items of {@code unit}. */
    public record Phase(long nanos, int units, String unit) {
        double perSecond() {
            return nanos == 0 ? 0 : units * 1_000_000_000.0 / nanos;
        }
    }

    /**
     * Nondeterministic measurements of one size: single-shot phases and, per operation, the timed samples of every
     * measured round (one sample per call, per event or per full traversal, as the operation name says).
     */
    public record SizeTiming(int events, Map<String, Phase> phases, Map<String, ScaleTimingStatistics> operations,
                             long readOnlyHeapDeltaBytes) {
        public SizeTiming {
            phases = new LinkedHashMap<>(phases);
            operations = new LinkedHashMap<>(operations);
        }
    }

    public LedgerGrowthBenchmarkReport {
        sizes = List.copyOf(sizes);
        gates = List.copyOf(gates);
        timings = List.copyOf(timings);
        environment = new LinkedHashMap<>(environment);
    }

    public Decision decision() {
        return allGatesPassed() ? Decision.LEDGER_GROWTH_CHARACTERIZED : Decision.GAPS_REQUIRE_FOLLOW_UP;
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
        return Set.of(APPEND_IDEMPOTENCY, REPLAY_FIDELITY, EXACT_REFERENCES, PAGE_EQUALITY, CUTOFF_STABILITY);
    }

    @Override
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("Monada execution memory — ledger growth, reopen/replay and sample-export paging (issue #126)\n");
        out.append("Public ExecutionMemory and ExecutionLedger/ExecutionLedgerReader APIs only; synthetic events from "
                + "an explicit seed; no network, provider or Forge. Ledger, codec, export, limits and defaults are "
                + "unchanged.\n");

        out.append("\n== Configuration ==\n");
        out.append(fmt("  seed=%d sizes(ledger events)=%s tail=max(1, N/10) events appended after the pinned "
                + "snapshot\n", config.seed(), config.sizes()));
        out.append(fmt("  export page sizes=%s (view %s, execution policy gates only, cutoff pinned at N) history "
                        + "page sizes=%s\n", config.exportPageSizes(), ExportView.ADAPTER_READY,
                config.historyPageSizes()));
        out.append(fmt("  rounds: warmup=%d untimed + iterations=%d timed; one fresh instance per open round; "
                        + "idempotency probes: up to %d evenly spaced events (every event when N is smaller) plus "
                        + "every correction\n", config.warmup(),
                config.iterations(), LedgerGrowthBenchmarkEvaluator.IDEMPOTENCY_PROBES));
        out.append("  corpus: the first N + tail events of scope ").append(LedgerGrowthBenchmarkEvaluator.SCOPE)
                .append(" of the #125 generator (single, repair, unfinished repair, corrected r1->r2 and duplicate "
                        + "executions; several events per attempt), cut at exact event counts; every prefix is a "
                        + "valid ledger\n");

        out.append("\n== Structure per size (deterministic) ==\n");
        out.append("  segment=bytes and sha256 (16 hex) of the scope's ledger directory, identical whether written "
                + "through ExecutionMemory or ExecutionLedger; prefix=ledger prefix digest at the cutoff\n");
        for (SizeResult s : sizes) {
            out.append(fmt("\nN=%d events (+%d tail), cut from a %d-experience scope; executions=%d (%d after "
                            + "tail)\n", s.events(), s.tailEvents(), s.sourceExperiences(), s.executions(),
                    s.executionsAfterTail()));
            out.append("  kinds ").append(s.kinds().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(" "))).append('\n');
            out.append(fmt("  segment bytes=%d (after tail %d) sha256=%s; idempotent re-records=%d\n",
                    s.segmentBytes(), s.segmentBytesAfterTail(), s.segmentDigest(), s.idempotencyProbes()));
            out.append("  history pages ").append(pages(s.historyPages())).append('\n');
            out.append(fmt("  export samples=%d samples sha256=%s prefix=%s\n", s.samples(), s.samplesDigest(),
                    s.prefixDigest().substring(0, Math.min(16, s.prefixDigest().length()))));
            out.append("  export pages ").append(pages(s.exportPages())).append('\n');
            out.append("  export page digests ").append(s.exportDigests().entrySet().stream()
                    .map(e -> "ps=" + e.getKey() + ":" + e.getValue()).collect(Collectors.joining(" "))).append('\n');
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
        out.append("  Timing, throughput, percentiles, heap proxy and growth exponents below are informational only; "
                + "they identify where cost grows, they never pass or fail anything.\n");
        return out.toString();
    }

    private static String pages(Map<Integer, Integer> pages) {
        return pages.entrySet().stream().map(e -> "ps=" + e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(" "));
    }

    @Override
    public String renderTiming() {
        StringBuilder out = new StringBuilder("== Elapsed timing (nondeterministic; excluded from every gate) ==\n");
        out.append("Environment\n");
        environment.forEach((k, v) -> out.append(fmt("  %-22s %s\n", k, v)));
        for (SizeTiming t : timings) {
            out.append(fmt("\nN=%d events\n", t.events()));
            t.phases().forEach((name, p) -> out.append(fmt("  %-46s %10.1f ms  %10.0f %s/s\n", name,
                    p.nanos() / 1_000_000.0, p.perSecond(), p.unit())));
            t.operations().forEach((name, s) -> out.append(fmt("  %-46s n=%-6d p50=%10.1f us p95=%10.1f us "
                            + "max=%10.1f us\n", name, s.sampleCount(), s.medianNanos() / 1_000.0,
                    s.p95Nanos() / 1_000.0, s.maxNanos() / 1_000.0)));
            out.append(fmt("  heap proxy: used-heap delta after one read-only open (after System.gc()) %.1f KiB\n",
                    t.readOnlyHeapDeltaBytes() / 1024.0));
        }
        out.append(growth());
        return out.toString();
    }

    /**
     * Growth of each operation's median as an exponent of the event ratio: about 0 is flat (per call cost independent
     * of N), about 1 linear in N, above 1 superlinear. Sizes run in ascending order in one JVM, so the smallest size
     * also absorbs JIT warm-up; the last step (the two largest sizes) is the less biased one and orders the list.
     */
    private String growth() {
        return growthTable(timings.stream().map(SizeTiming::events).toList(),
                timings.stream().map(LedgerGrowthBenchmarkReport::medians).toList(), 46, 12);
    }

    private static Map<String, Long> medians(SizeTiming t) {
        Map<String, Long> out = new LinkedHashMap<>();
        t.phases().forEach((name, p) -> out.put(name + " (total)", p.nanos()));
        t.operations().forEach((name, s) -> out.put(name, s.medianNanos()));
        return out;
    }

    /**
     * Growth exponents log(median ratio)/log(event ratio) per named median, for the last step (two largest sizes)
     * and overall (smallest to largest), sorted by the last step; non-positive medians are skipped. Empty when there
     * are fewer than two sizes. {@code medians.get(i)} belongs to {@code events.get(i)}, in ascending size order.
     */
    static String growthTable(List<Integer> events, List<Map<String, Long>> medians, int nameWidth,
                              int columnWidth) {
        int n = events.size();
        if (n < 2) {
            return "";
        }
        Map<String, Double> overall = exponents(events.get(0), medians.get(0), events.get(n - 1),
                medians.get(n - 1));
        Map<String, Double> lastStep = exponents(events.get(n - 2), medians.get(n - 2), events.get(n - 1),
                medians.get(n - 1));
        List<String> names = new ArrayList<>(lastStep.keySet());
        names.sort(Comparator.comparing((String name) -> lastStep.get(name), Comparator.reverseOrder())
                .thenComparing(Comparator.naturalOrder()));
        String header = "  %-" + nameWidth + "s %" + columnWidth + "s %" + columnWidth + "s\n";
        String row = "  %-" + nameWidth + "s %" + columnWidth + ".2f %" + columnWidth + "s\n";
        StringBuilder out = new StringBuilder(fmt("\nGrowth exponent log(median ratio)/log(event ratio); "
                + "informational, sorted by the last step\n" + header, "operation",
                "N=" + events.get(n - 2) + "->" + events.get(n - 1), "N=" + events.get(0) + "->" + events.get(n - 1)));
        for (String name : names) {
            out.append(fmt(row, name, lastStep.get(name),
                    overall.containsKey(name) ? fmt("%.2f", overall.get(name)) : "-"));
        }
        return out.toString();
    }

    private static Map<String, Double> exponents(int smallEvents, Map<String, Long> small, int largeEvents,
                                                 Map<String, Long> large) {
        double sizeRatio = Math.log((double) largeEvents / smallEvents);
        Map<String, Double> out = new LinkedHashMap<>();
        small.forEach((name, m) -> {
            Long other = large.get(name);
            if (other != null && m > 0 && other > 0) {
                out.put(name, Math.log((double) other / m) / sizeRatio);
            }
        });
        return out;
    }
}
