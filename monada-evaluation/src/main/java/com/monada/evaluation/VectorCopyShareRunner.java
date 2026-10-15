package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.core.FrequencyVector;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.evaluation.VectorCopyProbe.Materialization;
import com.monada.evaluation.VectorCopyProbe.StoreKind;
import com.monada.evaluation.VectorCopyShareReport.Decision;
import com.monada.evaluation.VectorCopyShareReport.PointResult;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.VectorFormatProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

import static com.monada.evaluation.ScanPhaseBreakdownRunner.latency;

/**
 * Evaluation-only harness for Issue #135. It measures the share of {@code FileFrequencyStore.findAll()} and
 * {@code FileSpeechFeatureStore.findAll()} spent on {@code FrequencyVector}'s defensive copy of each decoded
 * vector, by timing production against two {@link VectorCopyProbe} replicas that differ only in that copy.
 *
 * <p>Setup is untimed. Each scale point verifies both replicas against the real store, then rotates production,
 * copying and adopting passes on every repetition and records wall-clock time and, when available, the bytes the
 * measuring thread allocated per call. The production read paths are never modified here.
 */
public final class VectorCopyShareRunner {

    public static final List<Integer> DEFAULT_SCALE_POINTS = LatencyScaleSweepRunner.DEFAULT_SCALE_POINTS;
    public static final int DEFAULT_WARMUP_COUNT = 2;
    public static final int DEFAULT_REPETITION_COUNT = 20;

    static final int FREQUENCY_DIMENSIONS = 128;
    static final int SPEECH_DIMENSIONS = 64;
    private static final String VECTOR_FILE = "vectors/segment-000001.f32";
    private static final int PASS_COUNT = 3;

    private final String datasetName;
    private final EvaluationDataset seedDataset;
    private final List<Integer> scalePoints;
    private final int warmupCount;
    private final int repetitionCount;
    private final long seed;
    private final DeterministicScaleCorpusGenerator generator;
    private final AllocationMeter allocationMeter = AllocationMeter.create();

    public VectorCopyShareRunner() {
        this("expanded-technology", ExpandedTechnologyDataset.get(), DEFAULT_SCALE_POINTS,
                DEFAULT_WARMUP_COUNT, DEFAULT_REPETITION_COUNT, DeterministicScaleCorpusGenerator.DEFAULT_SEED);
    }

    public VectorCopyShareRunner(
            String datasetName,
            EvaluationDataset seedDataset,
            List<Integer> scalePoints,
            int warmupCount,
            int repetitionCount,
            long seed) {
        this.datasetName = Objects.requireNonNull(datasetName, "datasetName");
        this.seedDataset = Objects.requireNonNull(seedDataset, "seedDataset");
        ScanPhaseBreakdownRunner.validateScaleConfig(seedDataset, scalePoints, warmupCount, repetitionCount);
        this.scalePoints = List.copyOf(scalePoints);
        this.warmupCount = warmupCount;
        this.repetitionCount = repetitionCount;
        this.seed = seed;
        this.generator = new DeterministicScaleCorpusGenerator(seed);
    }

    public VectorCopyShareReport run(Path rootTempDir) throws IOException {
        Objects.requireNonNull(rootTempDir, "rootTempDir");
        var memoryOptions = new MonadaMemoryOptions(new LexicalEnrichmentPipeline(), false);
        var textNormalizer = memoryOptions.textNormalizer();
        var encoder = new SimpleFrequencyEncoder(FREQUENCY_DIMENSIONS);
        var profile = VectorFormatProfile.currentFixedRaw(FREQUENCY_DIMENSIONS);

        var results = new ArrayList<PointResult>();
        for (int corpusSize : scalePoints) {
            // Setup (untimed): the same persisted frequency files as the #82 sweep and the #130 breakdown.
            var frequencyDir = rootTempDir.resolve("copy-share-frequency-" + corpusSize);
            Files.createDirectories(frequencyDir);
            LatencyScaleSweepRunner.seedStoreFiles(generator.generate(seedDataset, corpusSize), frequencyDir,
                    memoryOptions, textNormalizer, encoder);
            var frequencyStore = FileFrequencyStore.openExisting(frequencyDir, VECTOR_FILE, profile);
            var frequencyProbe = VectorCopyProbe.frequency(frequencyDir, VECTOR_FILE, FREQUENCY_DIMENSIONS);
            results.add(measure(frequencyProbe, corpusSize, FREQUENCY_DIMENSIONS, frequencyStore::findAll,
                    () -> VectorCopyProbe.fromFrequencyStore(frequencyStore.findAll())));

            var speechDir = rootTempDir.resolve("copy-share-speech-" + corpusSize);
            var speechStore = new FileSpeechFeatureStore(speechDir);
            seedSpeechStore(speechStore, corpusSize, seed);
            var speechProbe = VectorCopyProbe.speech(speechDir);
            results.add(measure(speechProbe, corpusSize, SPEECH_DIMENSIONS, speechStore::findAll,
                    () -> VectorCopyProbe.fromSpeechStore(speechStore.findAll())));
        }

        var decision = decide(results);
        // Derived from the points: false when accounting is unsupported or no repetition was measured.
        boolean allocationMeasured = !results.isEmpty() && results.stream().allMatch(PointResult::allocationMeasured);
        return new VectorCopyShareReport(datasetName, seed, FREQUENCY_DIMENSIONS, SPEECH_DIMENSIONS, scalePoints,
                warmupCount, repetitionCount, allocationMeasured, results, decision,
                rationale(decision, results));
    }

    @FunctionalInterface
    private interface Load<T extends List<?>> {
        T run() throws IOException;
    }

    /**
     * Times {@code production} (the store's own {@code findAll()}, untouched) against both replicas;
     * {@code parityView} is the same call mapped to {@link VectorCopyProbe.Loaded} and is never timed.
     */
    private PointResult measure(VectorCopyProbe probe, int corpusSize, int dimensions, Load<?> production,
                                Load<List<VectorCopyProbe.Loaded>> parityView) throws IOException {
        // Parity (untimed): both replicas must return exactly what the real store returns.
        var expected = parityView.run();
        if (expected.size() != corpusSize) {
            throw new IllegalStateException(probe.kind() + " store returned " + expected.size()
                    + " vectors, expected " + corpusSize);
        }
        VectorCopyProbe.verifyParity(probe.kind() + " copying replica", expected, probe.load(Materialization.COPY));
        VectorCopyProbe.verifyParity(probe.kind() + " adopting replica", expected, probe.load(Materialization.ADOPT));

        var nanos = new ArrayList<List<Long>>(PASS_COUNT);
        var allocated = new ArrayList<List<Long>>(PASS_COUNT);
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            nanos.add(new ArrayList<>(repetitionCount));
            allocated.add(new ArrayList<>(repetitionCount));
        }
        var sample = new long[2];
        for (int w = 0; w < warmupCount; w++) {
            for (int pass = 0; pass < PASS_COUNT; pass++) {
                runPass((pass + w) % PASS_COUNT, probe, production, sample);
            }
        }
        for (int rep = 0; rep < repetitionCount; rep++) {
            for (int i = 0; i < PASS_COUNT; i++) {
                int pass = (i + rep) % PASS_COUNT;
                runPass(pass, probe, production, sample);
                nanos.get(pass).add(sample[0]);
                allocated.get(pass).add(sample[1]);
            }
        }
        boolean measured = allocationMeter.supported() && repetitionCount > 0;
        return new PointResult(probe.kind(), corpusSize, dimensions, repetitionCount,
                latency(nanos.get(0)), latency(nanos.get(1)), latency(nanos.get(2)),
                measured ? median(allocated.get(0)) : -1,
                measured ? median(allocated.get(1)) : -1,
                measured ? median(allocated.get(2)) : -1);
    }

    /**
     * Runs one pass and stores its wall-clock nanos and allocated bytes in {@code sample}. Order: 0 production
     * findAll, 1 copying replica, 2 adopting replica.
     */
    private void runPass(int pass, VectorCopyProbe probe, Load<?> production, long[] sample) throws IOException {
        long bytesBefore = allocationMeter.currentThreadAllocatedBytes();
        long start = System.nanoTime();
        int loaded = switch (pass) {
            case 0 -> production.run().size();
            case 1 -> probe.load(Materialization.COPY).size();
            case 2 -> probe.load(Materialization.ADOPT).size();
            default -> throw new IllegalArgumentException("unknown pass " + pass);
        };
        long elapsed = System.nanoTime() - start;
        long bytes = allocationMeter.currentThreadAllocatedBytes() - bytesBefore;
        if (loaded == 0) {
            throw new IllegalStateException("pass " + pass + " loaded no vectors");
        }
        sample[0] = elapsed;
        sample[1] = bytes;
    }

    /** Seeds {@code corpusSize} speech feature vectors through the production {@code save} path. */
    static void seedSpeechStore(FileSpeechFeatureStore store, int corpusSize, long seed) throws IOException {
        var random = new Random(seed);
        for (int i = 0; i < corpusSize; i++) {
            var values = new float[SPEECH_DIMENSIONS];
            for (int d = 0; d < values.length; d++) {
                values[d] = (float) random.nextGaussian();
            }
            store.save(String.format(Locale.ROOT, "speech-sample-%06d", i), new FrequencyVector(values));
        }
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        var sorted = values.stream().sorted().toList();
        return sorted.get((sorted.size() - 1) / 2);
    }

    private static List<PointResult> largestPerStore(List<PointResult> results) {
        var largest = new ArrayList<PointResult>();
        for (var kind : StoreKind.values()) {
            results.stream().filter(r -> r.store() == kind)
                    .max((a, b) -> Integer.compare(a.corpusSize(), b.corpusSize()))
                    .ifPresent(largest::add);
        }
        return largest;
    }

    /** True when every store has timing samples at its largest point and that point is N >= 10,000. */
    private static boolean timingEvidenceComplete(List<PointResult> largest) {
        if (largest.size() != StoreKind.values().length) {
            return false;
        }
        for (var r : largest) {
            if (r.corpusSize() < VectorCopyShareReport.MIN_DECISION_SCALE || r.sampleCount() == 0
                    || r.copyingReplica().p50Nanos() == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Meaningful as soon as one store reaches either threshold. Otherwise negligible only when every store's
     * allocation was measured: without it the allocation criterion was never observed, so the verdict is
     * inconclusive rather than negligible.
     */
    static Decision decide(List<PointResult> results) {
        var largest = largestPerStore(results);
        if (!timingEvidenceComplete(largest)) {
            return Decision.INCONCLUSIVE;
        }
        boolean allocationMeasured = true;
        for (var r : largest) {
            if (r.timeShare() >= VectorCopyShareReport.MEANINGFUL_TIME_SHARE
                    || (r.allocationMeasured()
                    && r.allocationShare() >= VectorCopyShareReport.MEANINGFUL_ALLOCATION_SHARE)) {
                return Decision.COPY_SHARE_MEANINGFUL_REMOVE_COPY;
            }
            allocationMeasured &= r.allocationMeasured();
        }
        return allocationMeasured ? Decision.COPY_SHARE_NEGLIGIBLE : Decision.INCONCLUSIVE;
    }

    static String rationale(Decision decision, List<PointResult> results) {
        if (decision == Decision.INCONCLUSIVE) {
            if (timingEvidenceComplete(largestPerStore(results))) {
                return "Insufficient evidence: no store reaches the time threshold and allocation was not measured"
                        + " for every store (per-thread allocation accounting unsupported or disabled), so the"
                        + " allocation criterion was never observed.";
            }
            return "Insufficient evidence: need timing samples and a largest scale point >= "
                    + VectorCopyShareReport.MIN_DECISION_SCALE + " for every store.";
        }
        var sb = new StringBuilder();
        for (var r : largestPerStore(results)) {
            sb.append(String.format(Locale.ROOT,
                    "%s N=%d: the copy is %.1f%% of the copying findAll() p50 (%.1f%% at p95) and %s of its allocated"
                            + " bytes (%s measured vs %d expected). ",
                    r.store(), r.corpusSize(), r.timeShare() * 100, r.timeShareP95() * 100,
                    r.allocationMeasured() ? String.format(Locale.ROOT, "%.1f%%", r.allocationShare() * 100) : "n/a",
                    r.allocationMeasured() ? Long.toString(r.copyingBytes() - r.adoptingBytes()) : "n/a",
                    r.expectedCopyBytes()));
        }
        int timePct = (int) Math.round(VectorCopyShareReport.MEANINGFUL_TIME_SHARE * 100);
        int allocPct = (int) Math.round(VectorCopyShareReport.MEANINGFUL_ALLOCATION_SHARE * 100);
        sb.append(decision == Decision.COPY_SHARE_MEANINGFUL_REMOVE_COPY
                ? "At least one store reaches the threshold (>= " + timePct + "% of p50 or >= " + allocPct
                + "% of allocated bytes), so a no-copy FrequencyVector construction path for the stores (stage 2)"
                + " is justified."
                : "No store reaches the threshold (>= " + timePct + "% of p50 or >= " + allocPct
                + "% of allocated bytes); stage 2 is not justified by this evidence.");
        sb.append(" Single-machine, warm page cache; treat as exploratory.");
        return sb.toString();
    }
}
