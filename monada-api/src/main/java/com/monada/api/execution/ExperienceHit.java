package com.monada.api.execution;

import com.monada.core.execution.ExperienceRef;
import java.util.Objects;

/**
 * One recalled experience: the exact ledger reference, the projected atom it was found through and the
 * resonance similarity of that atom to the query.
 *
 * <p>{@code similarity} is lexical resonance similarity only. It is <b>not</b> quality, acceptance or
 * confidence; use {@link ExecutionMemory#loadExecution} for outcomes and evidence. {@code event} is the exact
 * ledger record addressed by {@code ref} (its event ID and revision), never reconstructed metadata.
 * {@code projectionVersion} and {@code coveredSequence} identify the projection that produced the hit.
 */
public record ExperienceHit(ExperienceRef ref, String atomId, double similarity, HistoryEntry event,
                            int projectionVersion, long coveredSequence) {
    public ExperienceHit {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(atomId, "atomId");
        Objects.requireNonNull(event, "event");
        if (!event.event().eventId().equals(ref.eventId())
                || event.event().envelope().revision() != ref.revision()) {
            throw new IllegalArgumentException("event does not match the experience ref");
        }
    }
}
