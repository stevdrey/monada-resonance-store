package com.monada.encoder;

public record LexicalExpansionOptions(
        double originalWeight,
        double expansionWeight
) {
    public static final LexicalExpansionOptions DEFAULT = new LexicalExpansionOptions(1.0, 0.25);

    public LexicalExpansionOptions {
        if (!Double.isFinite(originalWeight) || originalWeight <= 0.0) {
            throw new IllegalArgumentException("originalWeight must be finite and positive");
        }
        if (!Double.isFinite(expansionWeight) || expansionWeight <= 0.0) {
            throw new IllegalArgumentException("expansionWeight must be finite and positive");
        }
        if (expansionWeight > originalWeight) {
            throw new IllegalArgumentException("expansionWeight must be less than or equal to originalWeight");
        }
    }
}
