package com.monada.core.execution;

/** How a usage counter value was obtained. UNKNOWN carries no value and is never treated as zero. */
public enum UsageProvenance {
    REPORTED, ESTIMATED, UNKNOWN
}
