package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.VectorLoadBreakdownReport.Decision;
import com.monada.evaluation.VectorLoadBreakdownReport.PointResult;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.VectorFormatProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Evaluation-only harness that splits the vector-load phase of linear-scan recall
 * ({@code FileFrequencyStore.findAll()}) into index parse, seek, per-float read, bulk read, float
 * decode and materialization over the same deterministic corpora as {@link ScanPhaseBreakdownRunner}.
 *
 * <p>Vector loading is independent of the query and of K, so it is timed per load call. Every pass is
 * checked against the real store before timing; the production read path is never modified here.
 */
public final class VectorLoadBreakdownRunner {

    public static final List<Integer> DEFAULT_SCALE_POINTS = LatencyScaleSweepRunner.DEFAULT_SCALE_POINTS;
    public static final int DEFAULT_WARMUP_COUNT = 2;
    public static final int DEFAULT_REPETITION_COUNT = 20;

    private static final int DEFAULT_DIMENSIONS = 128;
    private static final String VECTOR_FILE = "vectors/segment-000001.f32";
    private static final int PASS_COUNT = 9;

    private final String datasetName;
    private final EvaluationDataset seedDataset;
    private final List<Integer> scalePoints;
    private final int warmupCount;
    private final int repetitionCount;
    private final long seed;
    private final DeterministicScaleCorpusGenerator generator;

    public VectorLoadBreakdownRunner() {
        this("expanded-technology", ExpandedTechnologyDataset.get(), DEFAULT_SCALE_POINTS,
                DEFAULT_WARMUP_COUNT, DEFAULT_REPETITION_COUNT, DeterministicScaleCorpusGenerator.DEFAULT_SEED);
    }

    public VectorLoadBreakdownRunner(
            String datasetName,
            EvaluationDataset seedDataset,
            List<Integer> scalePoints,
            int warmupCount,
            int repetitionCount,
            long seed) {
        this.datasetName = Objects.requireNonNull(datasetName, "datasetName");
        this.seedDataset = Objects.requireNonNull(seedDataset, "seedDataset");
        Objects.requireNonNull(scalePoints, "scalePoints");
        if (scalePoints.isEmpty()) {
            throw new IllegalArgumentException("scalePoints must not be empty");
        }
        if (new HashSet<>(scalePoints).size() != scalePoints.size()) {
            throw new IllegalArgumentException("scalePoints must contain distinct values");
        }
        for (int sp : scalePoints) {
            if (sp < seedDataset.atoms().size()) {
                throw new IllegalArgumentException(
                        "scale point " + sp + " must be >= seed dataset size " + seedDataset.atoms().size());
            }
        }
        if (warmupCount < 0) {
            throw new IllegalArgumentException("warmupCount must be >= 0, got: " + warmupCount);
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0, got: " + repetitionCount);
        }
        this.scalePoints = List.copyOf(scalePoints);
        this.warmupCount = warmupCount;
        this.repetitionCount = repetitionCount;
        this.seed = seed;
        this.generator = new DeterministicScaleCorpusGenerator(seed);
    }

    public VectorLoadBreakdownReport run(Path rootTempDir) throws IOException {
        Objects.requireNonNull(rootTempDir, "rootTempDir");
        var memoryOptions = new MonadaMemoryOptions(new LexicalEnrichmentPipeline(), false);
        var textNormalizer = memoryOptions.textNormalizer();
        var encoder = new SimpleFrequencyEncoder(DEFAULT_DIMENSIONS);
        var profile = VectorFormatProfile.currentFixedRaw(DEFAULT_DIMENSIONS);

        var results = new ArrayList<PointResult>();
        for (int corpusSize : scalePoints) {
            var dataset = generator.generate(seedDataset, corpusSize);
            var scaleDir = rootTempDir.resolve("vector-load-scale-" + corpusSize);
            Files.createDirectories(scaleDir);

            // Setup (untimed): the same persisted files as the #82 sweep and the #119 breakdown.
            LatencyScaleSweepRunner.seedStoreFiles(dataset, scaleDir, memoryOptions, textNormalizer, encoder);
            var store = FileFrequencyStore.openExisting(scaleDir, VECTOR_FILE, profile);
            var probe = new VectorLoadProbe(scaleDir, VECTOR_FILE, profile);
            var fixture = Fixture.prepare(probe);

            // Parity + structural counts (deterministic, one untimed pass of every operation).
            var counters = new VectorLoadProbe.Counters();
            var entries = probe.parseIndex(counters);
            probe.seekOnly(entries, counters);
            long perFloatChecksum = probe.perFloatRead(entries, counters);
            probe.bulkRead(entries, counters);
            long decodeChecksum = probe.decode(entries, fixture.segmentBytes(), fixture.decodeTarget(), counters);
            if (perFloatChecksum != decodeChecksum) {
                throw new IllegalStateException("per-float read and float decode disagree at N=" + corpusSize);
            }
            var expected = store.findAll();
            VectorLoadProbe.verifyParity("materialize",
                    expected, probe.materialize(entries, fixture.decodeTarget(), counters));
            VectorLoadProbe.verifyParity("per-float replica", expected, probe.replicaFindAll());
            VectorLoadProbe.verifyParity("bulk candidate", expected, probe.bulkCandidateFindAll());

            var samples = new long[PASS_COUNT][repetitionCount];
            for (int w = 0; w < warmupCount; w++) {
                for (int pass = 0; pass < PASS_COUNT; pass++) {
                    runPass((pass + w) % PASS_COUNT, probe, store, fixture);
                }
            }
            for (int rep = 0; rep < repetitionCount; rep++) {
                for (int i = 0; i < PASS_COUNT; i++) {
                    int pass = (i + rep) % PASS_COUNT;
                    samples[pass][rep] = runPass(pass, probe, store, fixture);
                }
            }

            results.add(new PointResult(
                    corpusSize, repetitionCount,
                    latency(samples[0]), latency(samples[1]), latency(samples[2]), latency(samples[3]),
                    latency(samples[4]), latency(samples[5]), latency(samples[6]), latency(samples[7]),
                    latency(samples[8]),
                    counters.toCounts()));
        }

        var decision = decide(results);
        return new VectorLoadBreakdownReport(datasetName, seed, DEFAULT_DIMENSIONS, profile.framing().name(),
                scalePoints, warmupCount, repetitionCount, results, decision, rationale(decision, results));
    }

    /** Untimed inputs for the split passes: parsed index, whole segment bytes and a decode target. */
    private record Fixture(List<VectorLoadProbe.IndexEntry> entries, byte[] segmentBytes, float[] decodeTarget) {
        static Fixture prepare(VectorLoadProbe probe) throws IOException {
            var entries = probe.parseIndex(new VectorLoadProbe.Counters());
            return new Fixture(entries, Files.readAllBytes(probe.vectorFile()),
                    new float[entries.size() * probe.dimensions()]);
        }
    }

    /**
     * Runs one pass and returns its wall-clock nanos. Order: 0 index parse, 1 seek, 2 per-float read,
     * 3 bulk read, 4 decode, 5 materialize, 6 replica, 7 bulk candidate, 8 production findAll.
     */
    private static long runPass(int pass, VectorLoadProbe probe, FileFrequencyStore store, Fixture fixture)
            throws IOException {
        var counters = new VectorLoadProbe.Counters();
        long start = System.nanoTime();
        long sink = switch (pass) {
            case 0 -> probe.parseIndex(counters).size();
            case 1 -> probe.seekOnly(fixture.entries(), counters);
            case 2 -> probe.perFloatRead(fixture.entries(), counters);
            case 3 -> probe.bulkRead(fixture.entries(), counters);
            case 4 -> probe.decode(fixture.entries(), fixture.segmentBytes(), fixture.decodeTarget(), counters);
            case 5 -> probe.materialize(fixture.entries(), fixture.decodeTarget(), counters).size();
            case 6 -> probe.replicaFindAll().size();
            case 7 -> probe.bulkCandidateFindAll().size();
            case 8 -> store.findAll().size();
            default -> throw new IllegalArgumentException("unknown pass " + pass);
        };
        long elapsed = System.nanoTime() - start;
        if (sink == Long.MIN_VALUE) {
            throw new IllegalStateException("unreachable sink value");
        }
        return elapsed;
    }

    private static PhaseLatency latency(long[] samples) {
        var list = new ArrayList<Long>(samples.length);
        for (long sample : samples) {
            list.add(sample);
        }
        var stats = ScaleTimingStatistics.from(list, List.of(), 0, 0);
        return new PhaseLatency(stats.medianNanos(), stats.p95Nanos());
    }

    private static PointResult largestScale(List<PointResult> results) {
        PointResult largest = null;
        for (var r : results) {
            if (largest == null || r.corpusSize() > largest.corpusSize()) {
                largest = r;
            }
        }
        return largest;
    }

    static Decision decide(List<PointResult> results) {
        var largest = largestScale(results);
        if (largest == null || largest.corpusSize() < VectorLoadBreakdownReport.MIN_DECISION_SCALE
                || largest.sampleCount() == 0 || largest.replicaFindAll().p50Nanos() == 0) {
            return Decision.INCONCLUSIVE;
        }
        boolean dominant = largest.share(largest.perFloatRead()) >= VectorLoadBreakdownReport.PER_FLOAT_DOMINANT_SHARE;
        boolean bulkMaterial = largest.share(largest.bulkCandidateFindAll())
                <= VectorLoadBreakdownReport.BULK_CANDIDATE_MAX_RATIO;
        return dominant && bulkMaterial
                ? Decision.PER_FLOAT_READ_DOMINANT_BULK_READ_JUSTIFIED
                : Decision.PER_FLOAT_READ_NOT_DOMINANT;
    }

    static String rationale(Decision decision, List<PointResult> results) {
        if (decision == Decision.INCONCLUSIVE) {
            return "Insufficient evidence: need timing samples and a largest scale point >= "
                    + VectorLoadBreakdownReport.MIN_DECISION_SCALE + ".";
        }
        var r = largestScale(results);
        var c = r.counts();
        var sb = new StringBuilder(String.format(Locale.ROOT,
                "N=%d: per-float read %.1f%% of the median replica load (%d read calls vs %d bulk reads for the"
                        + " same %d bytes); index parse %.1f%%, seek %.1f%%, bulk read %.1f%%, float decode %.1f%%,"
                        + " materialize %.1f%%. Bulk candidate %.2fx faster than the replica at p50; production"
                        + " findAll %.2fx versus the replica. ",
                r.corpusSize(), r.share(r.perFloatRead()) * 100, c.perFloatReadCalls(), c.bulkReadCalls(),
                c.bulkBytesRead(), r.share(r.indexParse()) * 100, r.share(r.seek()) * 100,
                r.share(r.bulkRead()) * 100, r.share(r.decode()) * 100, r.share(r.materialize()) * 100,
                r.speedupVersusReplica(r.bulkCandidateFindAll()), r.speedupVersusReplica(r.productionFindAll())));
        int dominantPct = (int) (VectorLoadBreakdownReport.PER_FLOAT_DOMINANT_SHARE * 100);
        int ratioPct = (int) (VectorLoadBreakdownReport.BULK_CANDIDATE_MAX_RATIO * 100);
        sb.append(decision == Decision.PER_FLOAT_READ_DOMINANT_BULK_READ_JUSTIFIED
                ? "The unbuffered per-float read hypothesis is supported: per-float reads are at least " + dominantPct
                + "% of the load and the bit-identical bulk candidate needs at most " + ratioPct + "% of the replica time,"
                + " so a bulk frame read in FileFrequencyStore (stage 2) is justified."
                : "The unbuffered per-float read hypothesis is not supported at the thresholds (per-float >= "
                + dominantPct + "% of the load and bulk candidate <= " + ratioPct + "% of the replica);"
                + " stage 2 is not justified by this evidence.");
        sb.append(" Single-machine, warm page cache, split passes with timer overhead; treat as exploratory.");
        return sb.toString();
    }
}
