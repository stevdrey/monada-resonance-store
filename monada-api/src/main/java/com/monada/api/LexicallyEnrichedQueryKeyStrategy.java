package com.monada.api;

import com.monada.encoder.TextNormalizer;

import java.util.Objects;
import java.util.TreeSet;

/**
 * Feedback key strategy based on a deterministic set of normalized and expanded terms.
 */
public final class LexicallyEnrichedQueryKeyStrategy implements FeedbackQueryKeyStrategy {

    private final TextNormalizer textNormalizer;

    public LexicallyEnrichedQueryKeyStrategy(TextNormalizer textNormalizer) {
        this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer");
    }

    @Override
    public String keyFor(String query) {
        Objects.requireNonNull(query, "query");
        var normalized = textNormalizer.normalize(query);
        var expansionTerms = terms(String.join(" ", normalized.expansions()));
        if (!expansionTerms.isEmpty()) {
            return "lexical-expansion:" + expansionTerms.last();
        }

        var enriched = normalized.enrichedText();
        if (enriched.isBlank()) {
            return query;
        }

        var terms = terms(enriched);
        if (terms.isEmpty()) {
            return query;
        }
        return "lexical:" + String.join(" ", terms);
    }

    private TreeSet<String> terms(String text) {
        var terms = new TreeSet<String>();
        for (String token : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{Alnum}]+")) {
            if (!token.isBlank()) {
                terms.add(token);
            }
        }
        return terms;
    }
}
