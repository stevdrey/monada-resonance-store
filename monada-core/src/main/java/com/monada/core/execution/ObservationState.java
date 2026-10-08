package com.monada.core.execution;

/** State of a quality observation. UNKNOWN and NOT_APPLICABLE are never equivalent to PASS. */
public enum ObservationState {
    PASS, FAIL, UNKNOWN, NOT_APPLICABLE
}
