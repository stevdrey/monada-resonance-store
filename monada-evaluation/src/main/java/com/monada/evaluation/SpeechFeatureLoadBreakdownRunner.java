package com.monada.evaluation;

import com.monada.evaluation.SpeechFeatureLoadBreakdownReport.Decision;
import com.monada.evaluation.SpeechFeatureLoadBreakdownReport.PointResult;
import com.monada.speech.storage.FileSpeechFeatureStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.monada.evaluation.ScanPhaseBreakdownRunner.latency;

/**
 * Evaluation-only harness that splits {@code FileSpeechFeatureStore.findAll()} (Issue #136) into index parse, seek,
 * header read, value read, per-vector I/O and materialization at N=100/1,000/5,000/10,000 for 64 and 128
 * dimensions, and times a windowed sequential-read candidate against a replica of the pre-#136 per-vector path.
 *
 * <p>Follows the #130 {@link VectorLoadBreakdownRunner} conventions: one untimed parity and structural-count pass
 * per point, then rotated warmup and measured passes. The production read path is never modified here.
 */
public final class SpeechFeatureLoadBreakdownRunner {

    public static final List<Integer> DEFAULT_SCALE_POINTS = LatencyScaleSweepRunner.DEFAULT_SCALE_POINTS;
    public static final List<Integer> DEFAULT_DIMENSIONS = List.of(64, 128);
    public static final int DEFAULT_WARMUP_COUNT = 2;
    public static final int DEFAULT_REPETITION_COUNT = 20;

    private static final int PASS_COUNT = 9;

    private final List<Integer> dimensions;
    private final List<Integer> scalePoints;
    private final int warmupCount;
    private final int repetitionCount;
    private final long seed;

    public SpeechFeatureLoadBreakdownRunner() {
        this(DEFAULT_DIMENSIONS, DEFAULT_SCALE_POINTS, DEFAULT_WARMUP_COUNT, DEFAULT_REPETITION_COUNT,
                DeterministicScaleCorpusGenerator.DEFAULT_SEED);
    }

    public SpeechFeatureLoadBreakdownRunner(
            List<Integer> dimensions, List<Integer> scalePoints, int warmupCount, int repetitionCount, long seed) {
        this.dimensions = requireDistinctPositive(dimensions, "dimensions");
        this.scalePoints = requireDistinctPositive(scalePoints, "scalePoints");
        if (warmupCount < 0) {
            throw new IllegalArgumentException("warmupCount must be >= 0, got: " + warmupCount);
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0, got: " + repetitionCount);
        }
        this.warmupCount = warmupCount;
        this.repetitionCount = repetitionCount;
        this.seed = seed;
    }

    public SpeechFeatureLoadBreakdownReport run(Path rootTempDir) throws IOException {
        Objects.requireNonNull(rootTempDir, "rootTempDir");
        var results = new ArrayList<PointResult>();
        for (int dims : dimensions) {
            for (int corpusSize : scalePoints) {
                // Setup (untimed): the same seeded layout as the #135 copy-share speech store.
                var dir = rootTempDir.resolve("speech-load-" + dims + "-" + corpusSize);
                var store = new FileSpeechFeatureStore(dir, VectorCopyShareRunner.SPEECH_SEGMENT,
                        VectorCopyShareRunner.SPEECH_INDEX);
                VectorCopyShareRunner.seedSpeechStore(dir, corpusSize, seed, dims);
                var probe = new SpeechFeatureLoadProbe(dir, VectorCopyShareRunner.SPEECH_SEGMENT,
                        VectorCopyShareRunner.SPEECH_INDEX, dims);
                results.add(measure(probe, store, corpusSize, dims));
            }
        }
        var decision = decide(results);
        return new SpeechFeatureLoadBreakdownReport(seed, dimensions, scalePoints, warmupCount, repetitionCount,
                SpeechFeatureLoadProbe.WINDOW_BYTES, results, decision, rationale(decision, results));
    }

    /** Untimed inputs for the split passes: parsed index and whole segment bytes. */
    private record Fixture(List<SpeechFeatureLoadProbe.IndexEntry> entries, byte[] segmentBytes) {
    }

    private PointResult measure(SpeechFeatureLoadProbe probe, FileSpeechFeatureStore store, int corpusSize, int dims)
            throws IOException {
        var fixture = new Fixture(probe.parseIndex(new SpeechFeatureLoadProbe.Counters()),
                Files.readAllBytes(probe.vectorFile()));

        // Parity + structural counts (deterministic, one untimed pass of every operation).
        var counters = new SpeechFeatureLoadProbe.Counters();
        var entries = probe.parseIndex(counters);
        SpeechFeatureLoadProbe.verifySeeks(entries, probe.seekOnly(entries, counters));
        long headers = probe.headerRead(entries, counters);
        if (headers != (long) dims * entries.size()) {
            throw new IllegalStateException("header pass read " + headers + ", expected " + dims + " per entry");
        }
        if (probe.valueRead(entries, counters) + headers != probe.perVectorIo(entries, counters)) {
            throw new IllegalStateException("value read and per-vector I/O disagree at N=" + corpusSize);
        }
        var expected = VectorCopyProbe.fromSpeechStore(store.findAll());
        if (expected.size() != corpusSize) {
            throw new IllegalStateException("speech store returned " + expected.size() + " vectors, expected "
                    + corpusSize);
        }
        VectorCopyProbe.verifyParity("materialize", expected,
                VectorCopyProbe.fromSpeechStore(probe.materialize(entries, fixture.segmentBytes(), counters)));
        VectorCopyProbe.verifyParity("per-vector replica", expected,
                VectorCopyProbe.fromSpeechStore(probe.replicaFindAll()));
        VectorCopyProbe.verifyParity("windowed candidate", expected,
                VectorCopyProbe.fromSpeechStore(probe.windowedCandidateFindAll(counters)));

        var samples = new ArrayList<List<Long>>(PASS_COUNT);
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            samples.add(new ArrayList<>(repetitionCount));
        }
        for (int w = 0; w < warmupCount; w++) {
            for (int pass = 0; pass < PASS_COUNT; pass++) {
                runPass((pass + w) % PASS_COUNT, probe, store, fixture);
            }
        }
        for (int rep = 0; rep < repetitionCount; rep++) {
            for (int i = 0; i < PASS_COUNT; i++) {
                int pass = (i + rep) % PASS_COUNT;
                samples.get(pass).add(runPass(pass, probe, store, fixture));
            }
        }
        return new PointResult(corpusSize, dims, repetitionCount,
                latency(samples.get(0)), latency(samples.get(1)), latency(samples.get(2)),
                latency(samples.get(3)), latency(samples.get(4)), latency(samples.get(5)),
                latency(samples.get(6)), latency(samples.get(7)), latency(samples.get(8)),
                counters.toCounts());
    }

    /**
     * Runs one pass and returns its wall-clock nanos. Order: 0 index parse, 1 seek, 2 header read, 3 value read,
     * 4 per-vector I/O, 5 materialize, 6 replica, 7 windowed candidate, 8 production findAll.
     */
    private static long runPass(int pass, SpeechFeatureLoadProbe probe, FileSpeechFeatureStore store, Fixture fixture)
            throws IOException {
        var counters = new SpeechFeatureLoadProbe.Counters();
        long start = System.nanoTime();
        long sink = switch (pass) {
            case 0 -> probe.parseIndex(counters).size();
            case 1 -> probe.seekOnly(fixture.entries(), counters);
            case 2 -> probe.headerRead(fixture.entries(), counters);
            case 3 -> probe.valueRead(fixture.entries(), counters);
            case 4 -> probe.perVectorIo(fixture.entries(), counters);
            case 5 -> probe.materialize(fixture.entries(), fixture.segmentBytes(), counters).size();
            case 6 -> probe.replicaFindAll().size();
            case 7 -> probe.windowedCandidateFindAll(counters).size();
            case 8 -> store.findAll().size();
            default -> throw new IllegalArgumentException("unknown pass " + pass);
        };
        long elapsed = System.nanoTime() - start;
        if (sink == Long.MIN_VALUE) {
            throw new IllegalStateException("unreachable sink value");
        }
        return elapsed;
    }

    /** The largest-N point of every measured dimension, in first-seen dimension order. */
    private static List<PointResult> largestPerDimension(List<PointResult> results) {
        var dims = new ArrayList<Integer>();
        for (var r : results) {
            if (!dims.contains(r.dimensions())) {
                dims.add(r.dimensions());
            }
        }
        var largest = new ArrayList<PointResult>();
        for (int d : dims) {
            results.stream().filter(r -> r.dimensions() == d)
                    .max((a, b) -> Integer.compare(a.corpusSize(), b.corpusSize()))
                    .ifPresent(largest::add);
        }
        return largest;
    }

    static Decision decide(List<PointResult> results) {
        var largest = largestPerDimension(results);
        if (largest.isEmpty()) {
            return Decision.INCONCLUSIVE;
        }
        for (var r : largest) {
            if (r.corpusSize() < SpeechFeatureLoadBreakdownReport.MIN_DECISION_SCALE || r.sampleCount() == 0
                    || r.replicaFindAll().p50Nanos() == 0 || r.replicaFindAll().p95Nanos() == 0) {
                return Decision.INCONCLUSIVE;
            }
        }
        for (var r : largest) {
            boolean dominant = r.share(r.perVectorIo()) >= SpeechFeatureLoadBreakdownReport.PER_VECTOR_IO_DOMINANT_SHARE;
            boolean faster = r.share(r.windowedCandidate()) <= SpeechFeatureLoadBreakdownReport.WINDOWED_CANDIDATE_MAX_RATIO
                    && r.windowedRatioP95() <= SpeechFeatureLoadBreakdownReport.WINDOWED_CANDIDATE_MAX_RATIO;
            if (!dominant || !faster) {
                return Decision.PER_VECTOR_IO_NOT_DOMINANT;
            }
        }
        return Decision.PER_VECTOR_IO_DOMINANT_WINDOWED_READ_JUSTIFIED;
    }

    static String rationale(Decision decision, List<PointResult> results) {
        if (decision == Decision.INCONCLUSIVE) {
            return "Insufficient evidence: need timing samples and a largest scale point >= "
                    + SpeechFeatureLoadBreakdownReport.MIN_DECISION_SCALE + " for every dimension.";
        }
        var sb = new StringBuilder();
        for (var r : largestPerDimension(results)) {
            var c = r.counts();
            sb.append(String.format(Locale.ROOT,
                    "%d dims N=%d: per-vector I/O %.1f%% of the replica p50 (index parse %.1f%%, seek %.1f%%, header"
                            + " read %.1f%%, value read %.1f%%, materialize %.1f%%); the windowed candidate needs"
                            + " %.1f%% of the replica at p50 and %.1f%% at p95 (%d window reads vs %d per-vector"
                            + " reads); production findAll %.2fx versus the replica. ",
                    r.dimensions(), r.corpusSize(), r.share(r.perVectorIo()) * 100, r.share(r.indexParse()) * 100,
                    r.share(r.seek()) * 100, r.share(r.headerRead()) * 100, r.share(r.valueRead()) * 100,
                    r.share(r.materialize()) * 100, r.share(r.windowedCandidate()) * 100,
                    r.windowedRatioP95() * 100, c.windowReadCalls(), 2L * r.corpusSize(),
                    r.speedupVersusReplica(r.productionFindAll())));
        }
        int dominantPct = (int) Math.round(SpeechFeatureLoadBreakdownReport.PER_VECTOR_IO_DOMINANT_SHARE * 100);
        int ratioPct = (int) Math.round(SpeechFeatureLoadBreakdownReport.WINDOWED_CANDIDATE_MAX_RATIO * 100);
        sb.append(decision == Decision.PER_VECTOR_IO_DOMINANT_WINDOWED_READ_JUSTIFIED
                ? "Every dimension reaches the thresholds (per-vector I/O >= " + dominantPct + "% of the replica p50 and"
                + " the bit-identical windowed candidate <= " + ratioPct + "% of the replica at p50 and p95), so a"
                + " bounded windowed read in FileSpeechFeatureStore.findAll() (stage 2) is justified."
                : "At least one dimension misses the thresholds (per-vector I/O >= " + dominantPct + "% of the replica"
                + " p50 and windowed candidate <= " + ratioPct + "% at p50 and p95); stage 2 is not justified by this"
                + " evidence.");
        sb.append(" Single-machine, warm page cache, split passes with timer overhead; treat as exploratory.");
        return sb.toString();
    }

    private static List<Integer> requireDistinctPositive(List<Integer> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must contain distinct values");
        }
        for (int v : values) {
            if (v <= 0) {
                throw new IllegalArgumentException(name + " must be positive, got: " + v);
            }
        }
        return List.copyOf(values);
    }
}
