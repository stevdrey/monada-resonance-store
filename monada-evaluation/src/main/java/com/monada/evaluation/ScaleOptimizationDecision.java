package com.monada.evaluation;

/**
 * Explicit evidence conclusion signal produced by the scaled linear-scan benchmark.
 *
 * <p>In accordance with the project's measurement-first principle, this signal
 * advises whether a bounded exact top-K optimization experiment is justified by
 * empirical scale and structural bottleneck evidence, without silently altering
 * the production index implementation.
 */
public enum ScaleOptimizationDecision {

    /**
     * Scan overhead remains negligible or within noise compared to encoding across
     * all evaluated scale points. Optimization is not yet justified.
     */
    OPTIMIZATION_NOT_YET_JUSTIFIED,

    /**
     * The configured sweep meets its full-scan and scale-growth criteria across
     * multiple top-K arms. An exact bounded top-K comparison experiment is justified.
     */
    BOUNDED_EXACT_TOP_K_EXPERIMENT_JUSTIFIED,

    /**
     * Evaluated scale points were too small or the results did not provide conclusive
     * separation between encoding and scanning overhead.
     */
    INCONCLUSIVE_NEEDS_LARGER_SCALE
}
