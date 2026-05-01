package com.monada.core;

/**
 * A single resonance search result pairing an atom with its relevance score.
 *
 * <p><b>Note on score range:</b> the base cosine-similarity score is in
 * {@code [-1, 1]}, but when feedback adjustments are applied by
 * {@code FeedbackAwareResonanceIndex} the effective score can exceed this
 * range (e.g. {@code base + delta > 1.0}). Consumers should not assume
 * the score is bounded to {@code [0, 1]}.
 */
public record ResonanceResult(
        KnowledgeAtom atom,
        double score
) {}