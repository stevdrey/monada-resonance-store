package com.monada.api.execution;

import com.monada.api.MonadaMemoryOptions;
import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.encoder.NormalizedText;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.storage.AtomStore;
import com.monada.storage.FrequencyStore;
import com.monada.storage.StoredVector;
import com.monada.storage.execution.ProjectionCheckpoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, in-memory copy of one verified projection generation, used by read-only snapshots. The atoms and
 * vectors are read once at open, so a snapshot is truly frozen: later appends, rebuilds, prunes or damage to the
 * generation's files never change its answers, and recall never touches the file system.
 *
 * <p>Ranking reuses the production pieces end to end: the existing {@link LinearScanResonanceIndex} over
 * read-only {@link AtomStore}/{@link FrequencyStore} views, and the query steps of {@code MonadaQuery.execute}
 * with {@link MonadaMemoryOptions#defaults()} (normalize, empty when the enriched text is blank, encode the
 * weighted text, search with threshold 0.0). Feedback-aware wrapping is omitted because verification requires
 * the projection's feedback log to be empty, which makes it a no-op. The recall regression gate and the
 * writable-versus-read-only equality tests guard this equivalence.
 */
final class FrozenProjectionIndex {
    private final LinearScanResonanceIndex index;
    private final MonadaMemoryOptions options = MonadaMemoryOptions.defaults();
    private final SimpleFrequencyEncoder encoder =
            new SimpleFrequencyEncoder(ProjectionCheckpoint.PROJECTION_DIMENSIONS);

    FrozenProjectionIndex(List<KnowledgeAtom> atoms, List<StoredVector> vectors) {
        this.index = new LinearScanResonanceIndex(new FrozenAtoms(List.copyOf(atoms)),
                new FrozenVectors(List.copyOf(vectors)));
    }

    /** Ranked atoms for {@code query}, at most {@code topK}; empty for a query that normalizes to nothing. */
    List<ResonanceResult> search(String query, int topK) {
        NormalizedText normalized = options.textNormalizer().normalize(query);
        if (normalized.enrichedText().isBlank()) {
            return List.of();
        }
        try {
            return index.search(encoder.encode(normalized.toWeightedText(options.expansionOptions())), topK, 0.0);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // cannot happen: the frozen stores do no I/O
        }
    }

    private record FrozenAtoms(List<KnowledgeAtom> atoms) implements AtomStore {
        @Override
        public void save(KnowledgeAtom atom) {
            throw new UnsupportedOperationException("a frozen projection snapshot is read-only");
        }

        @Override
        public Optional<KnowledgeAtom> findById(String id) {
            return atoms.stream().filter(a -> a.id().equals(id)).findFirst();
        }

        @Override
        public List<KnowledgeAtom> findAll() {
            return atoms;
        }
    }

    private static final class FrozenVectors implements FrequencyStore {
        private final List<StoredVector> vectors;
        private final Map<String, FrequencyVector> byAtom = new HashMap<>();

        FrozenVectors(List<StoredVector> vectors) {
            this.vectors = vectors;
            vectors.forEach(v -> byAtom.put(v.atomId(), v.vector()));
        }

        @Override
        public void save(String atomId, FrequencyVector vector) {
            throw new UnsupportedOperationException("a frozen projection snapshot is read-only");
        }

        @Override
        public Optional<FrequencyVector> findByAtomId(String atomId) {
            return Optional.ofNullable(byAtom.get(atomId));
        }

        @Override
        public List<StoredVector> findAll() {
            return vectors;
        }
    }
}
