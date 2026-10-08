package com.monada.storage.execution;

import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ScopeId;
import java.util.List;

/** Public bridge to the package-private {@link Events} fixtures for tests in other packages. */
public final class TestScopes {
    public static final ScopeId SCOPE = Events.SCOPE;

    private TestScopes() {
    }

    public static List<ExecutionEvent> fullRun() {
        return Events.fullRun();
    }
}
