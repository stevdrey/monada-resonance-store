package com.monada.evaluation.execution;

/** What {@code chain*} fields of an {@link ExecutionSummary} cover. */
public enum ChainScope {
    /** Validated acceptance with a derivable cutoff: stages up to the cutoff; later ones are post-acceptance. */
    UP_TO_ACCEPTANCE,
    /** No validated acceptance (or no derivable cutoff): every recorded stage, none is post-acceptance. */
    ALL_RECORDED_NO_ACCEPTANCE
}
