package com.monada.evaluation.book;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Labelled process-memory samples around one experiment phase (Issue #169). Three measurement families are kept apart
 * and never combined or compared as if they measured the same thing:
 * <ul>
 *   <li><b>heap</b>: {@code MemoryMXBean} heap used/committed and non-heap committed at the end of the phase, plus
 *   the sum of the heap pools' peak usage since the phase started (pool peaks are reset first; a sum of per-pool
 *   peaks is an upper bound of the true heap peak, not a simultaneous reading);</li>
 *   <li><b>allocation</b>: bytes the measuring thread allocated during the phase, through
 *   {@code com.sun.management.ThreadMXBean}, or {@code UNSUPPORTED} with the reason;</li>
 *   <li><b>RSS</b>: the process resident set ({@code VmRSS}) and its lifetime high-water mark ({@code VmHWM}) from
 *   Linux {@code /proc/self/status}, or {@code UNKNOWN} where that file is not available. The high-water mark is never
 *   reset (that needs a write to {@code /proc}), so it is a process-lifetime peak, not a per-phase one.</li>
 * </ul>
 * Every value is host-, JVM- and GC-dependent: samples identify a run, they never gate it.
 */
final class BookMemorySampler {
    static final String HEAP_METHOD = "java.lang.management MemoryMXBean heap/non-heap usage; heap pool peaks reset"
            + " at phase start and summed (upper bound, not simultaneous)";
    static final String ALLOCATION_METHOD = "com.sun.management.ThreadMXBean current-thread allocated bytes, delta"
            + " over the phase (this thread only)";
    static final String RSS_METHOD = "/proc/self/status VmRSS (resident now) and VmHWM (process-lifetime peak,"
            + " never reset)";
    static final String GC_NOTE = "after-GC readings follow an advisory System.gc(); the JVM may ignore it";

    /** Reads this thread's allocated bytes, or explains why it cannot. */
    interface AllocationProbe {
        /** Bytes allocated by the current thread so far; empty when unsupported. */
        OptionalLong allocatedBytes();

        /** Why {@link #allocatedBytes()} is empty, or null when it is supported. */
        String unsupportedReason();
    }

    /** One phase's readings; {@code null} longs never appear: unsupported values are strings. */
    record Sample(String phase, long heapUsed, long heapCommitted, long nonHeapCommitted, long heapPoolPeakSum,
                  String allocatedBytes, String rssKiB, String rssPeakKiB, long heapUsedAfterGc) {
    }

    private final AllocationProbe allocation;
    private final Supplier<Optional<String>> procStatus;

    BookMemorySampler(AllocationProbe allocation, Supplier<Optional<String>> procStatus) {
        this.allocation = allocation;
        this.procStatus = procStatus;
    }

    /** The sampler for this JVM and host. */
    static BookMemorySampler platform() {
        return new BookMemorySampler(platformAllocation(), BookMemorySampler::readProcStatus);
    }

    /** Work measured by {@link #measure}. */
    @FunctionalInterface
    interface Phase<T> {
        T run() throws IOException;
    }

    /** A phase's result plus its sample. */
    record Measured<T>(T value, Sample sample) {
    }

    /** Runs {@code phase} on this thread and samples memory around it. */
    <T> Measured<T> measure(String label, Phase<T> phase) throws IOException {
        List<MemoryPoolMXBean> heapPools = heapPools();
        heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
        OptionalLong allocatedBefore = allocation.allocatedBytes();
        T value = phase.run();
        OptionalLong allocatedAfter = allocation.allocatedBytes();
        var memory = ManagementFactory.getMemoryMXBean();
        var heap = memory.getHeapMemoryUsage();
        long nonHeap = memory.getNonHeapMemoryUsage().getCommitted();
        long peak = heapPools.stream().mapToLong(p -> p.getPeakUsage() == null ? 0 : p.getPeakUsage().getUsed())
                .sum();
        String allocated = allocatedBefore.isPresent() && allocatedAfter.isPresent()
                ? Long.toString(allocatedAfter.getAsLong() - allocatedBefore.getAsLong())
                : "UNSUPPORTED (" + allocation.unsupportedReason() + ")";
        Optional<String> status = procStatus.get();
        String rss = status.map(s -> procField(s, "VmRSS")).orElse(unknownRss());
        String hwm = status.map(s -> procField(s, "VmHWM")).orElse(unknownRss());
        long afterGc = heapUsedAfterGc();
        return new Measured<>(value, new Sample(label, heap.getUsed(), heap.getCommitted(), nonHeap, peak, allocated,
                rss, hwm, afterGc));
    }

    /** Heap used after an advisory collection; see {@link #GC_NOTE}. */
    static long heapUsedAfterGc() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP)
                .filter(MemoryPoolMXBean::isValid).toList();
    }

    private static String unknownRss() {
        return "UNKNOWN (no /proc/self/status on this platform)";
    }

    /**
     * The value of {@code field} in a {@code /proc/self/status} text in KiB (the kernel reports {@code kB}, which are
     * KiB), or {@code UNKNOWN} when the field is missing or not in that unit.
     */
    static String procField(String status, String field) {
        for (String line : status.split("\n")) {
            if (line.startsWith(field + ":")) {
                String[] parts = line.substring(field.length() + 1).strip().split("\\s+");
                if (parts.length == 2 && parts[1].equals("kB") && parts[0].matches("[0-9]+")) {
                    return parts[0];
                }
                return "UNKNOWN (unparseable " + field + ")";
            }
        }
        return "UNKNOWN (no " + field + " in /proc/self/status)";
    }

    private static Optional<String> readProcStatus() {
        Path status = Path.of("/proc/self/status");
        try {
            return Files.isReadable(status) ? Optional.of(Files.readString(status, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static AllocationProbe platformAllocation() {
        var bean = ManagementFactory.getThreadMXBean();
        String reason;
        if (!(bean instanceof com.sun.management.ThreadMXBean threads)) {
            reason = "ThreadMXBean is not a com.sun.management.ThreadMXBean on this JVM";
        } else if (!threads.isThreadAllocatedMemorySupported()) {
            reason = "thread allocated memory is not supported by this JVM";
        } else if (!threads.isThreadAllocatedMemoryEnabled()) {
            reason = "thread allocated memory measurement is disabled";
        } else {
            return fromCounter(threads::getCurrentThreadAllocatedBytes);
        }
        return unsupported(reason);
    }

    /**
     * A probe over a thread-allocation counter. The JVM reports -1 when measurement was disabled after the probe was
     * built, so a negative reading is unsupported, never a number: two -1 readings must not look like 0 bytes.
     */
    static AllocationProbe fromCounter(LongSupplier counter) {
        return new AllocationProbe() {
            @Override
            public OptionalLong allocatedBytes() {
                long value = counter.getAsLong();
                return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
            }

            @Override
            public String unsupportedReason() {
                return "thread allocated memory measurement returned -1 (disabled at runtime)";
            }
        };
    }

    /** A probe that never measures, for {@code reason}. */
    static AllocationProbe unsupported(String reason) {
        return new AllocationProbe() {
            @Override
            public OptionalLong allocatedBytes() {
                return OptionalLong.empty();
            }

            @Override
            public String unsupportedReason() {
                return reason;
            }
        };
    }
}
