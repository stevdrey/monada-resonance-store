package com.monada.api.execution;

/**
 * Explicit, caller-owned configuration of {@link ExecutionMemory}. History reads are always bounded:
 * {@code defaultPageSize} applies when the caller gives no size, {@code maxPageSize} caps any request.
 */
public record ExecutionMemoryConfig(int defaultPageSize, int maxPageSize) {
    /** Hard upper bound of a history page (contract section 12). */
    public static final int MAX_PAGE_SIZE_LIMIT = 500;
    public static final int DEFAULT_PAGE_SIZE = 100;

    public ExecutionMemoryConfig {
        if (maxPageSize < 1 || maxPageSize > MAX_PAGE_SIZE_LIMIT) {
            throw new IllegalArgumentException("maxPageSize must be between 1 and " + MAX_PAGE_SIZE_LIMIT);
        }
        if (defaultPageSize < 1 || defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException("defaultPageSize must be between 1 and maxPageSize");
        }
    }

    public static ExecutionMemoryConfig defaults() {
        return new ExecutionMemoryConfig(DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE_LIMIT);
    }

    public ExecutionMemoryConfig withDefaultPageSize(int size) {
        return new ExecutionMemoryConfig(size, maxPageSize);
    }

    public ExecutionMemoryConfig withMaxPageSize(int size) {
        return new ExecutionMemoryConfig(defaultPageSize, size);
    }
}
