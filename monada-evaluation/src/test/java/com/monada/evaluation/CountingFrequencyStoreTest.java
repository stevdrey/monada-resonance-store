package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.storage.FrequencyStore;
import com.monada.storage.StoredVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CountingFrequencyStoreTest {

    private static final class InMemoryStore implements FrequencyStore {
        private final List<StoredVector> vectors = new ArrayList<>();

        @Override
        public void save(String atomId, FrequencyVector vector) {
            vectors.add(new StoredVector(atomId, vector));
        }

        @Override
        public Optional<FrequencyVector> findByAtomId(String atomId) {
            return vectors.stream().filter(v -> v.atomId().equals(atomId)).map(StoredVector::vector).findFirst();
        }

        @Override
        public List<StoredVector> findAll() {
            return List.copyOf(vectors);
        }
    }

    @Test
    void countsEveryVectorReadByFindAllAndDelegatesTheRest() throws IOException {
        var counting = new CountingFrequencyStore(new InMemoryStore());
        counting.save("a", new FrequencyVector(new float[] {1f}));
        counting.save("b", new FrequencyVector(new float[] {2f}));

        assertEquals(0, counting.scannedVectors());
        assertEquals(2, counting.findAll().size());
        assertEquals(2, counting.scannedVectors());
        counting.findAll();
        assertEquals(4, counting.scannedVectors());
        assertEquals(true, counting.findByAtomId("a").isPresent());
        assertEquals(4, counting.scannedVectors());
    }
}
