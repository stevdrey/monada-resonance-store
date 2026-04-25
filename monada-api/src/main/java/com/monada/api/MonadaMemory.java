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
import com.monada.storage.ManifestStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public class MonadaMemory {

    private static final int DEFAULT_DIMENSIONS = 128;

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
            manifestStore.initialize();
            AtomStore atomStore = new FileAtomStore(path);
            FrequencyStore frequencyStore = new FileFrequencyStore(path);
            FrequencyEncoder encoder = new SimpleFrequencyEncoder(DEFAULT_DIMENSIONS);
            ResonanceIndex resonanceIndex = new LinearScanResonanceIndex(atomStore, frequencyStore);
            return new MonadaMemory(encoder, atomStore, frequencyStore, resonanceIndex);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public KnowledgeAtom remember(String text) {
        try {
            KnowledgeAtom atom = KnowledgeAtom.text(text);
            atomStore.save(atom);
            frequencyStore.save(atom.id(), encoder.encode(text));
            return atom;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public MonadaQuery resonate(String query) {
        return new MonadaQuery(query, encoder, resonanceIndex);
    }
}
