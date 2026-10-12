package com.monada.evaluation.execution;

/** What a computed amount means. There is deliberately a single basis: costs are never billed amounts. */
public enum CostBasis {
    /** Usage priced with a caller-supplied snapshot as if billed through the API, for every billing mode. */
    HYPOTHETICAL_API_EQUIVALENT
}
