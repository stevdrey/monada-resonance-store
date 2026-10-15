package com.monada.evaluation;

import java.lang.management.ManagementFactory;

/**
 * Optional per-thread allocation accounting ({@code com.sun.management.ThreadMXBean}). Read-only: when
 * accounting is unsupported or disabled the figures are reported as not measured, never force-enabled.
 * Shared by {@link BoundedTopKExperimentRunner} and {@link VectorCopyShareRunner}.
 */
record AllocationMeter(com.sun.management.ThreadMXBean bean) {

    static AllocationMeter create() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
            return new AllocationMeter(bean);
        }
        return new AllocationMeter(null);
    }

    boolean supported() {
        return bean != null;
    }

    long currentThreadAllocatedBytes() {
        return bean == null ? 0L : bean.getCurrentThreadAllocatedBytes();
    }
}
