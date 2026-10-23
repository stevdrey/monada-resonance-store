package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Percentiles, denominators and rates of {@link BookLatencyStats} (Issue #168). */
class BookLatencyStatsTest {

    private static List<Long> range(int n) {
        return LongStream.rangeClosed(1, n).boxed().toList();
    }

    @Test
    void emptySeriesReportsNothing() {
        BookLatencyStats s = BookLatencyStats.of(List.of());
        assertEquals(0, s.n());
        assertTrue(s.p50().isEmpty() && s.p95().isEmpty() && s.p99().isEmpty());
        assertTrue(Double.isNaN(s.mean()) && Double.isNaN(s.stddev()));
        assertEquals("n/a (n=0 < 1)", BookLatencyStats.micros(s.p50(), s.n(), BookLatencyStats.MIN_P50));
    }

    @Test
    void singleSampleHasOnlyAMedian() {
        BookLatencyStats s = BookLatencyStats.of(List.of(7_000L));
        assertEquals(OptionalLong.of(7_000), s.p50());
        assertTrue(s.p95().isEmpty() && s.p99().isEmpty());
        assertTrue(Double.isNaN(s.stddev()), "the sample standard deviation needs two samples");
        assertEquals(7_000, s.min());
        assertEquals(7_000, s.max());
    }

    @Test
    void p95NeedsTwentySamplesAndIsNearestRank() {
        assertTrue(BookLatencyStats.of(range(19)).p95().isEmpty());
        BookLatencyStats twenty = BookLatencyStats.of(range(20));
        assertEquals(OptionalLong.of(19), twenty.p95(), "rank ceil(0.95 * 20) = 19");
        assertEquals(OptionalLong.of(10), twenty.p50(), "rank ceil(0.5 * 20) = 10, no interpolation");
        assertTrue(twenty.p99().isEmpty());
        assertEquals("n/a (n=19 < 20)", BookLatencyStats.micros(OptionalLong.empty(), 19, BookLatencyStats.MIN_P95));
    }

    @Test
    void p99NeedsOneHundredSamples() {
        assertTrue(BookLatencyStats.of(range(99)).p99().isEmpty());
        BookLatencyStats hundred = BookLatencyStats.of(range(100));
        assertEquals(OptionalLong.of(99), hundred.p99());
        assertEquals(OptionalLong.of(95), hundred.p95());
        assertEquals(OptionalLong.of(50), hundred.p50());
        BookLatencyStats odd = BookLatencyStats.of(range(321));
        assertEquals(OptionalLong.of(318), odd.p99(), "rank ceil(0.99 * 321) = 318");
        assertEquals(OptionalLong.of(161), odd.p50());
    }

    @Test
    void inputOrderAndTiesDoNotMatter() {
        List<Long> shuffled = new ArrayList<>(range(100));
        Collections.reverse(shuffled);
        assertEquals(BookLatencyStats.of(range(100)), BookLatencyStats.of(shuffled));
        BookLatencyStats ties = BookLatencyStats.of(List.of(5L, 5L, 5L, 9L));
        assertEquals(OptionalLong.of(5), ties.p50());
        assertEquals(24, ties.totalNanos());
    }

    @Test
    void meanAndSampleStandardDeviation() {
        BookLatencyStats s = BookLatencyStats.of(List.of(2L, 4L, 4L, 4L, 5L, 5L, 7L, 9L));
        assertEquals(5.0, s.mean(), 1e-12);
        assertEquals(Math.sqrt(32.0 / 7.0), s.stddev(), 1e-12);
    }

    @Test
    void ratesNeverDivideByZero() {
        assertTrue(Double.isNaN(BookLatencyStats.perSecond(10, 0)));
        assertEquals(10.0, BookLatencyStats.perSecond(10, 1_000_000_000L), 1e-12);
        assertEquals(2.5, BookLatencyStats.perSecond(5, 2_000_000_000L), 1e-12);
        assertEquals("n/a", BookLatencyStats.fmt(Double.NaN));
    }

    @Test
    void negativeLatencyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BookLatencyStats.of(List.of(1L, -1L)));
    }
}
