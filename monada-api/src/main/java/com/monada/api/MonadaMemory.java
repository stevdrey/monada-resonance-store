package com.monada.api;

import com.monada.core.KnowledgeAtom;
import com.monada.encoder.FrequencyEncoder;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.TextNormalizer;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.index.ResonanceIndex;
import com.monada.storage.AtomStore;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.FileManifestStore;
import com.monada.storage.FrequencyStore;
import com.monada.storage.Manifest;
import com.monada.storage.ManifestStore;
import com.monada.storage.feedback.FeedbackEvent;
import com.monada.storage.feedback.FeedbackSignal;
import com.monada.storage.feedback.FeedbackStore;
import com.monada.storage.feedback.FileFeedbackStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class MonadaMemory {

    private static final String MANIFEST_VERSION = "0.1";
    private static final int DEFAULT_DIMENSIONS = 128;
    private static final String DEFAULT_VECTOR_SEGMENT = "vectors/segment-000001.f32";
    private static final String DEFAULT_ATOM_SEGMENT = "atoms/segment-000001.log";
    private static final double DEFAULT_POSITIVE_DELTA = 0.05;
    private static final double DEFAULT_NEGATIVE_DELTA = -0.05;
    private static final int DEFAULT_KNOWN_ATOM_ID_CACHE_SIZE = 1024;

    private final FrequencyEncoder encoder;
    private final TextNormalizer textNormalizer;
    private final AtomStore atomStore;
    private final FrequencyStore frequencyStore;
    private final ResonanceIndex resonanceIndex;
    private final FeedbackStore feedbackStore;
    private final KnownAtomIdCache knownAtomIds;
    private final boolean feedbackAwareRanking;

    private MonadaMemory(FrequencyEncoder encoder, TextNormalizer textNormalizer, AtomStore atomStore, FrequencyStore frequencyStore,
                         ResonanceIndex resonanceIndex, FeedbackStore feedbackStore, KnownAtomIdCache knownAtomIds,
                         boolean feedbackAwareRanking) {
        this.encoder = encoder;
        this.textNormalizer = textNormalizer;
        this.atomStore = atomStore;
        this.frequencyStore = frequencyStore;
        this.resonanceIndex = resonanceIndex;
        this.feedbackStore = feedbackStore;
        this.knownAtomIds = knownAtomIds;
        this.feedbackAwareRanking = feedbackAwareRanking;
    }

    public static MonadaMemory open(String path) {
        return open(Path.of(path));
    }

    public static MonadaMemory open(Path path) {
        return open(path, MonadaMemoryOptions.defaults());
    }

    public static MonadaMemory open(String path, MonadaMemoryOptions options) {
        return open(Path.of(path), options);
    }

    public static MonadaMemory open(Path path, MonadaMemoryOptions options) {
        Objects.requireNonNull(options, "options");
        try {
            ManifestStore manifestStore = new FileManifestStore(path);
            Optional<Manifest> existing = manifestStore.load();
            Manifest manifest;
            if (existing.isPresent()) {
                manifest = existing.get();
                if (!Objects.equals(manifest.version(), MANIFEST_VERSION)) {
                    throw new IOException(
                            "Unsupported manifest version '" + manifest.version()
                                    + "'; expected '" + MANIFEST_VERSION + "'");
                }
            } else {
                manifest = new Manifest(
                        MANIFEST_VERSION, DEFAULT_DIMENSIONS, DEFAULT_VECTOR_SEGMENT, DEFAULT_ATOM_SEGMENT,
                        FeedbackStore.DEFAULT_SEGMENT);
                manifestStore.save(manifest);
            }

            AtomStore atomStore = new FileAtomStore(path, manifest.atomSegment());
            FrequencyStore frequencyStore = new FileFrequencyStore(
                    path, manifest.vectorSegment(), manifest.dimensions());
            FeedbackStore feedbackStore = new FileFeedbackStore(path, manifest.feedbackSegment());
            FrequencyEncoder encoder = new SimpleFrequencyEncoder(manifest.dimensions());
            ResonanceIndex resonanceIndex = new LinearScanResonanceIndex(atomStore, frequencyStore);
            return new MonadaMemory(encoder, options.textNormalizer(), atomStore, frequencyStore, resonanceIndex,
                    feedbackStore, new KnownAtomIdCache(DEFAULT_KNOWN_ATOM_ID_CACHE_SIZE),
                    options.feedbackAwareRanking());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public KnowledgeAtom remember(String text) {
        return remember(text, List.of());
    }

    public KnowledgeAtom remember(String text, List<String> aliases) {
        try {
            var atom = KnowledgeAtom.text(text, aliases);
            var existing = atomStore.findById(atom.id());
            if (existing.isPresent()) {
                var merged = mergeAliases(existing.get(), aliases);
                if (merged == existing.get()) {
                    // No new aliases; idempotent return.
                    knownAtomIds.remember(merged.id());
                    return merged;
                }
                // New aliases were added: re-encode and append updated entries.
                var searchableText = textNormalizer.normalize(merged.searchableContent()).enrichedText();
                frequencyStore.save(merged.id(), encoder.encode(searchableText));
                atomStore.save(merged);
                knownAtomIds.remember(merged.id());
                return merged;
            }
            // Persist vector first: a partial failure leaves an orphan vector that search
            // safely ignores, instead of an atom that cannot be recalled by resonance.
            var searchableText = textNormalizer.normalize(atom.searchableContent()).enrichedText();
            frequencyStore.save(atom.id(), encoder.encode(searchableText));
            atomStore.save(atom);
            knownAtomIds.remember(atom.id());
            return atom;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns {@code existing} unchanged if {@code newAliases} adds nothing new.
     * Otherwise returns a new atom with the union of existing and new aliases
     * (existing order first, then new aliases in caller order, deduped).
     */
    private static KnowledgeAtom mergeAliases(KnowledgeAtom existing, List<String> newAliases) {
        if (newAliases.isEmpty()) {
            return existing;
        }
        var merged = new LinkedHashSet<>(existing.aliases());
        merged.addAll(newAliases);
        var mergedList = List.copyOf(merged);
        if (mergedList.equals(existing.aliases())) {
            return existing;
        }
        return new KnowledgeAtom(
                existing.id(),
                existing.type(),
                existing.content(),
                mergedList,
                existing.metadata(),
                existing.weight(),
                existing.createdAt()
        );
    }

    public MonadaQuery resonate(String query) {
        return new MonadaQuery(query, encoder, textNormalizer, resonanceIndex, feedbackStore, feedbackAwareRanking);
    }

    /**
     * Record a feedback event for {@code atomId} under {@code query} using the
     * default delta for the given signal.
     */
    public void feedback(String query, String atomId, FeedbackSignal signal) {
        Objects.requireNonNull(signal, "signal");
        double delta = switch (signal) {
            case POSITIVE -> DEFAULT_POSITIVE_DELTA;
            case NEGATIVE -> DEFAULT_NEGATIVE_DELTA;
        };
        feedback(query, atomId, signal, delta);
    }

    /**
     * Record a feedback event with an explicit delta. The sign of {@code delta}
     * must match {@code signal}: positive for {@code POSITIVE}, negative for
     * {@code NEGATIVE}.
     */
    public void feedback(String query, String atomId, FeedbackSignal signal, double delta) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(atomId, "atomId");
        Objects.requireNonNull(signal, "signal");
        try {
            if (!knownAtomIds.contains(atomId) && atomStore.findById(atomId).isEmpty()) {
                throw new IllegalArgumentException("Unknown atomId: " + atomId);
            }
            knownAtomIds.remember(atomId);
            feedbackStore.append(new FeedbackEvent(query, atomId, signal, delta, Instant.now()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
