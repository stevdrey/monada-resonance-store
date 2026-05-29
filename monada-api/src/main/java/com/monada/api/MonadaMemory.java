package com.monada.api;

import com.monada.core.KnowledgeAtom;
import com.monada.encoder.FrequencyEncoder;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.TextNormalizer;
import com.monada.encoder.WeightedText;
import com.monada.encoder.WeightedToken;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.index.ResonanceIndex;
import com.monada.storage.AtomStore;
import com.monada.storage.EncodingProfile;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class MonadaMemory {

    private static final String MANIFEST_VERSION = "0.3";
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
    private final LexicalExpansionOptions expansionOptions;

    private MonadaMemory(FrequencyEncoder encoder, TextNormalizer textNormalizer, AtomStore atomStore, FrequencyStore frequencyStore,
                         ResonanceIndex resonanceIndex, FeedbackStore feedbackStore, KnownAtomIdCache knownAtomIds,
                         boolean feedbackAwareRanking, LexicalExpansionOptions expansionOptions) {
        this.encoder = encoder;
        this.textNormalizer = textNormalizer;
        this.atomStore = atomStore;
        this.frequencyStore = frequencyStore;
        this.resonanceIndex = resonanceIndex;
        this.feedbackStore = feedbackStore;
        this.knownAtomIds = knownAtomIds;
        this.feedbackAwareRanking = feedbackAwareRanking;
        this.expansionOptions = Objects.requireNonNull(expansionOptions, "expansionOptions");
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

    public static EncodingProfile getExpectedProfile(int dimensions, MonadaMemoryOptions options) {
        double origW = options.expansionOptions().originalWeight();
        double expW = options.expansionOptions().expansionWeight();
        double aliasOrigW = expW;
        double aliasExpW = Math.min(expW, expW * expW);
        
        return new EncodingProfile(
            "SimpleFrequencyEncoder",
            "1.0",
            dimensions,
            options.textNormalizer().getClass().getSimpleName(),
            options.textNormalizer().configurationFingerprint(),
            "WeightedTokens",
            origW,
            expW,
            aliasOrigW,
            aliasExpW
        );
    }

    public static MonadaMemory open(Path path, MonadaMemoryOptions options) {
        Objects.requireNonNull(options, "options");
        try {
            ManifestStore manifestStore = new FileManifestStore(path);
            Optional<Manifest> existing = manifestStore.load();
            Manifest manifest;
            LexicalExpansionOptions actualExpansionOptions = options.expansionOptions();
            if (existing.isPresent()) {
                manifest = existing.get();
                if (Objects.equals(manifest.version(), "0.3")) {
                    EncodingProfile storedProfile = manifest.encodingProfile();
                    if (storedProfile == null) {
                        throw new IOException("Manifest version is 0.3 but encodingProfile is missing");
                    }
                    EncodingProfile expectedProfile = getExpectedProfile(manifest.dimensions(), options);
                    if (!Objects.equals(storedProfile, expectedProfile)) {
                        throw new IllegalArgumentException(
                                "Requested encoding profile does not match the persisted encoding profile in the store.\n" +
                                "Stored: " + storedProfile + "\n" +
                                "Expected: " + expectedProfile + "\n" +
                                "Please rebuild vectors using VectorRebuilder to match the new profile.");
                    }
                } else if (Objects.equals(manifest.version(), "0.2")) {
                    if (!options.textNormalizer().getClass().getSimpleName().equals("LexicalEnrichmentPipeline")) {
                        throw new IllegalArgumentException(
                                "Requested normalizer " + options.textNormalizer().getClass().getSimpleName() +
                                " is incompatible with legacy 0.2 store (expected LexicalEnrichmentPipeline).\n" +
                                "Please rebuild vectors using VectorRebuilder.");
                    }
                    // Legacy 0.2 stores do not persist their lexical configuration, so we cannot
                    // prove that custom requested resources match the existing vectors. Only the
                    // default resources are assumed compatible; reject anything else.
                    String defaultFingerprint = new LexicalEnrichmentPipeline().configurationFingerprint();
                    if (!options.textNormalizer().configurationFingerprint().equals(defaultFingerprint)) {
                        throw new IllegalArgumentException(
                                "Requested normalizer has custom lexical resources which cannot be proven compatible with legacy 0.2 store (resources are not persisted).\n" +
                                "Please rebuild vectors using VectorRebuilder.");
                    }
                    // Legacy 0.2 stores do not persist their expansion weights, so we cannot
                    // prove that custom requested weights match the existing vectors. Only the
                    // default weights are assumed compatible; reject anything else.
                    if (!options.expansionOptions().equals(LexicalExpansionOptions.DEFAULT)) {
                        throw new IllegalArgumentException(
                                "Requested expansion options " + options.expansionOptions() +
                                " cannot be proven compatible with legacy 0.2 store (weights are not persisted).\n" +
                                "Please rebuild vectors using VectorRebuilder.");
                    }
                    actualExpansionOptions = LexicalExpansionOptions.DEFAULT;
                } else if (Objects.equals(manifest.version(), "0.1")) {
                    if (!options.textNormalizer().getClass().getSimpleName().equals("LexicalEnrichmentPipeline")) {
                        throw new IllegalArgumentException(
                                "Requested normalizer " + options.textNormalizer().getClass().getSimpleName() +
                                " is incompatible with legacy 0.1 store (expected LexicalEnrichmentPipeline).\n" +
                                "Please rebuild vectors using VectorRebuilder.");
                    }
                    // Legacy 0.1 stores do not persist their lexical configuration, so we cannot
                    // prove that custom requested resources match the existing vectors. Only the
                    // default resources are assumed compatible; reject anything else.
                    String defaultFingerprint = new LexicalEnrichmentPipeline().configurationFingerprint();
                    if (!options.textNormalizer().configurationFingerprint().equals(defaultFingerprint)) {
                        throw new IllegalArgumentException(
                                "Requested normalizer has custom lexical resources which cannot be proven compatible with legacy 0.1 store (resources are not persisted).\n" +
                                "Please rebuild vectors using VectorRebuilder.");
                    }
                    actualExpansionOptions = new LexicalExpansionOptions(1.0, 1.0);
                } else {
                    throw new IOException(
                            "Unsupported manifest version '" + manifest.version()
                                    + "'; expected '0.3', '0.2', or '0.1'");
                }
            } else {
                EncodingProfile profile = getExpectedProfile(DEFAULT_DIMENSIONS, options);
                manifest = new Manifest(
                        "0.3", DEFAULT_DIMENSIONS, DEFAULT_VECTOR_SEGMENT, DEFAULT_ATOM_SEGMENT,
                        FeedbackStore.DEFAULT_SEGMENT, profile);
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
                    options.feedbackAwareRanking(), actualExpansionOptions);
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
                frequencyStore.save(merged.id(), encoder.encode(getWeightedTextForAtom(merged)));
                atomStore.save(merged);
                knownAtomIds.remember(merged.id());
                return merged;
            }
            // Persist vector first: a partial failure leaves an orphan vector that search
            // safely ignores, instead of an atom that cannot be recalled by resonance.
            frequencyStore.save(atom.id(), encoder.encode(getWeightedTextForAtom(atom)));
            atomStore.save(atom);
            knownAtomIds.remember(atom.id());
            return atom;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private WeightedText getWeightedTextForAtom(KnowledgeAtom atom) {
        List<WeightedToken> tokens = new ArrayList<>();
        
        // Normalize core content and assign original/expansion weights
        var normalizedContent = textNormalizer.normalize(atom.content());
        tokens.addAll(normalizedContent.toWeightedText(expansionOptions).tokens());
        
        // Normalize each alias as secondary support text
        if (!atom.aliases().isEmpty()) {
            var aliasOptions = new LexicalExpansionOptions(
                expansionOptions.expansionWeight(),
                Math.min(expansionOptions.expansionWeight(), expansionOptions.expansionWeight() * expansionOptions.expansionWeight())
            );
            for (String alias : atom.aliases()) {
                var normalizedAlias = textNormalizer.normalize(alias);
                tokens.addAll(normalizedAlias.toWeightedText(aliasOptions).tokens());
            }
        }
        
        return new WeightedText(tokens);
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
        return new MonadaQuery(query, encoder, textNormalizer, resonanceIndex, feedbackStore, feedbackAwareRanking, expansionOptions);
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
