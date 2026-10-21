package com.monada.evaluation.book;

import java.util.List;
import java.util.Set;

/**
 * Why one judged book query did not get its evidence at rank 1, classified from the ranked hits and the frozen
 * judgments only (Issue #167, evaluation only). Classes are mutually exclusive; {@link #classify} applies them in
 * declaration order, so the most severe failure wins. Nothing here interprets chunk text.
 */
public enum BookRecallFailure {
    /** Answerable: no judged evidence chunk anywhere in the retrieved top-K. */
    NO_RELEVANT_RESULT,
    /** Answerable: a chunk judged as a confounder (grade 0) ranks above the first evidence chunk. */
    DISTRACTOR,
    /**
     * Answerable: an unjudged chunk directly before or after an evidence chunk (ordinal ±1) ranks above the first
     * evidence chunk, so the passage may straddle a chunk boundary.
     */
    CHUNK_BOUNDARY_GAP,
    /** Answerable: evidence is retrieved, but below rank 1, behind unjudged non-adjacent chunks. */
    WRONG_RANK,
    /** Answerable: rank 1 is evidence, but some judged evidence chunk is missing from the top-K. */
    MULTI_EVIDENCE_GAP,
    /** Answerable: rank 1 is evidence and every evidence chunk is in the top-K. */
    NONE,
    /** NO_ANSWER: the query returned at least one chunk at the evaluated threshold. */
    FALSE_POSITIVE,
    /** NO_ANSWER: the query returned nothing at the evaluated threshold. */
    ABSTAINED;

    /** One retrieved chunk as the classifier sees it: its relevance key and every chunk ordinal it stands for. */
    record Ranked(String key, List<Integer> ordinals) {
        Ranked {
            ordinals = List.copyOf(ordinals);
        }
    }

    /**
     * Classifies one query.
     *
     * @param answerable       false for NO_ANSWER queries
     * @param ranked           the retrieved top-K, best first
     * @param evidence         relevance keys of judged evidence (grade 1 or 2)
     * @param confounders      relevance keys of judged confounders (grade 0)
     * @param evidenceOrdinals chunk ordinals of every judged evidence chunk
     */
    static BookRecallFailure classify(boolean answerable, List<Ranked> ranked, Set<String> evidence,
                                      Set<String> confounders, Set<Integer> evidenceOrdinals) {
        if (!answerable) {
            return ranked.isEmpty() ? ABSTAINED : FALSE_POSITIVE;
        }
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("an answerable query needs judged evidence");
        }
        int first = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (evidence.contains(ranked.get(i).key())) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return NO_RELEVANT_RESULT;
        }
        List<Ranked> above = ranked.subList(0, first);
        if (above.stream().anyMatch(r -> confounders.contains(r.key()))) {
            return DISTRACTOR;
        }
        if (above.stream().anyMatch(r -> r.ordinals().stream()
                .anyMatch(o -> evidenceOrdinals.contains(o - 1) || evidenceOrdinals.contains(o + 1)))) {
            return CHUNK_BOUNDARY_GAP;
        }
        if (first > 0) {
            return WRONG_RANK;
        }
        boolean allRetrieved = ranked.stream().map(Ranked::key).filter(evidence::contains).distinct().count()
                == evidence.size();
        return allRetrieved ? NONE : MULTI_EVIDENCE_GAP;
    }
}
