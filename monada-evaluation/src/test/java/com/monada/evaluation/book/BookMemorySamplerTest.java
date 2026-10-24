package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Memory samples keep heap, allocation and RSS apart and say so when a measure is unavailable (Issue #169). */
class BookMemorySamplerTest {
    private static final String STATUS = "Name:\tjava\nVmPeak:\t  900 kB\nVmHWM:\t   2048 kB\nVmRSS:\t   1024 kB\n";

    @Test
    void unsupportedAllocationAndMissingProcAreLabelledNotGuessed() throws Exception {
        BookMemorySampler sampler = new BookMemorySampler(BookMemorySampler.unsupported("not on this JVM"),
                Optional::empty);
        BookMemorySampler.Sample sample = sampler.measure("phase", () -> null).sample();
        assertEquals("UNSUPPORTED (not on this JVM)", sample.allocatedBytes());
        assertTrue(sample.rssKiB().startsWith("UNKNOWN"), sample.rssKiB());
        assertTrue(sample.rssPeakKiB().startsWith("UNKNOWN"), sample.rssPeakKiB());
        assertTrue(sample.heapCommitted() > 0, "heap usage is always available");
        assertEquals("phase", sample.phase());
    }

    @Test
    void supportedAllocationIsADeltaOverThePhase() throws Exception {
        AtomicLong counter = new AtomicLong(100);
        BookMemorySampler.AllocationProbe probe = new BookMemorySampler.AllocationProbe() {
            @Override
            public OptionalLong allocatedBytes() {
                return OptionalLong.of(counter.get());
            }

            @Override
            public String unsupportedReason() {
                return null;
            }
        };
        BookMemorySampler sampler = new BookMemorySampler(probe, () -> Optional.of(STATUS));
        BookMemorySampler.Measured<String> measured = sampler.measure("p", () -> {
            counter.addAndGet(250);
            return "done";
        });
        assertEquals("done", measured.value());
        assertEquals("250", measured.sample().allocatedBytes());
        assertEquals("1024", measured.sample().rssKiB());
        assertEquals("2048", measured.sample().rssPeakKiB());
    }

    @Test
    void aCounterDisabledAtRuntimeIsUnsupportedNotZero() throws Exception {
        BookMemorySampler sampler = new BookMemorySampler(BookMemorySampler.fromCounter(() -> -1), Optional::empty);
        String allocated = sampler.measure("p", () -> null).sample().allocatedBytes();
        assertEquals("UNSUPPORTED (thread allocated memory measurement returned -1 (disabled at runtime))", allocated);

        AtomicLong counter = new AtomicLong(10);
        BookMemorySampler counting = new BookMemorySampler(BookMemorySampler.fromCounter(counter::get),
                Optional::empty);
        assertEquals("5", counting.measure("p", () -> counter.addAndGet(5)).sample().allocatedBytes());
    }

    @Test
    void procStatusFieldsParseOnlyKibValues() {
        assertEquals("1024", BookMemorySampler.procField(STATUS, "VmRSS"));
        assertTrue(BookMemorySampler.procField(STATUS, "VmSwap").startsWith("UNKNOWN (no VmSwap"));
        assertTrue(BookMemorySampler.procField("VmRSS:\t12 MB\n", "VmRSS").startsWith("UNKNOWN (unparseable"));
    }

    @Test
    void thePlatformSamplerNeverThrowsOnThisHost() throws Exception {
        BookMemorySampler.Sample sample = BookMemorySampler.platform().measure("host", () -> null).sample();
        assertTrue(sample.allocatedBytes().matches("-?[0-9]+") || sample.allocatedBytes().startsWith("UNSUPPORTED"));
        assertTrue(sample.rssKiB().matches("[0-9]+") || sample.rssKiB().startsWith("UNKNOWN"));
    }
}
