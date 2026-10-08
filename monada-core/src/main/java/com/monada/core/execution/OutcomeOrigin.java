package com.monada.core.execution;

/** Whether an outcome was asserted under the execution's evaluation policy or merely imported. */
public enum OutcomeOrigin {
    VALIDATED, IMPORTED_CLAIM
}
