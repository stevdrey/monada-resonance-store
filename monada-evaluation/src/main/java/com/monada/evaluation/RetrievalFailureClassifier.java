package com.monada.evaluation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies why a retrieval query did not produce a perfect result.
 *
 * <p>Heuristics applied in priority order:
 * <ol>
 *   <li>If Hit@5 == 0 and the query shares no normalised tokens with the
 *       expected label identifiers → {@link RetrievalFailureType#POSSIBLE_DATASET_ALIAS_GAP}.</li>
 *   <li>If Hit@5 == 0 → {@link RetrievalFailureType#MISSING_EXPECTED_ATOM}.</li>
 *   <li>If there are multiple expected labels and Recall@5 &lt; 1.0 →
 *       {@link RetrievalFailureType#MULTI_RELEVANT_RECALL_GAP}.</li>
 *   <li>If the first expected atom is at rank 2 (index 1) →
 *       {@link RetrievalFailureType#CONFUSABLE_ATOM_RANKED_HIGHER}.</li>
 *   <li>If the first expected atom is at rank ≥ 3 →
 *       {@link RetrievalFailureType#EXPECTED_ATOM_PRESENT_BUT_LOW_RANK}.</li>
 *   <li>Otherwise → {@link RetrievalFailureType#POSSIBLE_ENCODER_LIMITATION}.</li>
 * </ol>
 *
 * <p>Returns {@link Optional#empty()} when the query result is perfect
 * (all expected atoms in top-K at rank 1 with Hit@1 == 1.0 and no recall gap).
 */
public final class RetrievalFailureClassifier {

    private RetrievalFailureClassifier() {
    }

    /**
     * Classifies the failure mode for {@code evaluation}, or returns
     * {@link Optional#empty()} if the result is perfect.
     *
     * <p>A result is considered perfect when {@code Hit@1 == 1.0} and
     * all expected labels are recalled at the evaluated K.
     */
    public static Optional<RetrievalFailureType> classify(QueryEvaluation evaluation) {
        var expected = evaluation.expectedLabels();
        var returned = evaluation.returnedLabels();
        var hitByK = evaluation.hitByK();

        // Perfect: Hit@1 is 1.0 and no multi-relevant recall gap.
        double hit1 = hitByK.getOrDefault(1, 0.0);
        double hit5 = hitByK.getOrDefault(5, hitByK.getOrDefault(3, 0.0));
        double recall5 = evaluation.recallByK().getOrDefault(5, evaluation.recallByK().getOrDefault(3, 0.0));

        boolean perfectHit = hit1 >= 1.0 - 1e-12;
        boolean perfectRecall = expected.size() <= 1 || recall5 >= 1.0 - 1e-12;
        if (perfectHit && perfectRecall) {
            return Optional.empty();
        }

        // No expected atom in top-K at all.
        if (hit5 < 1e-12) {
            // Heuristic: if the query tokens don't overlap with any expected label token,
            // it is likely a dataset alias gap rather than an encoder limitation.
            if (isPossibleAliasGap(evaluation.queryText(), expected)) {
                return Optional.of(RetrievalFailureType.POSSIBLE_DATASET_ALIAS_GAP);
            }
            return Optional.of(RetrievalFailureType.MISSING_EXPECTED_ATOM);
        }

        // Multi-relevant gap: some expected atoms are missing from top-K.
        if (expected.size() > 1 && recall5 < 1.0 - 1e-12) {
            return Optional.of(RetrievalFailureType.MULTI_RELEVANT_RECALL_GAP);
        }

        // At least one expected atom is in top-K; classify by rank.
        int firstExpectedRank = firstExpectedRank(expected, returned);
        if (firstExpectedRank == 1) {
            // Present at rank 2 (0-based index 1): something else ranked higher.
            return Optional.of(RetrievalFailureType.CONFUSABLE_ATOM_RANKED_HIGHER);
        }
        if (firstExpectedRank >= 2) {
            return Optional.of(RetrievalFailureType.EXPECTED_ATOM_PRESENT_BUT_LOW_RANK);
        }

        return Optional.of(RetrievalFailureType.POSSIBLE_ENCODER_LIMITATION);
    }

    /**
     * Returns the 0-based index of the first expected label in {@code returned},
     * or {@link Integer#MAX_VALUE} if none is present.
     */
    private static int firstExpectedRank(Set<String> expected, List<String> returned) {
        for (int i = 0; i < returned.size(); i++) {
            if (expected.contains(returned.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Heuristic alias-gap check: returns {@code true} when no token from the
     * lowercased query appears in any of the expected label identifiers.
     *
     * <p>This is intentionally conservative — it only fires when there is zero
     * token overlap, making a synonym or alias addition the most likely fix.
     */
    private static boolean isPossibleAliasGap(String queryText, Set<String> expectedLabels) {
        var queryTokens = Set.of(queryText.toLowerCase().split("[^a-z0-9]+"));
        for (String label : expectedLabels) {
            // Labels use underscore-separated identifiers (e.g. ka_cosine_similarity).
            var labelTokens = label.toLowerCase().split("[^a-z0-9]+");
            for (String labelToken : labelTokens) {
                if (!labelToken.isBlank() && queryTokens.contains(labelToken)) {
                    return false;
                }
            }
        }
        return true;
    }
}
