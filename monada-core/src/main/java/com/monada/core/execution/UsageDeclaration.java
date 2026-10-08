package com.monada.core.execution;

/** Whether a stage must carry measured usage or explicitly declares that it consumed nothing billable. */
public enum UsageDeclaration {
    MEASURED, NO_BILLABLE_USAGE
}
