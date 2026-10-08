package com.monada.core.execution;

/** Terminal state of an attempt. An attempt without a finish event is in progress, not an error. */
public enum AttemptResult {
    COMPLETED, FAILED, CANCELLED
}
