package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaleTimingStatisticsTest {

    @Test
    void computesPercentilesCorrectly() {
        // Latencies from 100 to 1000 with step 100 (10 samples)
        var latencies = List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L);
        var encode = List.of(50L, 50L);

        var stats = ScaleTimingStatistics.from(latencies, encode, 5, 2);

        assertEquals(5, stats.queryCount());
        assertEquals(2, stats.repetitionCount());
        assertEquals(10, stats.sampleCount());
        assertEquals(100L, stats.minNanos());
        assertEquals(550L, stats.medianNanos());
        assertEquals(1000L, stats.p95Nanos());
        assertEquals(1000L, stats.maxNanos());
        assertEquals(550L, stats.avgNanos());
        assertEquals(50L, stats.avgEncodeNanos());
        assertTrue(stats.queriesPerSecond() > 0.0);
    }

    @Test
    void computesNearestRankP95AndOddMedianCorrectly() {
        // 20 samples from 1 to 20
        var latencies20 = java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList();
        var stats20 = ScaleTimingStatistics.from(latencies20, List.of(1L), 10, 2);

        // Nearest-rank p95 for 20 samples: ceil(20 * 0.95) - 1 = 18 -> value 19
        assertEquals(19L, stats20.p95Nanos());
        // Median for 20 samples: (10 + 11) / 2 = 10
        assertEquals(10L, stats20.medianNanos());

        // Odd sample count (5 samples: 10, 20, 30, 40, 50)
        var oddStats = ScaleTimingStatistics.from(List.of(10L, 20L, 30L, 40L, 50L), List.of(5L), 5, 1);
        assertEquals(30L, oddStats.medianNanos());
    }

    @Test
    void computesThroughputCorrectly() {
        var latencies = List.of(1_000_000L); // 1 ms = 1,000 QPS
        var stats = ScaleTimingStatistics.from(latencies, List.of(100L), 1, 1);

        assertEquals(1_000_000L, stats.avgNanos());
        assertEquals(1000.0, stats.queriesPerSecond(), 0.01);
    }

    @Test
    void handlesEmptyLatencies() {
        var stats = ScaleTimingStatistics.from(List.of(), List.of(), 5, 0);

        assertEquals(0, stats.sampleCount());
        assertEquals(0L, stats.minNanos());
        assertEquals(0L, stats.medianNanos());
        assertEquals(0L, stats.p95Nanos());
        assertEquals(0L, stats.maxNanos());
        assertEquals(0L, stats.avgNanos());
        assertEquals(0L, stats.avgEncodeNanos());
        assertEquals(0.0, stats.queriesPerSecond(), 0.001);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScaleTimingStatistics(-1, 1, 1, 0, 0, 0, 0, 0, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ScaleTimingStatistics(1, -1, 1, 0, 0, 0, 0, 0, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ScaleTimingStatistics(1, 1, -1, 0, 0, 0, 0, 0, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ScaleTimingStatistics(1, 1, 1, -1, 0, 0, 0, 0, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ScaleTimingStatistics(1, 1, 1, 0, 0, 0, 0, 0, 0, -1.0));
    }
}
