package com.monada.api.execution;

/** Another writer already owns the execution-memory root (exclusive ownership, no waiting). */
public final class ExecutionMemoryLockedException extends RuntimeException {
    public ExecutionMemoryLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
