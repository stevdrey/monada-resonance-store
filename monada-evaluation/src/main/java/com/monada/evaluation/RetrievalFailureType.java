package com.monada.evaluation;

/**
 * Classification of why a retrieval query failed to produce a perfect result.
 *
 * <p>Not every failure should be solved the same way:
 * <ul>
 *   <li>{@link #MISSING_EXPECTED_ATOM} — the expected atom is not present in
 *       the top-K at all; may require more data, better aliases, or a stronger
 *       encoder.</li>
 *   <li>{@link #EXPECTED_ATOM_PRESENT_BUT_LOW_RANK} — the atom is present in
 *       top-K but at a low rank (≥ 3); ranking or scoring needs improvement.</li>
 *   <li>{@link #CONFUSABLE_ATOM_RANKED_HIGHER} — a different (non-expected) atom
 *       ranked above the expected one; the encoder conflates two concepts.</li>
 *   <li>{@link #MULTI_RELEVANT_RECALL_GAP} — the query has multiple expected
 *       labels but not all are recalled in the top-K.</li>
 *   <li>{@link #POSSIBLE_DATASET_ALIAS_GAP} — the query probably needs an alias
 *       or synonym entry rather than a better encoder.</li>
 *   <li>{@link #POSSIBLE_ENCODER_LIMITATION} — the root cause appears to be a
 *       fundamental semantic limitation of the current frequency-based encoder.</li>
 * </ul>
 */
public enum RetrievalFailureType {
    MISSING_EXPECTED_ATOM,
    EXPECTED_ATOM_PRESENT_BUT_LOW_RANK,
    CONFUSABLE_ATOM_RANKED_HIGHER,
    MULTI_RELEVANT_RECALL_GAP,
    POSSIBLE_DATASET_ALIAS_GAP,
    POSSIBLE_ENCODER_LIMITATION
}
