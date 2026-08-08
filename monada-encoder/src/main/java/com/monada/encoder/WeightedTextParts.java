package com.monada.encoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable partition of a normalized text representation.
 *
 * <p>The two lists preserve the exact weighted tokens consumed by
 * {@link SimpleFrequencyEncoder}: normalized original terms first, followed by
 * lexical expansion terms. The partition is diagnostic-only; flattening it is
 * equivalent to {@link NormalizedText#toWeightedText(LexicalExpansionOptions)}.
 */
public record WeightedTextParts(
        List<WeightedToken> originalTokens,
        List<WeightedToken> expansionTokens
) {
    public WeightedTextParts {
        originalTokens = List.copyOf(Objects.requireNonNull(originalTokens, "originalTokens"));
        expansionTokens = List.copyOf(Objects.requireNonNull(expansionTokens, "expansionTokens"));
    }

    public WeightedText toWeightedText() {
        var tokens = new ArrayList<WeightedToken>(originalTokens.size() + expansionTokens.size());
        tokens.addAll(originalTokens);
        tokens.addAll(expansionTokens);
        return new WeightedText(tokens);
    }
}
