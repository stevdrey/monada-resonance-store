package com.monada.evaluation;

import java.util.Map;

/**
 * Result of seeding a {@link EvaluationDataset} into a memory instance.
 *
 * <p>Carries both directions of the atom-id/label mapping so that callers
 * never need to invert a potentially lossy map.
 *
 * @param idToLabel     atom id → dataset label (needed to translate ranked results)
 * @param labelToAtomId dataset label → atom id (needed for feedback seeding by expected label)
 */
record DatasetSeeding(Map<String, String> idToLabel, Map<String, String> labelToAtomId) {
}
