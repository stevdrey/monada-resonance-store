package com.monada.storage;

import com.monada.core.FrequencyVector;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface FrequencyStore {
    void save(String atomId, FrequencyVector vector) throws IOException;

    Optional<FrequencyVector> findByAtomId(String atomId) throws IOException;

    List<StoredVector> findAll() throws IOException;
}
