package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.core.io.ChunkedFloatReader;
import com.monada.speech.storage.StoredSpeechFeatureVector;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.StoredVector;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Evaluation-only replicas of the bulk read path of {@code FileFrequencyStore.findAll()} (fixed-raw profile) and
 * {@code FileSpeechFeatureStore.findAll()} (length-prefixed frames, last-wins by sample id), used to isolate the
 * cost of {@link FrequencyVector}'s defensive copy on construction (Issue #135).
 *
 * <p>Both replicas do the same index parse, offset check, seek and {@link ChunkedFloatReader} read as production
 * and differ only in how the decoded {@code float[]} is materialized:
 * <ul>
 *     <li>{@link Materialization#COPY} calls {@code new FrequencyVector(values)}, which copies the array again, as
 *     production does;</li>
 *     <li>{@link Materialization#ADOPT} wraps the decoded array in {@link AdoptedVector}, an object with the same
 *     shape as {@code FrequencyVector} (one array reference) but no copy, modelling a no-copy construction path.</li>
 * </ul>
 * Both wrap each vector in a {@link Loaded} record with the same shape as {@code StoredVector}. Callers check both
 * replicas against the real store with {@link #verifyParity} before timing.
 */
final class VectorCopyProbe {

    enum StoreKind {
        /** {@code FileFrequencyStore}, fixed-raw frames of declared dimensions, index order. */
        FREQUENCY,
        /** {@code FileSpeechFeatureStore}, {@code int dims} header per frame, last-wins by sample id. */
        SPEECH
    }

    enum Materialization {
        COPY,
        ADOPT
    }

    /** Same shape as {@code FrequencyVector}: one reference to a {@code float[]}, adopted without a copy. */
    record AdoptedVector(float[] values) {
    }

    /** Same shape as {@code StoredVector}/{@code StoredSpeechFeatureVector}: an id and a vector object. */
    record Loaded(String id, Object vector) {
    }

    private record IndexEntry(String id, long offset) {
    }

    private final StoreKind kind;
    private final Path vectorFile;
    private final Path vectorMap;
    private final int fixedDimensions;

    private VectorCopyProbe(StoreKind kind, Path vectorFile, Path vectorMap, int fixedDimensions) {
        this.kind = kind;
        this.vectorFile = vectorFile;
        this.vectorMap = vectorMap;
        this.fixedDimensions = fixedDimensions;
    }

    /** Probe over a fixed-raw {@code FileFrequencyStore} with the given declared dimensions. */
    static VectorCopyProbe frequency(Path root, String vectorSegment, int dimensions) {
        Objects.requireNonNull(root, "root");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be > 0");
        }
        return new VectorCopyProbe(StoreKind.FREQUENCY, root.resolve(vectorSegment),
                FileFrequencyStore.resolveVectorMapPath(root), dimensions);
    }

    /** Probe over a {@code FileSpeechFeatureStore} with its default segment and index paths. */
    static VectorCopyProbe speech(Path root) {
        Objects.requireNonNull(root, "root");
        return new VectorCopyProbe(StoreKind.SPEECH, root.resolve("features/speech-features-000001.f32"),
                root.resolve("indexes/speech-feature-map.idx"), 0);
    }

    StoreKind kind() {
        return kind;
    }

    /** One {@code findAll()}-equivalent load with the given materialization. */
    List<Loaded> load(Materialization materialization) throws IOException {
        var entries = parseIndex();
        long fileSize = Files.size(vectorFile);
        var floatReader = new ChunkedFloatReader();
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            if (kind == StoreKind.FREQUENCY) {
                var vectors = new ArrayList<Loaded>(entries.size());
                for (var entry : entries) {
                    requireOffset(entry, fileSize);
                    file.seek(entry.offset());
                    vectors.add(read(file, entry, fixedDimensions, floatReader, materialization));
                }
                return vectors;
            }
            var byId = new LinkedHashMap<String, Loaded>();
            for (var entry : entries) {
                requireOffset(entry, fileSize);
                file.seek(entry.offset());
                int dimensions = file.readInt();
                if (dimensions <= 0 || dimensions > 1 << 20) {
                    throw new IOException("Invalid vector dimensions (" + dimensions + ") for id=" + entry.id());
                }
                byId.put(entry.id(), read(file, entry, dimensions, floatReader, materialization));
            }
            return List.copyOf(byId.values());
        }
    }

    private static Loaded read(RandomAccessFile file, IndexEntry entry, int dimensions,
                               ChunkedFloatReader floatReader, Materialization materialization) throws IOException {
        var values = new float[dimensions];
        floatReader.readFloats(file, values);
        Object vector = materialization == Materialization.COPY ? new FrequencyVector(values) : new AdoptedVector(values);
        return new Loaded(entry.id(), vector);
    }

    private List<IndexEntry> parseIndex() throws IOException {
        try (Stream<String> lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).map(VectorCopyProbe::parseEntry).toList();
        }
    }

    private static IndexEntry parseEntry(String line) {
        String[] parts = line.split("\t", 2);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw new IllegalStateException("Malformed index entry: " + line);
        }
        try {
            return new IndexEntry(parts[0], Long.parseLong(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid offset in index entry: " + line, e);
        }
    }

    private static void requireOffset(IndexEntry entry, long fileSize) throws IOException {
        if (entry.offset() < 0 || entry.offset() >= fileSize) {
            throw new IOException("Offset (" + entry.offset() + ") out of range for id=" + entry.id());
        }
    }

    /** Ids and raw float bits of a production {@code FileFrequencyStore.findAll()} result. */
    static List<Loaded> fromFrequencyStore(List<StoredVector> vectors) {
        return vectors.stream().map(v -> new Loaded(v.atomId(), v.vector())).toList();
    }

    /** Ids and raw float bits of a production {@code FileSpeechFeatureStore.findAll()} result. */
    static List<Loaded> fromSpeechStore(List<StoredSpeechFeatureVector> vectors) {
        return vectors.stream().map(v -> new Loaded(v.sampleId(), v.vector())).toList();
    }

    /** Copy of the decoded floats held by a {@link Loaded} vector, whichever materialization produced it. */
    static float[] valuesOf(Loaded loaded) {
        return switch (loaded.vector()) {
            case FrequencyVector v -> v.values();
            case AdoptedVector v -> v.values().clone();
            default -> throw new IllegalStateException("unexpected vector type " + loaded.vector().getClass());
        };
    }

    /**
     * Throws unless {@code actual} has the same ids in the same order as {@code expected} and every float has
     * identical raw bits.
     */
    static void verifyParity(String what, List<Loaded> expected, List<Loaded> actual) {
        if (expected.size() != actual.size()) {
            throw new IllegalStateException(what + " diverged from findAll(): size " + actual.size()
                    + " != " + expected.size());
        }
        for (int i = 0; i < expected.size(); i++) {
            var e = expected.get(i);
            var a = actual.get(i);
            if (!e.id().equals(a.id())) {
                throw new IllegalStateException(what + " diverged from findAll() at index " + i + ": id "
                        + a.id() + " != " + e.id());
            }
            var ev = valuesOf(e);
            var av = valuesOf(a);
            if (ev.length != av.length) {
                throw new IllegalStateException(what + " diverged from findAll() for id=" + e.id()
                        + ": dimensions " + av.length + " != " + ev.length);
            }
            for (int d = 0; d < ev.length; d++) {
                if (Float.floatToRawIntBits(ev[d]) != Float.floatToRawIntBits(av[d])) {
                    throw new IllegalStateException(what + " diverged from findAll() for id=" + e.id()
                            + " at dimension " + d);
                }
            }
        }
    }
}
