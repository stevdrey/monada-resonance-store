package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeEvaluationReport.fmt;

import com.monada.core.execution.EventKind;
import com.monada.evaluation.ScaleTimingStatistics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Report of the execution memory open-cost breakdown (issue #156). {@link #render()} is deterministic for a fixed
 * configuration: corpus shape, ledger bytes and digest, projection generation, hard checks and the
 * decision. {@link #renderTiming()} holds everything measured on the clock or the heap and the projection bytes (per open medians, the
 * ledger / history index / projection split taken by difference, shares, heap proxy, growth exponents, JVM and
 * environment); it is informational and never gates the decision.
 */
public record OpenCostBreakdownReport(OpenCostBreakdownEvaluator.Config config, List<SizeResult> sizes,
                                      List<ForgeGate> gates, List<SizeTiming> timings,
                                      Map<String, String> environment)
        implements RecallStressEvaluationMain.Report {
    public static final String PROJECTION_STATES = "B1-PROJECTION-STATES";
    public static final String REPLAY_FIDELITY = "B2-REPLAY-FIDELITY";
    public static final String OPENS_DO_NOT_WRITE = "B3-OPENS-DO-NOT-WRITE";

    /** Only the hard checks decide; timing and memory never do. */
    public enum Decision { OPEN_COST_CHARACTERIZED, GAPS_REQUIRE_FOLLOW_UP }

    /** Deterministic shape and fingerprints of one ledger size. */
    public record SizeResult(int events, int sourceExperiences, int executions, Map<EventKind, Integer> kinds,
                             long segmentBytes, String segmentDigest, String projectionGeneration) {
        public SizeResult {
            kinds = new LinkedHashMap<>(kinds);
        }
    }

    /**
     * Nondeterministic measurements of one size: the timed samples of every open, one per round, the heap proxy and
     * the projection's bytes on disk (its memory files are not byte-stable across JVMs, so they stay out of the
     * deterministic report).
     */
    public record SizeTiming(int events, Map<String, ScaleTimingStatistics> operations, long projectionBytes,
                             long readOnlyHeapFullBytes, long readOnlyHeapNoProjectionBytes) {
        public SizeTiming {
            operations = new LinkedHashMap<>(operations);
        }

        long median(String operation) {
            return operations.get(operation).medianNanos();
        }
    }

    /** One open mode split by difference of medians: ledger scan, history index, projection. */
    record Split(String mode, long ledger, long index, long projection, long total) {
    }

    public OpenCostBreakdownReport {
        sizes = List.copyOf(sizes);
        gates = List.copyOf(gates);
        timings = List.copyOf(timings);
        environment = new LinkedHashMap<>(environment);
    }

    public Decision decision() {
        return allGatesPassed() ? Decision.OPEN_COST_CHARACTERIZED : Decision.GAPS_REQUIRE_FOLLOW_UP;
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
        return Set.of(PROJECTION_STATES, REPLAY_FIDELITY, OPENS_DO_NOT_WRITE);
    }

    /** Writable and read-only splits of one size. */
    static List<Split> splits(SizeTiming t) {
        return List.of(
                split("writable", t, OpenCostBreakdownEvaluator.LEDGER_SCAN,
                        OpenCostBreakdownEvaluator.WRITABLE_NO_PROJECTION, OpenCostBreakdownEvaluator.WRITABLE_FULL),
                split("read-only", t, OpenCostBreakdownEvaluator.READER_SCAN,
                        OpenCostBreakdownEvaluator.READ_ONLY_NO_PROJECTION,
                        OpenCostBreakdownEvaluator.READ_ONLY_FULL));
    }

    private static Split split(String mode, SizeTiming t, String scan, String noProjection, String full) {
        long ledger = t.median(scan);
        long bare = t.median(noProjection);
        long total = t.median(full);
        return new Split(mode, ledger, bare - ledger, total - bare, total);
    }

    @Override
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("Monada execution memory — open cost split into ledger scan, history index and projection "
                + "(issue #156)\n");
        out.append("Public ExecutionMemory and ExecutionLedger/ExecutionLedgerReader APIs only; synthetic events from "
                + "an explicit seed; no network, provider or Forge. Ledger, projection, codec, limits and defaults "
                + "are unchanged.\n");

        out.append("\n== Configuration ==\n");
        out.append(fmt("  seed=%d sizes(ledger events)=%s\n", config.seed(), config.sizes()));
        out.append(fmt("  rounds: warmup=%d untimed + iterations=%d timed; one fresh instance per open; only the "
                + "open (and replay where named) is timed\n", config.warmup(), config.iterations()));
        out.append("  corpus: the first N events of scope ").append(LedgerGrowthBenchmarkEvaluator.SCOPE)
                .append(" of the #125 generator, recorded through ExecutionMemory (incremental projection); a "
                        + "copy of the root without projection.current and projection-* generations (writer lock "
                        + "file copied) opens with projection MISSING\n");
        out.append("  split by difference of medians: ledger = storage open + replay; history index = facade open "
                + "of the copy - ledger; projection = facade open of the full root - copy\n");

        out.append("\n== Structure per size (deterministic) ==\n");
        for (SizeResult s : sizes) {
            out.append(fmt("\nN=%d events, cut from a %d-experience scope; executions=%d\n", s.events(),
                    s.sourceExperiences(), s.executions()));
            out.append("  kinds ").append(s.kinds().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(" "))).append('\n');
            out.append(fmt("  ledger segment bytes=%d sha256=%s (identical in the projection-less copy)\n",
                    s.segmentBytes(), s.segmentDigest()));
            out.append("  projection ").append(s.projectionGeneration()).append(" (CURRENT on the full root)\n");
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
        out.append("  Timing, shares, heap proxy and growth exponents below are informational only; they rank where "
                + "open cost grows, they never pass or fail anything.\n");
        return out.toString();
    }

    @Override
    public String renderTiming() {
        StringBuilder out = new StringBuilder("== Elapsed timing (nondeterministic; excluded from every gate) ==\n");
        out.append("Environment\n");
        environment.forEach((k, v) -> out.append(fmt("  %-22s %s\n", k, v)));
        for (SizeTiming t : timings) {
            out.append(fmt("\nN=%d events\n", t.events()));
            t.operations().forEach((name, s) -> out.append(fmt("  %-50s n=%-4d p50=%10.1f us p95=%10.1f us "
                            + "max=%10.1f us\n", name, s.sampleCount(), s.medianNanos() / 1_000.0,
                    s.p95Nanos() / 1_000.0, s.maxNanos() / 1_000.0)));
            out.append(fmt("  projection on disk %.1f KiB\n", t.projectionBytes() / 1024.0));
            out.append(fmt("  heap proxy after one read-only open (after System.gc()): full %.1f KiB, no projection "
                    + "%.1f KiB\n", t.readOnlyHeapFullBytes() / 1024.0, t.readOnlyHeapNoProjectionBytes() / 1024.0));
        }
        out.append(breakdown());
        out.append(growth());
        return out.toString();
    }

    /** The split per size and mode; a difference may be negative when two medians are within noise. */
    private String breakdown() {
        StringBuilder out = new StringBuilder(fmt("\nOpen split by difference of medians (ms; share of the facade "
                + "open in parentheses)\n  %-8s %-9s %18s %18s %18s %10s\n", "N", "mode", "ledger scan",
                "history index", "projection", "total"));
        for (SizeTiming t : timings) {
            for (Split s : splits(t)) {
                out.append(fmt("  %-8d %-9s %18s %18s %18s %10.1f\n", t.events(), s.mode(), part(s.ledger(), s),
                        part(s.index(), s), part(s.projection(), s), s.total() / 1_000_000.0));
            }
        }
        return out.toString();
    }

    private static String part(long nanos, Split s) {
        return fmt("%.1f (%3.0f%%)", nanos / 1_000_000.0, s.total() == 0 ? 0.0 : 100.0 * nanos / s.total());
    }

    /**
     * Growth of each median (and of each split component) as an exponent of the event ratio: about 0 is flat, about
     * 1 linear in N. Sizes run in ascending order in one JVM, so the last step (the two largest sizes) is the less
     * biased one and orders the list.
     */
    private String growth() {
        if (timings.size() < 2) {
            return "";
        }
        SizeTiming first = timings.get(0);
        SizeTiming previous = timings.get(timings.size() - 2);
        SizeTiming last = timings.get(timings.size() - 1);
        Map<String, Double> overall = exponents(first, last);
        Map<String, Double> lastStep = exponents(previous, last);
        List<String> names = new ArrayList<>(lastStep.keySet());
        names.sort(Comparator.comparing((String n) -> lastStep.get(n), Comparator.reverseOrder())
                .thenComparing(Comparator.naturalOrder()));
        StringBuilder out = new StringBuilder(fmt("\nGrowth exponent log(median ratio)/log(event ratio); "
                + "informational, sorted by the last step\n  %-50s %14s %14s\n", "operation",
                "N=" + previous.events() + "->" + last.events(), "N=" + first.events() + "->" + last.events()));
        for (String name : names) {
            out.append(fmt("  %-50s %14.2f %14s\n", name, lastStep.get(name),
                    overall.containsKey(name) ? fmt("%.2f", overall.get(name)) : "-"));
        }
        return out.toString();
    }

    private static Map<String, Double> exponents(SizeTiming small, SizeTiming large) {
        Map<String, Long> a = medians(small);
        Map<String, Long> b = medians(large);
        double sizeRatio = Math.log((double) large.events() / small.events());
        Map<String, Double> out = new LinkedHashMap<>();
        a.forEach((name, m) -> {
            Long other = b.get(name);
            if (other != null && m > 0 && other > 0) {
                out.put(name, Math.log((double) other / m) / sizeRatio);
            }
        });
        return out;
    }

    private static Map<String, Long> medians(SizeTiming t) {
        Map<String, Long> out = new LinkedHashMap<>();
        t.operations().forEach((name, s) -> out.put(name, s.medianNanos()));
        for (Split s : splits(t)) {
            out.put(s.mode() + " split: history index", s.index());
            out.put(s.mode() + " split: projection", s.projection());
        }
        return out;
    }
}
