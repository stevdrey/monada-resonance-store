package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.storage.FrequencyStore;
import com.monada.storage.StoredVector;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluation-only {@link FrequencyStore} decorator that counts how many stored vectors a
 * linear scan reads, so structural scan metrics are measured rather than assumed.
 */
final class CountingFrequencyStore implements FrequencyStore {

    private final FrequencyStore delegate;
    private long scannedVectors;

    CountingFrequencyStore(FrequencyStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    long scannedVectors() {
        return scannedVectors;
    }

    @Override
    public void save(String atomId, FrequencyVector vector) throws IOException {
        delegate.save(atomId, vector);
    }

    @Override
    public Optional<FrequencyVector> findByAtomId(String atomId) throws IOException {
        return delegate.findByAtomId(atomId);
    }

    @Override
    public List<StoredVector> findAll() throws IOException {
        var all = delegate.findAll();
        scannedVectors += all.size();
        return all;
    }
}
