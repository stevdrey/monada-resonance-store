package com.monada.core.execution;

/** Kind of a usage counter. */
public enum UsageKind {
    INPUT_TOKENS, CACHED_INPUT_TOKENS, OUTPUT_TOKENS, REASONING_TOKENS, REQUESTS, TOOL_CALLS
}
