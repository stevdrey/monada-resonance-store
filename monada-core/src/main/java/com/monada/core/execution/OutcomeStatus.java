package com.monada.core.execution;

/** Execution outcome status, distinct from quality evidence and retrieval feedback. */
public enum OutcomeStatus {
    ACCEPTED, REJECTED, FAILED, CANCELLED, PENDING
}
