package com.monada.encoder;

import com.monada.core.KnowledgeAtom;
import java.util.ArrayList;

public final class WeightedAtomEncoder {

    private WeightedAtomEncoder() {
    }

    public static WeightedText toWeightedText(
            KnowledgeAtom atom,
            TextNormalizer textNormalizer,
            LexicalExpansionOptions expansionOptions) {
        return toWeightedTextParts(atom, textNormalizer, expansionOptions).toWeightedText();
    }

    /**
     * Reconstructs the exact atom representation while retaining token origin.
     * This helper is side-effect free and exists so diagnostics never need to
     * duplicate the production weighting rules.
     */
    public static WeightedAtomTextParts toWeightedTextParts(
            KnowledgeAtom atom,
            TextNormalizer textNormalizer,
            LexicalExpansionOptions expansionOptions) {
        var originalTokens = new ArrayList<WeightedToken>();
        var expansionTokens = new ArrayList<WeightedToken>();
        var aliasTokens = new ArrayList<WeightedToken>();
        var aliasExpansionTokens = new ArrayList<WeightedToken>();

        // Normalize core content and assign original/expansion weights
        var normalizedContent = textNormalizer.normalize(atom.content());
        var contentParts = normalizedContent.toWeightedTextParts(expansionOptions);
        originalTokens.addAll(contentParts.originalTokens());
        expansionTokens.addAll(contentParts.expansionTokens());

        // Normalize each alias as secondary support text
        if (!atom.aliases().isEmpty()) {
            var aliasOptions = new LexicalExpansionOptions(
                    expansionOptions.expansionWeight(),
                    Math.min(expansionOptions.expansionWeight(), expansionOptions.expansionWeight() * expansionOptions.expansionWeight())
            );
            for (String alias : atom.aliases()) {
                var normalizedAlias = textNormalizer.normalize(alias);
                var aliasParts = normalizedAlias.toWeightedTextParts(aliasOptions);
                aliasTokens.addAll(aliasParts.originalTokens());
                aliasExpansionTokens.addAll(aliasParts.expansionTokens());
            }
        }

        return new WeightedAtomTextParts(
                originalTokens,
                expansionTokens,
                aliasTokens,
                aliasExpansionTokens);
    }
}
