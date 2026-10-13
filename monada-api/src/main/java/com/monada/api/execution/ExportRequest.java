package com.monada.api.execution;

import com.monada.core.execution.ScopeId;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit, caller-owned parameters of {@link ExecutionMemory#exportSamples}: the scope, the cutoff, a bounded
 * page size, the view and the evidence policy. A first page is built with {@link #first}; every further page is
 * {@link #resume}d from the {@link ExportCursor} of the previous one, which carries the resolved cutoff, view and
 * gates so none of them can change mid-export.
 */
public record ExportRequest(ScopeId scope, Optional<ExportCutoff> cutoff, int pageSize, ExportView view,
                            ExportEvidencePolicy evidencePolicy, Optional<ExportCursor> cursor) {
    public ExportRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(evidencePolicy, "evidencePolicy");
        Objects.requireNonNull(cursor, "cursor");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        if (cursor.isPresent()) {
            if (cutoff.isPresent()) {
                throw new IllegalArgumentException("a resumed export takes its cutoff from the cursor");
            }
            ExportCursor c = cursor.get();
            if (!c.scope().equals(scope) || c.view() != view
                    || !c.additionalGates().equals(evidencePolicy.additionalGates())) {
                throw new IllegalArgumentException("request options disagree with the export cursor");
            }
        } else if (cutoff.isEmpty()) {
            throw new IllegalArgumentException("a first page needs a cutoff (use ExportCutoff.latest())");
        }
    }

    public static ExportRequest first(ScopeId scope, ExportCutoff cutoff, int pageSize, ExportView view,
                                      ExportEvidencePolicy evidencePolicy) {
        return new ExportRequest(scope, Optional.of(cutoff), pageSize, view, evidencePolicy, Optional.empty());
    }

    /** Adapter-ready export of everything recorded, with only the execution policies' own gates. */
    public static ExportRequest firstPage(ScopeId scope, int pageSize) {
        return first(scope, ExportCutoff.latest(), pageSize, ExportView.ADAPTER_READY,
                ExportEvidencePolicy.executionPolicyOnly());
    }

    public static ExportRequest resume(ExportCursor cursor, int pageSize) {
        Objects.requireNonNull(cursor, "cursor");
        return new ExportRequest(cursor.scope(), Optional.empty(), pageSize, cursor.view(),
                new ExportEvidencePolicy(cursor.additionalGates()), Optional.of(cursor));
    }
}
