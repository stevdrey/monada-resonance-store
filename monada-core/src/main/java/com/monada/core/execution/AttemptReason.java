package com.monada.core.execution;

/** Why an attempt was started. */
public enum AttemptReason {
    INITIAL, REPAIR, RETRY, REVIEW_FOLLOW_UP
}
