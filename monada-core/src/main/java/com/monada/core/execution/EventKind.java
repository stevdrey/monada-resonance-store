package com.monada.core.execution;

/** Kind of an immutable ledger event (contract section 4). */
public enum EventKind {
    EXECUTION_STARTED, ATTEMPT_STARTED, STAGE_RECORDED, EVIDENCE_RECORDED, ATTEMPT_FINISHED, OUTCOME_RECORDED, CORRECTION
}
