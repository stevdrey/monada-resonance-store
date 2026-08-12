package com.monada.evaluation;

/** Safety classification for one normalized query-key stress pair. */
public enum NormalizedQueryKeyStressClassification {
    SAFE_EQUIVALENT_SHARING,
    SAFE_ISOLATION,
    COLLISION_WITHOUT_MOVEMENT,
    CONTAMINATION
}
