package com.monada.encoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable partition of the weighted representation used for a text atom.
 *
 * <p>Alias terms and alias expansions retain their distinct effective weights,
 * allowing evaluation diagnostics to explain the representation without
 * changing or reimplementing atom encoding.
 */
public record WeightedAtomTextParts(
        List<WeightedToken> originalTokens,
        List<WeightedToken> expansionTokens,
        List<WeightedToken> aliasTokens,
        List<WeightedToken> aliasExpansionTokens
) {
    public WeightedAtomTextParts {
        originalTokens = copy(originalTokens, "originalTokens");
        expansionTokens = copy(expansionTokens, "expansionTokens");
        aliasTokens = copy(aliasTokens, "aliasTokens");
        aliasExpansionTokens = copy(aliasExpansionTokens, "aliasExpansionTokens");
    }

    public WeightedText toWeightedText() {
        var tokens = new ArrayList<WeightedToken>(
                originalTokens.size() + expansionTokens.size()
                        + aliasTokens.size() + aliasExpansionTokens.size());
        tokens.addAll(originalTokens);
        tokens.addAll(expansionTokens);
        tokens.addAll(aliasTokens);
        tokens.addAll(aliasExpansionTokens);
        return new WeightedText(tokens);
    }

    private List<WeightedToken> copy(List<WeightedToken> tokens, String name) {
        return List.copyOf(Objects.requireNonNull(tokens, name));
    }
}
