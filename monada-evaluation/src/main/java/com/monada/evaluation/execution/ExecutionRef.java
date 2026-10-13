package com.monada.evaluation.execution;

import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ScopeId;
import java.util.Comparator;
import java.util.Objects;

/**
 * Identity of one execution inside a comparison. Execution ids are unique per scope only, so the scope is part
 * of the identity. Natural order is execution id then scope, which gives reports a stable presentation order.
 */
public record ExecutionRef(ScopeId scope, ExecutionId execution) implements Comparable<ExecutionRef> {
    private static final Comparator<ExecutionRef> ORDER = Comparator
            .comparing((ExecutionRef r) -> r.execution().value())
            .thenComparing(r -> r.scope().value());

    public ExecutionRef {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(execution, "execution");
    }

    @Override
    public int compareTo(ExecutionRef other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return scope + "/" + execution;
    }
}
