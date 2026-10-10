package com.monada.api.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.AppendResult;
import com.monada.storage.execution.ExecutionLedger;
import com.monada.storage.execution.ExecutionLedgerReader;
import com.monada.storage.execution.LedgerDiagnostic;
import com.monada.storage.execution.LedgerException;
import com.monada.storage.execution.LedgerLockedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Caller-owned, ledger-backed execution history for one scope (contract v1, section 17).
 *
 * <p>The host supplies the root, the scope, identifiers, timestamps and evidence; nothing is global, nothing
 * reads the clock and no thread is started. {@link #open} takes the exclusive writer lock of the root;
 * a second open fails immediately with {@link ExecutionMemoryLockedException}. The instance is a
 * <b>single writer and is not thread-safe</b>: the host serializes calls and calls {@link #close()} (idempotent)
 * to release ownership. Every operation after close throws {@link IllegalStateException}.
 *
 * <p>{@link #openReadOnly} gives a lock-free snapshot for hosts that only inspect history.
 *
 * <p>The facade records and reads ledger history and keeps a derived, per-scope projection for bounded
 * {@link #recall} (contract v1, section 11). The ledger is written first and stays authoritative: a failed
 * projection write never fails or repeats {@link #record}; it leaves {@link #projectionStatus} {@code STALE}
 * until an explicit {@link #rebuildProjection}. It never maps outcomes to ranking feedback, does not change
 * {@code MonadaMemory}, and exposes no cost or export operation.
 *
 * <p>Failures: invalid or illegal events throw {@link IllegalArgumentException} before any I/O (the ledger's
 * {@code LedgerRejectedException} category is preserved); I/O and corruption throw {@link UncheckedIOException}.
 */
public final class ExecutionMemory implements AutoCloseable {
    private final ExecutionLedger ledger; // null for a read-only instance
    private final HistoryIndex index;
    private final boolean tornTail;
    private final ScopeId scope;
    private final ExecutionMemoryConfig config;
    private final ExperienceProjection projection;
    private boolean closed;

    private ExecutionMemory(ExecutionLedger ledger, HistoryIndex index, boolean tornTail, ScopeId scope,
                            ExecutionMemoryConfig config, ExperienceProjection projection) {
        this.ledger = ledger;
        this.index = index;
        this.tornTail = tornTail;
        this.scope = scope;
        this.config = config;
        this.projection = projection;
    }

    /**
     * Opens the scope for writing. The scope's projection is loaded and verified against the ledger; it is
     * created only for an empty ledger and is never repaired here (see {@link #projectionStatus}).
     */
    public static ExecutionMemory open(Path root, ScopeId scope, ExecutionMemoryConfig config) {
        return open(root, scope, config, ProjectionFaults.NONE);
    }

    static ExecutionMemory open(Path root, ScopeId scope, ExecutionMemoryConfig config, ProjectionFaults faults) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(config, "config");
        try {
            ExecutionLedger opened = ExecutionLedger.open(root, scope);
            try {
                HistoryIndex index = HistoryIndex.of(opened.replay());
                ExperienceProjection projection = ExperienceProjection.open(root, scope, index.all(), true, faults);
                return new ExecutionMemory(opened, index, false, scope, config, projection);
            } catch (RuntimeException e) {
                opened.close();
                throw e;
            }
        } catch (LedgerLockedException e) {
            throw new ExecutionMemoryLockedException("execution memory " + root + " is owned by another writer", e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open execution memory " + root, e);
        }
    }

    public static ExecutionMemory open(Path root, ScopeId scope) {
        return open(root, scope, ExecutionMemoryConfig.defaults());
    }

    /**
     * Opens a read-only <b>snapshot</b> of the scope: it takes no lock, creates nothing and may run next to a
     * live writer, seeing the valid prefix of the ledger at the moment of opening. Later appends are not
     * visible; open a new read-only instance to see them. {@link #record} and {@link #rebuildProjection} are
     * unsupported; {@link #recall} reads an existing projection and never creates one. A missing root or
     * manifest fails with {@link UncheckedIOException}, and so does a ledger with integrity errors (the
     * writable open refuses it too); a missing scope is an empty history. A torn tail (possibly a writer
     * mid-append) is tolerated and reported by {@link #hasTornTail()}.
     */
    public static ExecutionMemory openReadOnly(Path root, ScopeId scope, ExecutionMemoryConfig config) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(config, "config");
        try {
            ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, scope);
            List<LedgerDiagnostic> errors = reader.diagnostics().stream()
                    .filter(d -> d.severity() == LedgerDiagnostic.Severity.ERROR).toList();
            if (!errors.isEmpty()) {
                throw new UncheckedIOException(new LedgerException("execution memory " + root + " is not healthy; "
                        + "refusing a read-only open: " + errors.get(0).message(), errors));
            }
            HistoryIndex index = HistoryIndex.of(reader.replay());
            ExperienceProjection projection = ExperienceProjection.open(root, scope, index.all(), false,
                    ProjectionFaults.NONE);
            return new ExecutionMemory(null, index, reader.hasTornTail(), scope, config, projection);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open execution memory " + root + " read-only", e);
        }
    }

    public static ExecutionMemory openReadOnly(Path root, ScopeId scope) {
        return openReadOnly(root, scope, ExecutionMemoryConfig.defaults());
    }

    /** True when this read-only snapshot ended at an incomplete final record; always false when writable. */
    public boolean hasTornTail() {
        requireOpen();
        return tornTail;
    }

    public ScopeId scope() {
        requireOpen();
        return scope;
    }

    /**
     * Appends {@code event}: {@code APPENDED}, {@code IDEMPOTENT} (identical retry, nothing written) or
     * {@code CONFLICT} (nothing written). Retrying a revision never charges its usage twice because replay
     * counts each fact once. An appended event is then projected when the projection is {@code CURRENT}; if
     * that fails the result is still {@code APPENDED} and the projection becomes {@code STALE}.
     */
    public RecordResult record(ExecutionEvent event) {
        requireOpen();
        Objects.requireNonNull(event, "event");
        if (ledger == null) {
            throw new UnsupportedOperationException("this execution memory is read-only");
        }
        try {
            AppendResult r = ledger.append(event);
            if (r.status() == AppendResult.Status.APPENDED) {
                index.add(r.sequence(), event);
                projection.onAppended(index.all().get(index.all().size() - 1), index.all());
            }
            RecordResult.Status status = switch (r.status()) {
                case APPENDED -> RecordResult.Status.APPENDED;
                case IDEMPOTENT -> RecordResult.Status.IDEMPOTENT;
                case CONFLICT -> RecordResult.Status.CONFLICT;
            };
            return new RecordResult(status, r.sequence(), r.reason());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot record event " + event.eventId(), e);
        }
    }

    public Optional<ExecutionView> loadExecution(ScopeId scope, ExecutionId execution) {
        requireOpen(scope);
        Objects.requireNonNull(execution, "execution");
        return ExecutionViews.execution(index.execution(execution), execution);
    }

    public Optional<AttemptView> loadAttempt(ScopeId scope, ExecutionId execution, AttemptId attempt) {
        requireOpen(scope);
        Objects.requireNonNull(attempt, "attempt");
        return loadExecution(scope, execution).flatMap(v -> v.attempt(attempt));
    }

    /**
     * Exact revision lookup: {@code eventId} may name any event of a correction chain; the record of that
     * chain with the given revision is returned (1 = the original).
     */
    public Optional<HistoryEntry> loadEvent(ScopeId scope, EventId eventId, int revision) {
        requireOpen(scope);
        Objects.requireNonNull(eventId, "eventId");
        return walk(eventId, false, revision);
    }

    /** Latest revision of the event chain that contains {@code eventId}. */
    public Optional<HistoryEntry> loadEvent(ScopeId scope, EventId eventId) {
        requireOpen(scope);
        Objects.requireNonNull(eventId, "eventId");
        return walk(eventId, true, 0);
    }

    /** Walks back to revision 1, then forward through the correction successors. */
    private Optional<HistoryEntry> walk(EventId eventId, boolean latest, int revision) {
        Optional<HistoryEntry> current = index.find(eventId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        while (current.get().event().envelope().supersedes().isPresent()) {
            current = index.find(current.get().event().envelope().supersedes().get());
        }
        while (latest || current.get().event().envelope().revision() < revision) {
            Optional<HistoryEntry> next = index.successorOf(current.get().event().eventId());
            if (next.isEmpty()) {
                break;
            }
            current = next;
        }
        return latest ? current : current.filter(r -> r.event().envelope().revision() == revision);
    }

    /** First page of a new snapshot with the configured default page size. */
    public HistoryPage history(ScopeId scope) {
        return history(scope, null, config.defaultPageSize());
    }

    /** Page of the history; {@code cursor == null} starts a snapshot at the current last sequence. */
    public HistoryPage history(ScopeId scope, HistoryCursor cursor, int pageSize) {
        requireOpen(scope);
        if (pageSize < 1 || pageSize > config.maxPageSize()) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + config.maxPageSize());
        }
        List<HistoryEntry> all = index.all(); // sequences are contiguous from 1
        long last = all.size();
        long highWatermark = last;
        long after = 0;
        if (cursor != null) {
            if (!cursor.scope().equals(scope)) {
                throw new IllegalArgumentException("history cursor belongs to scope " + cursor.scope());
            }
            if (cursor.highWatermark() > last) {
                throw new IllegalArgumentException("history cursor is beyond the end of this ledger");
            }
            highWatermark = cursor.highWatermark();
            after = cursor.lastSequence();
        }
        int to = (int) Math.min(highWatermark, after + pageSize);
        List<HistoryEntry> page = after >= to ? List.of() : all.subList((int) after, to);
        long lastReturned = page.isEmpty() ? after : page.get(page.size() - 1).sequence();
        boolean hasMore = lastReturned < highWatermark;
        return new HistoryPage(page, highWatermark, hasMore,
                hasMore ? Optional.of(new HistoryCursor(scope, highWatermark, lastReturned)) : Optional.empty());
    }

    /** Projection state of the scope and the ledger sequence it covers; side-effect free. */
    public ProjectionStatus projectionStatus(ScopeId scope) {
        requireOpen(scope);
        return projection.status(index.all().size());
    }

    /**
     * Explicit, deterministic reconciliation: rebuilds the scope's projection from the ledger (any prior
     * state, including {@code INCOMPATIBLE}), publishing it only once complete. Re-running it on a current
     * projection yields the same mapping and recall results. The ledger is never modified.
     */
    public ProjectionStatus rebuildProjection(ScopeId scope) {
        requireOpen(scope);
        if (ledger == null) {
            throw new UnsupportedOperationException("this execution memory is read-only");
        }
        return projection.rebuild(index.all());
    }

    /**
     * Bounded recall of prior experiences of this scope only (contract v1, section 11). Scope selection
     * happens before ranking: each scope has its own projection, so another scope can never consume a slot.
     * Runs the existing {@code MonadaMemory} ranking with its production defaults and expands atoms to their
     * exact experience refs. {@code limit} must be 1–{@value ExperienceRecall#MAX_LIMIT}; a blank query
     * returns no hits. A missing or incompatible projection returns no hits with its status; a stale one
     * answers from the covered ledger prefix.
     */
    public ExperienceRecall recall(ScopeId scope, String query, int limit) {
        requireOpen(scope);
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > ExperienceRecall.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + ExperienceRecall.MAX_LIMIT);
        }
        return projection.recall(query, limit, index.all().size(), this::resolve);
    }

    private Optional<HistoryEntry> resolve(ExperienceRef ref) {
        return index.find(ref.eventId())
                .filter(e -> e.event().envelope().revision() == ref.revision())
                .filter(e -> e.event().scopeId().equals(ref.scope())
                        && e.event().envelope().taskId().equals(ref.task())
                        && e.event().executionId().equals(ref.execution())
                        && e.event().envelope().attemptId().equals(ref.attempt()));
    }

    /** Releases the writer lock. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        projection.close();
        if (ledger != null) {
            ledger.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("execution memory is closed");
        }
    }

    private void requireOpen(ScopeId requested) {
        requireOpen();
        Objects.requireNonNull(requested, "scope");
        if (!requested.equals(scope)) {
            throw new IllegalArgumentException("this execution memory is open for scope " + scope
                    + ", not " + requested);
        }
    }
}
