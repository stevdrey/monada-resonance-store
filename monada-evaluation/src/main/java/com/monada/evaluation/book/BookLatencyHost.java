package com.monada.evaluation.book;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The machine and JVM a book latency run measured on (Issue #168). Host facts are reported apart from the
 * deterministic inputs: they identify a run, they never take part in a comparison or a gate.
 */
final class BookLatencyHost {
    /**
     * The page-cache condition of every timing: the runner never drops the OS cache (that is a destructive, global,
     * privileged operation), and the Store files were just written or read, so they are presumably cached.
     */
    static final String PAGE_CACHE = "WARM_OR_UNKNOWN (no OS cache drop performed; files were just written or read;"
            + " an OS cold-cache condition is never measured or implied)";

    private BookLatencyHost() {
    }

    /** JVM, OS, CPU, heap, GC and the file system of {@code scratch}. */
    static Map<String, String> describe(Path scratch) {
        // The same JVM fields as the execution benchmarks report (their helper is package-private there).
        Map<String, String> host = new LinkedHashMap<>();
        host.put("java.version", System.getProperty("java.version"));
        host.put("java.vm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        host.put("java.vendor", System.getProperty("java.vendor"));
        host.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        host.put("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        host.put("max heap", Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MiB");
        host.put("cpu", cpuModel());
        host.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName)
                .collect(Collectors.joining(", ")));
        host.put("jvm args", describeArgs(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        host.put("scratch file store", fileStore(scratch));
        host.put("page cache", PAGE_CACHE);
        host.put("clock", "System.nanoTime() wall-clock, single thread, no CPU pinning or frequency control");
        return host;
    }

    /**
     * Performance-relevant JVM options that may be recorded and forwarded: heap, stack, GC choice and sizing, JIT
     * tiering and processor count. Everything else is left out on purpose: system properties ({@code -D}) can carry
     * secrets and the report is committed, and diagnostic options such as flight recordings or logs name output files
     * that sequential child JVMs would overwrite.
     */
    private static final Pattern PERFORMANCE_ARG = Pattern.compile(
            "-Xm[sxn][0-9]+[kKmMgGtT]?|-Xss[0-9]+[kKmMgGtT]?|-Xint|-Xmixed"
                    + "|-XX:[+-]Use[A-Za-z]*GC|-XX:[+-]UseCompressedOops|-XX:[+-]TieredCompilation"
                    + "|-XX:(Max|Min|Initial)RAMPercentage=[0-9.]+|-XX:(Max|Min)HeapFreeRatio=[0-9]+"
                    + "|-XX:MaxGCPauseMillis=[0-9]+|-XX:(Parallel|Conc)GCThreads=[0-9]+"
                    + "|-XX:TieredStopAtLevel=[0-9]|-XX:ActiveProcessorCount=[0-9]+");

    /** The allowlisted performance options of {@code args}, in order. */
    static List<String> performanceArgs(List<String> args) {
        return args.stream().filter(a -> PERFORMANCE_ARG.matcher(a).matches()).toList();
    }

    /**
     * {@code args} as they may be reported: the allowlisted options, plus how many others were left out (their
     * values are never rendered).
     */
    static String describeArgs(List<String> args) {
        List<String> kept = performanceArgs(args);
        int redacted = args.size() - kept.size();
        String shown = kept.isEmpty() ? "(none)" : String.join(" ", kept);
        return redacted == 0 ? shown : shown + " (+" + redacted + " other arguments not recorded)";
    }

    /** The first {@code model name} of {@code /proc/cpuinfo}, or {@code unknown} where there is none. */
    static String cpuModel() {
        Path cpuinfo = Path.of("/proc/cpuinfo");
        try {
            if (Files.isReadable(cpuinfo)) {
                for (String line : Files.readAllLines(cpuinfo, StandardCharsets.UTF_8)) {
                    if (line.startsWith("model name")) {
                        int colon = line.indexOf(':');
                        return colon < 0 ? "unknown" : line.substring(colon + 1).strip();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
        return "unknown";
    }

    static String fileStore(Path dir) {
        try {
            var store = Files.getFileStore(dir);
            return store.type() + " (" + store.name() + ")";
        } catch (IOException e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }
}
