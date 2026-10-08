package com.monada.core.execution;

/**
 * Size limits of execution memory v1 (see {@code docs/specs/execution-memory-contract-v1.md}, section 3).
 *
 * <p>The domain records enforce every limit that can be checked without an encoding.
 * {@link #MAX_RECORD_BYTES} depends on the ledger payload codec and is enforced by the storage layer.
 */
public final class ExecutionLimits {
    public static final int MAX_IDENTIFIER_CODE_POINTS = 128;
    public static final int MAX_SUMMARY_CODE_POINTS = 4096;
    public static final int MAX_OPAQUE_CODE_POINTS = 512;
    public static final int MAX_ARTIFACT_REFS_PER_EVENT = 64;
    public static final int MAX_OBSERVATIONS_PER_EVENT = 64;
    public static final int MAX_USAGE_COUNTERS_PER_EVENT = 64;
    public static final int MAX_RECORD_BYTES = 65_536;

    private ExecutionLimits() {
    }
}
