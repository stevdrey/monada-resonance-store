package com.monada.speech.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.monada.core.FrequencyVector;

class FileSpeechFeatureStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsFrequencyVectorBySampleId() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector = new FrequencyVector(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f});

        store.save("sample1", vector);

        var found = store.findBySampleId("sample1");
        assertTrue(found.isPresent());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f}, found.get().values());
    }

    @Test
    void returnsEmptyWhenSampleIdNotFound() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);

        var found = store.findBySampleId("nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    void preservesVectorDimensionsAndValues() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        float[] values = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
        var vector = new FrequencyVector(values);

        store.save("sample1", vector);

        var found = store.findBySampleId("sample1").orElseThrow();
        assertEquals(8, found.dimensions());
        assertArrayEquals(values, found.values());
    }

    @Test
    void returnsAllStoredFeatureVectors() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector1 = new FrequencyVector(new float[]{0.1f, 0.2f});
        var vector2 = new FrequencyVector(new float[]{0.3f, 0.4f});
        var vector3 = new FrequencyVector(new float[]{0.5f, 0.6f});

        store.save("sample1", vector1);
        store.save("sample2", vector2);
        store.save("sample3", vector3);

        var all = store.findAll();
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(v -> v.sampleId().equals("sample1")));
        assertTrue(all.stream().anyMatch(v -> v.sampleId().equals("sample2")));
        assertTrue(all.stream().anyMatch(v -> v.sampleId().equals("sample3")));
    }

    @Test
    void handlesDuplicateSampleIdsDeterministically() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var original = new FrequencyVector(new float[]{0.1f, 0.2f});
        var updated = new FrequencyVector(new float[]{0.3f, 0.4f});

        store.save("sample1", original);
        store.save("sample1", updated);

        var found = store.findBySampleId("sample1");
        assertTrue(found.isPresent());
        // Last-wins: should return the updated vector
        assertArrayEquals(new float[]{0.3f, 0.4f}, found.get().values());

        var all = store.findAll();
        assertEquals(1, all.size());
        assertEquals("sample1", all.get(0).sampleId());
        assertArrayEquals(new float[]{0.3f, 0.4f}, all.get(0).vector().values());
    }

    @Test
    void worksAfterReopeningStore() throws IOException {
        var vector = new FrequencyVector(new float[]{0.1f, 0.2f, 0.3f});

        // First session: save
        var store = new FileSpeechFeatureStore(tempDir);
        store.save("sample1", vector);

        // Second session: reopen and read
        var reopenedStore = new FileSpeechFeatureStore(tempDir);
        var found = reopenedStore.findBySampleId("sample1");

        assertTrue(found.isPresent());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, found.get().values());
    }

    @Test
    void vectorValuesAreImmutableAfterRetrieval() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector = new FrequencyVector(new float[]{0.1f, 0.2f, 0.3f});

        store.save("sample1", vector);

        var found = store.findBySampleId("sample1").orElseThrow();
        // Modifying returned array should not affect the vector
        var values = found.values();
        values[0] = 999f;

        // Re-fetch should still have original value
        var refetched = store.findBySampleId("sample1").orElseThrow();
        assertEquals(0.1f, refetched.values()[0]);
    }

    @Test
    void handlesSingleDimensionVector() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector = new FrequencyVector(new float[]{0.5f});

        store.save("sample1", vector);

        var found = store.findBySampleId("sample1").orElseThrow();
        assertEquals(1, found.dimensions());
        assertArrayEquals(new float[]{0.5f}, found.values());
    }

    @Test
    void handlesLargeDimensionVectors() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        float[] largeValues = new float[1000];
        for (int i = 0; i < largeValues.length; i++) {
            largeValues[i] = i * 0.001f;
        }
        var vector = new FrequencyVector(largeValues);

        store.save("sample1", vector);

        var found = store.findBySampleId("sample1").orElseThrow();
        assertEquals(1000, found.dimensions());
        assertArrayEquals(largeValues, found.values());
    }

    @Test
    void storedSpeechFeatureVectorRecordHoldsDataCorrectly() {
        var vector = new FrequencyVector(new float[]{0.1f, 0.2f, 0.3f});
        var stored = new StoredSpeechFeatureVector("sample1", vector);

        assertEquals("sample1", stored.sampleId());
        assertEquals(vector, stored.vector());
    }

    @Test
    void rejectsNullSampleIdOnSave() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector = new FrequencyVector(new float[]{0.1f});
        assertThrows(NullPointerException.class, () -> store.save(null, vector));
    }

    @Test
    void rejectsBlankSampleIdOnSave() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector = new FrequencyVector(new float[]{0.1f});
        assertThrows(IllegalArgumentException.class, () -> store.save("  ", vector));
    }

    @Test
    void rejectsSampleIdWithTabOnSave() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector = new FrequencyVector(new float[]{0.1f});
        assertThrows(IllegalArgumentException.class, () -> store.save("sample\t1", vector));
    }

    @Test
    void rejectsSampleIdWithNewlineOnSave() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        var vector = new FrequencyVector(new float[]{0.1f});
        assertThrows(IllegalArgumentException.class, () -> store.save("sample\n1", vector));
    }

    @Test
    void rejectsNullSampleIdOnFind() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        assertThrows(NullPointerException.class, () -> store.findBySampleId(null));
    }

    @Test
    void rejectsBlankSampleIdOnFind() throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.findBySampleId(""));
    }
}
