package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.StoredVector;
import com.monada.storage.VectorFormatProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorLoadProbeTest {

    private static final String SEGMENT = "vectors/segment-000001.f32";
    private static final float NAN_WITH_PAYLOAD = Float.intBitsToFloat(0x7fc0_1234);

    private static final List<float[]> VALUES = List.of(
            new float[]{1f, -2.5f, 0f},
            new float[]{-0.0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY},
            new float[]{NAN_WITH_PAYLOAD, Float.MIN_VALUE, Float.MAX_VALUE},
            new float[]{3.25f, 1e-30f, -7f});

    private static FileFrequencyStore seed(Path root, VectorFormatProfile profile) throws IOException {
        var store = FileFrequencyStore.create(root, SEGMENT, profile);
        for (int i = 0; i < VALUES.size(); i++) {
            store.save("atom-" + i, new FrequencyVector(VALUES.get(i)));
        }
        return FileFrequencyStore.openExisting(root, SEGMENT, profile);
    }

    private static void assertAllPassesMatch(Path root, VectorFormatProfile profile) throws IOException {
        var store = seed(root, profile);
        var probe = new VectorLoadProbe(root, SEGMENT, profile);
        var expected = store.findAll();

        VectorLoadProbe.verifyParity("replica", expected, probe.replicaFindAll());
        VectorLoadProbe.verifyParity("bulk", expected, probe.bulkCandidateFindAll());

        var counters = new VectorLoadProbe.Counters();
        var entries = probe.parseIndex(counters);
        var decoded = new float[entries.size() * profile.dimensions()];
        long decodeChecksum = probe.decode(entries, Files.readAllBytes(root.resolve(SEGMENT)), decoded, counters);
        assertEquals(probe.perFloatRead(entries, counters), decodeChecksum);
        VectorLoadProbe.verifyParity("materialize", expected, probe.materialize(entries, decoded, counters));
    }

    @Test
    void allPassesMatchFindAllBitForBitOnFixedRawFrames(@TempDir Path root) throws IOException {
        assertAllPassesMatch(root, VectorFormatProfile.currentFixedRaw(3));
    }

    @Test
    void allPassesMatchFindAllBitForBitOnLengthPrefixedFrames(@TempDir Path root) throws IOException {
        assertAllPassesMatch(root, VectorFormatProfile.legacyLengthPrefixed(3));
    }

    @Test
    void reorderedIndexIsReadInIndexOrder(@TempDir Path root) throws IOException {
        var profile = VectorFormatProfile.currentFixedRaw(3);
        seed(root, profile);
        var index = FileFrequencyStore.resolveVectorMapPath(root);
        Files.write(index, Files.readAllLines(index).reversed(), StandardCharsets.UTF_8);
        var store = FileFrequencyStore.openExisting(root, SEGMENT, profile);
        var probe = new VectorLoadProbe(root, SEGMENT, profile);

        var replica = probe.replicaFindAll();
        assertEquals("atom-3", replica.getFirst().atomId());
        VectorLoadProbe.verifyParity("replica", store.findAll(), replica);
        VectorLoadProbe.verifyParity("bulk", store.findAll(), probe.bulkCandidateFindAll());
    }

    @Test
    void countersAreExactPerPass(@TempDir Path root) throws IOException {
        var profile = VectorFormatProfile.legacyLengthPrefixed(3);
        seed(root, profile);
        var probe = new VectorLoadProbe(root, SEGMENT, profile);
        var c = new VectorLoadProbe.Counters();
        var entries = probe.parseIndex(c);
        probe.seekOnly(entries, c);
        probe.perFloatRead(entries, c);
        probe.bulkRead(entries, c, null);
        var decoded = new float[entries.size() * 3];
        probe.decode(entries, Files.readAllBytes(root.resolve(SEGMENT)), decoded, c);
        probe.materialize(entries, decoded, c);

        int n = VALUES.size();
        var counts = c.toCounts();
        assertEquals(n, counts.indexLinesParsed());
        assertEquals(n, counts.seeks());
        assertEquals(n * (3 + 1L), counts.perFloatReadCalls(), "one readInt prefix plus one readFloat per value");
        assertEquals(n * profile.frameSizeInBytes(), counts.perFloatBytesRead());
        assertEquals(n, counts.bulkReadCalls());
        assertEquals(counts.perFloatBytesRead(), counts.bulkBytesRead());
        assertEquals(n * 3L, counts.floatsDecoded());
        assertEquals(2L * n, counts.arraysAllocated());
        assertEquals(2L * n, counts.objectsAllocated());
    }

    @Test
    void seekAndBulkReadPassesAreVerifiedAgainstTheIndexAndSegmentBytes(@TempDir Path root) throws IOException {
        var profile = VectorFormatProfile.legacyLengthPrefixed(3);
        seed(root, profile);
        var index = FileFrequencyStore.resolveVectorMapPath(root);
        Files.write(index, Files.readAllLines(index).reversed(), StandardCharsets.UTF_8);
        var probe = new VectorLoadProbe(root, SEGMENT, profile);
        var entries = probe.parseIndex(new VectorLoadProbe.Counters());
        var segmentBytes = Files.readAllBytes(root.resolve(SEGMENT));

        long seekChecksum = probe.seekOnly(entries, new VectorLoadProbe.Counters());
        VectorLoadProbe.verifySeeks(entries, seekChecksum);
        assertThrows(IllegalStateException.class, () -> VectorLoadProbe.verifySeeks(entries, seekChecksum + 1));

        var captured = new byte[probe.captureSize(entries)];
        probe.bulkRead(entries, new VectorLoadProbe.Counters(), captured);
        probe.verifyBulkFrames(entries, segmentBytes, captured);

        captured[captured.length - 1] ^= 1;
        var ex = assertThrows(IllegalStateException.class,
                () -> probe.verifyBulkFrames(entries, segmentBytes, captured));
        assertTrue(ex.getMessage().contains("atomId=atom-0"), "index is reversed, so the last frame is atom-0");
    }

    @Test
    void verifyParityDetectsIdOrderSizeAndBitDivergence() {
        var a = new StoredVector("a", new FrequencyVector(new float[]{1f, 0.0f}));
        var b = new StoredVector("b", new FrequencyVector(new float[]{2f, 3f}));
        var negativeZero = new StoredVector("a", new FrequencyVector(new float[]{1f, -0.0f}));
        var otherNan = new StoredVector("b", new FrequencyVector(new float[]{2f, NAN_WITH_PAYLOAD}));
        var nan = new StoredVector("b", new FrequencyVector(new float[]{2f, Float.NaN}));

        VectorLoadProbe.verifyParity("same", List.of(a, b), List.of(a, b));
        assertThrows(IllegalStateException.class, () -> VectorLoadProbe.verifyParity("order", List.of(a, b), List.of(b, a)));
        assertThrows(IllegalStateException.class, () -> VectorLoadProbe.verifyParity("size", List.of(a, b), List.of(a)));
        var ex = assertThrows(IllegalStateException.class,
                () -> VectorLoadProbe.verifyParity("bits", List.of(a), List.of(negativeZero)));
        assertTrue(ex.getMessage().contains("dimension 1"));
        assertThrows(IllegalStateException.class, () -> VectorLoadProbe.verifyParity("nan", List.of(nan), List.of(otherNan)));
    }

    @Test
    void corruptLengthPrefixIsRejectedByEveryReadingPass(@TempDir Path root) throws IOException {
        var profile = VectorFormatProfile.legacyLengthPrefixed(3);
        seed(root, profile);
        // Overwrite the second frame's length prefix (3) with 2 after the store validated its headers.
        try (var file = new RandomAccessFile(root.resolve(SEGMENT).toFile(), "rw")) {
            file.seek(profile.frameSizeInBytes());
            file.writeInt(2);
        }
        var probe = new VectorLoadProbe(root, SEGMENT, profile);
        var entries = probe.parseIndex(new VectorLoadProbe.Counters());

        assertThrows(IOException.class, probe::replicaFindAll);
        assertThrows(IOException.class, probe::bulkCandidateFindAll);
        var ex = assertThrows(IOException.class, () -> probe.perFloatRead(entries, new VectorLoadProbe.Counters()));
        assertTrue(ex.getMessage().contains("atomId=atom-1"));
    }
}
