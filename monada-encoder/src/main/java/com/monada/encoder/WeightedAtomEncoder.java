package com.monada.encoder;

import com.monada.core.KnowledgeAtom;
import java.util.ArrayList;
import java.util.List;

public final class WeightedAtomEncoder {

    private WeightedAtomEncoder() {
    }

    public static WeightedText toWeightedText(
            KnowledgeAtom atom,
            TextNormalizer textNormalizer,
            LexicalExpansionOptions expansionOptions) {
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
}
