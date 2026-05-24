package com.monada.encoder;

public record LexicalExpansionOptions(
        double originalWeight,
        double expansionWeight
) {
    public static final LexicalExpansionOptions DEFAULT = new LexicalExpansionOptions(1.0, 0.25);

    public LexicalExpansionOptions {
        if (originalWeight <= 0.0) {
            throw new IllegalArgumentException("originalWeight must be positive");
        }
        if (expansionWeight <= 0.0) {
            throw new IllegalArgumentException("expansionWeight must be positive");
        }
    }
}
