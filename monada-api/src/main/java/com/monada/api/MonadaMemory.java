package com.monada.api;

import com.monada.core.KnowledgeAtom;
import com.monada.encoder.FrequencyEncoder;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.index.ResonanceIndex;
import com.monada.storage.AtomStore;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.FileManifestStore;
import com.monada.storage.FrequencyStore;
import com.monada.storage.Manifest;
import com.monada.storage.ManifestStore;
import com.monada.storage.StoredVector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class MonadaMemory {

    private static final String MANIFEST_VERSION = "0.1";
    private static final int DEFAULT_DIMENSIONS = 128;
    private static final String DEFAULT_VECTOR_SEGMENT = "vectors/segment-000001.f32";
    private static final String DEFAULT_ATOM_SEGMENT = "atoms/segment-000001.log";

    private final FrequencyEncoder encoder;
    private final AtomStore atomStore;
    private final FrequencyStore frequencyStore;
    private final ResonanceIndex resonanceIndex;

    private MonadaMemory(FrequencyEncoder encoder, AtomStore atomStore, FrequencyStore frequencyStore, ResonanceIndex resonanceIndex) {
        this.encoder = encoder;
        this.atomStore = atomStore;
        this.frequencyStore = frequencyStore;
        this.resonanceIndex = resonanceIndex;
    }

    public static MonadaMemory open(String path) {
        return open(Path.of(path));
    }

    public static MonadaMemory open(Path path) {
        try {
            ManifestStore manifestStore = new FileManifestStore(path);
            Optional<Manifest> existing = manifestStore.load();
            Manifest manifest;
            if (existing.isPresent()) {
                manifest = existing.get();
            } else {
                manifest = new Manifest(
                        MANIFEST_VERSION, DEFAULT_DIMENSIONS, DEFAULT_VECTOR_SEGMENT, DEFAULT_ATOM_SEGMENT);
                manifestStore.save(manifest);
            }

            AtomStore atomStore = new FileAtomStore(path, manifest.atomSegment());
            FrequencyStore frequencyStore = new FileFrequencyStore(
                    path, manifest.vectorSegment(), manifest.dimensions());
            validateDimensions(frequencyStore, manifest.dimensions());
            FrequencyEncoder encoder = new SimpleFrequencyEncoder(manifest.dimensions());
            ResonanceIndex resonanceIndex = new LinearScanResonanceIndex(atomStore, frequencyStore);
            return new MonadaMemory(encoder, atomStore, frequencyStore, resonanceIndex);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void validateDimensions(FrequencyStore frequencyStore, int expected) throws IOException {
        for (StoredVector stored : frequencyStore.findAll()) {
            int actual = stored.vector().dimensions();
            if (actual != expected) {
                throw new IllegalStateException(
                        "Manifest dimensions (" + expected + ") do not match stored vector dimensions (" + actual
                                + ") for atomId=" + stored.atomId());
            }
        }
    }

    public KnowledgeAtom remember(String text) {
        try {
            KnowledgeAtom atom = KnowledgeAtom.text(text);
            // Persist vector first: a partial failure leaves an orphan vector that search
            // safely ignores, instead of an atom that cannot be recalled by resonance.
            frequencyStore.save(atom.id(), encoder.encode(text));
            atomStore.save(atom);
            return atom;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public MonadaQuery resonate(String query) {
        return new MonadaQuery(query, encoder, resonanceIndex);
    }
}
