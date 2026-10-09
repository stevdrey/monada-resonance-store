package com.monada.api.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.AppendResult;
import com.monada.storage.execution.ExecutionLedger;
import com.monada.storage.execution.LedgerLockedException;
import com.monada.storage.execution.LedgerRecord;
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
 * <p>This facade only records and reads ledger history. It never maps outcomes to ranking feedback, does not
 * change {@code MonadaMemory}, and exposes no cost, recall or export operation.
 *
 * <p>Failures: invalid or illegal events throw {@link IllegalArgumentException} before any I/O (the ledger's
 * {@code LedgerRejectedException} category is preserved); I/O and corruption throw {@link UncheckedIOException}.
 */
public final class ExecutionMemory implements AutoCloseable {
    private final ExecutionLedger ledger;
    private final ScopeId scope;
    private final ExecutionMemoryConfig config;
    private boolean closed;

    private ExecutionMemory(ExecutionLedger ledger, ScopeId scope, ExecutionMemoryConfig config) {
        this.ledger = ledger;
        this.scope = scope;
        this.config = config;
    }

    public static ExecutionMemory open(Path root, ScopeId scope, ExecutionMemoryConfig config) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(config, "config");
        try {
            return new ExecutionMemory(ExecutionLedger.open(root, scope), scope, config);
        } catch (LedgerLockedException e) {
            throw new ExecutionMemoryLockedException("execution memory " + root + " is owned by another writer", e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open execution memory " + root, e);
        }
    }

    public static ExecutionMemory open(Path root, ScopeId scope) {
        return open(root, scope, ExecutionMemoryConfig.defaults());
    }

    public ScopeId scope() {
        requireOpen();
        return scope;
    }

    /**
     * Appends {@code event}: {@code APPENDED}, {@code IDEMPOTENT} (identical retry, nothing written) or
     * {@code CONFLICT} (nothing written). Retrying a revision never charges its usage twice because replay
     * counts each fact once.
     */
    public RecordResult record(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        try {
            AppendResult r = ledger.append(event);
            return new RecordResult(RecordResult.Status.valueOf(r.status().name()), r.sequence(), r.reason());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot record event " + event.eventId(), e);
        }
    }

    public Optional<ExecutionView> loadExecution(ScopeId scope, ExecutionId execution) {
        Objects.requireNonNull(execution, "execution");
        requireOpen(scope);
        return ExecutionViews.execution(ledger.replay(), execution);
    }

    public Optional<AttemptView> loadAttempt(ScopeId scope, ExecutionId execution, AttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt");
        return loadExecution(scope, execution).flatMap(v -> v.attempt(attempt));
    }

    /**
     * Exact revision lookup: {@code eventId} may name any event of a correction chain; the record of that
     * chain with the given revision is returned (1 = the original).
     */
    public Optional<LedgerRecord> loadEvent(ScopeId scope, EventId eventId, int revision) {
        Objects.requireNonNull(eventId, "eventId");
        requireOpen(scope);
        List<LedgerRecord> all = ledger.replay();
        Optional<LedgerRecord> cursor = ledger.find(eventId);
        if (cursor.isEmpty()) {
            return Optional.empty();
        }
        while (cursor.get().event().envelope().supersedes().isPresent()) { // walk back to revision 1
            cursor = ledger.find(cursor.get().event().envelope().supersedes().get());
        }
        while (cursor.isPresent() && cursor.get().event().envelope().revision() < revision) { // walk forward
            EventId current = cursor.get().event().eventId();
            cursor = all.stream()
                    .filter(r -> r.event().envelope().supersedes().filter(current::equals).isPresent())
                    .findFirst();
        }
        return cursor.filter(r -> r.event().envelope().revision() == revision);
    }

    /** Latest revision of the event chain that contains {@code eventId}. */
    public Optional<LedgerRecord> loadEvent(ScopeId scope, EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        requireOpen(scope);
        int latest = 0;
        for (LedgerRecord r : ledger.replay()) {
            if (r.event().eventId().equals(eventId)) {
                latest = r.event().envelope().revision();
            }
        }
        Optional<LedgerRecord> found = latest == 0 ? Optional.empty() : loadEvent(scope, eventId, latest);
        return found.isPresent() ? latestOf(found.get()) : Optional.empty();
    }

    private Optional<LedgerRecord> latestOf(LedgerRecord start) {
        Optional<LedgerRecord> current = Optional.of(start);
        while (true) {
            EventId id = current.get().event().eventId();
            Optional<LedgerRecord> next = ledger.replay().stream()
                    .filter(r -> r.event().envelope().supersedes().filter(id::equals).isPresent())
                    .findFirst();
            if (next.isEmpty()) {
                return current;
            }
            current = next;
        }
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
        List<LedgerRecord> all = ledger.replay(); // sequences are contiguous from 1
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
        List<LedgerRecord> page = after >= to ? List.of() : all.subList((int) after, to);
        long lastReturned = page.isEmpty() ? after : page.get(page.size() - 1).sequence();
        boolean hasMore = lastReturned < highWatermark;
        return new HistoryPage(page, highWatermark, hasMore,
                hasMore ? Optional.of(new HistoryCursor(scope, highWatermark, lastReturned)) : Optional.empty());
    }

    /** Releases the writer lock. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ledger.close();
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
