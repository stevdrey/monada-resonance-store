package com.monada.api.execution;

import java.util.List;
import java.util.Objects;

/**
 * State of one scope's derived projection (contract v1, section 11). The ledger is always authoritative;
 * the projection only serves {@link ExecutionMemory#recall}.
 *
 * <ul>
 *   <li>{@code CURRENT}: every ledger event up to {@code ledgerSequence} is projected.</li>
 *   <li>{@code STALE}: the projection covers only {@code coveredSequence < ledgerSequence} (a projection write
 *   failed or was interrupted). Recall still answers from the covered prefix.</li>
 *   <li>{@code MISSING}: there is no projection (for example a scope recorded before projections existed, or
 *   a rebuild interrupted before publication). Recall returns no hits.</li>
 *   <li>{@code INCOMPATIBLE}: the projection cannot be trusted (unknown version, corrupt checkpoint, mapping or
 *   atoms that disagree with the ledger). Recall returns no hits; nothing is rewritten automatically.</li>
 * </ul>
 *
 * <p>Every state except {@code CURRENT} is reconciled only by an explicit
 * {@link ExecutionMemory#rebuildProjection}. {@code diagnostics} explains non-current states.
 */
public record ProjectionStatus(State state, long coveredSequence, long ledgerSequence, int projectionVersion,
                               List<String> diagnostics) {

    public enum State { CURRENT, STALE, MISSING, INCOMPATIBLE }

    public ProjectionStatus {
        Objects.requireNonNull(state, "state");
        if (coveredSequence < 0 || ledgerSequence < 0) {
            throw new IllegalArgumentException("sequences must be >= 0");
        }
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean isCurrent() {
        return state == State.CURRENT;
    }
}
