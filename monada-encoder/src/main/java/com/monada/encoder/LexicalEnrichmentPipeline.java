package com.monada.encoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        Objects.requireNonNull(query, "query");
        var tokens = tokenize(query);
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
        return new NormalizedQuery(query, normalized, List.copyOf(expansions));
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
            for (var value : values) {
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
