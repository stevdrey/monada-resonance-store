package com.monada.encoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class NormalizedText {

    private final String original;
    private final String normalized;
    private final List<String> expansions;

    public NormalizedText(String original, String normalized, List<String> expansions) {
        this.original = Objects.requireNonNull(original, "original");
        this.normalized = Objects.requireNonNull(normalized, "normalized");
        this.expansions = List.copyOf(Objects.requireNonNull(expansions, "expansions"));
        for (String expansion : expansions) {
            Objects.requireNonNull(expansion, "expansion");
            if (expansion.isBlank()) {
                throw new IllegalArgumentException("expansions must not contain blank values");
            }
        }
    }

    public String original() {
        return original;
    }

    public String normalized() {
        return normalized;
    }

    public List<String> expansions() {
        return expansions;
    }

    public String enrichedText() {
        if (expansions.isEmpty()) {
            return normalized;
        }
        return normalized + " " + String.join(" ", expansions);
    }

    public WeightedText toWeightedText(LexicalExpansionOptions options) {
        Objects.requireNonNull(options, "options");
        List<WeightedToken> tokens = new ArrayList<>();
        for (String token : tokenize(normalized)) {
            tokens.add(new WeightedToken(token, options.originalWeight()));
        }
        for (String expansion : expansions) {
            for (String token : tokenize(expansion)) {
                tokens.add(new WeightedToken(token, options.expansionWeight()));
            }
        }
        return new WeightedText(tokens);
    }

    private List<String> tokenize(String text) {
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+");
        List<String> tokens = new ArrayList<>();
        for (String token : raw) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
