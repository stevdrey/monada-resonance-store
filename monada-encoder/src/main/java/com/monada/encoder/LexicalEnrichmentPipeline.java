package com.monada.encoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class LexicalEnrichmentPipeline implements QueryNormalizer {

    private final Set<String> stopWords;
    private final Map<String, String> plurals;
    private final Map<String, List<String>> synonyms;

    public LexicalEnrichmentPipeline() {
        this(LexicalResources.loadDefault());
    }

    public LexicalEnrichmentPipeline(String language) {
        this(LexicalResources.load(language));
    }

    private LexicalEnrichmentPipeline(LexicalResources.LexicalConfig config) {
        this(config.stopWords(), config.plurals(), config.synonyms());
    }

    public LexicalEnrichmentPipeline(Set<String> stopWords, Map<String, String> plurals,
                                     Map<String, List<String>> synonyms) {
        this.stopWords = Set.copyOf(Objects.requireNonNull(stopWords, "stopWords"));
        this.plurals = Map.copyOf(Objects.requireNonNull(plurals, "plurals"));
        this.synonyms = copySynonyms(synonyms);
    }

    @Override
    public NormalizedQuery normalize(String query) {
        var normalized = normalizeText(query);
        return new NormalizedQuery(normalized.original(), normalized.normalized(), normalized.expansions());
    }

    /**
     * Returns a deterministic fingerprint of this pipeline's lexical resources
     * (stop words, plurals, and synonyms). The fingerprint is stable across JVM
     * runs so that two pipelines configured with the same resources produce the
     * same value, while any difference in configured resources yields a
     * different value. The class name is prefixed for readability.
     */
    @Override
    public String configurationFingerprint() {
        StringBuilder canonical = new StringBuilder();
        canonical.append("stopWords:");
        for (String word : new TreeSet<>(stopWords)) {
            canonical.append(word).append('\n');
        }
        canonical.append("plurals:");
        for (var entry : new TreeMap<>(plurals).entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        canonical.append("synonyms:");
        for (var entry : new TreeMap<>(synonyms).entrySet()) {
            canonical.append(entry.getKey()).append('=').append(String.join(",", entry.getValue())).append('\n');
        }
        return getClass().getSimpleName() + "#" + sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public NormalizedText normalizeText(String text) {
        Objects.requireNonNull(text, "text");
        var tokens = tokenize(text);
        var kept = new ArrayList<String>();
        for (var token : tokens) {
            var normalized = plurals.getOrDefault(token, token);
            if (!stopWords.contains(normalized)) {
                kept.add(normalized);
            }
        }

        var normalized = String.join(" ", kept);
        var expansions = new LinkedHashSet<String>();
        for (var entry : synonyms.entrySet()) {
            if (matches(normalized, entry.getKey())) {
                expansions.addAll(entry.getValue());
            }
        }
        return new NormalizedText(text, normalized, List.copyOf(expansions));
    }

    private List<String> tokenize(String query) {
        var raw = query.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+");
        var tokens = new ArrayList<String>();
        for (var token : raw) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean matches(String normalized, String phrase) {
        if (normalized.equals(phrase)) {
            return true;
        }
        return (" " + normalized + " ").contains(" " + phrase + " ");
    }

    private static Map<String, List<String>> copySynonyms(Map<String, List<String>> synonyms) {
        Objects.requireNonNull(synonyms, "synonyms");
        var copy = new LinkedHashMap<String, List<String>>();
        for (var entry : synonyms.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), "synonym key");
            var values = List.copyOf(Objects.requireNonNull(entry.getValue(), "synonym values"));
            if (key.isBlank()) {
                throw new IllegalArgumentException("synonym keys must not be blank");
            }
            for (String value : values) {
                Objects.requireNonNull(value, "synonym value");
                if (value.isBlank()) {
                    throw new IllegalArgumentException("synonym values must not be blank");
                }
            }
            copy.put(key, values);
        }
        return Collections.unmodifiableMap(copy);
    }
}
