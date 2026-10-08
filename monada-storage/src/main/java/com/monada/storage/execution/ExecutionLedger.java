package com.monada.storage.execution;

import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Single-writer, append-only execution ledger for one scope (contract sections 2-7).
 *
 * <p>Opening takes an exclusive OS lock on {@code <root>/write.lock}; a second writable open fails
 * immediately with {@link LedgerLockedException} and there is no multi-writer mode. The lock is released by
 * {@link #close()} (idempotent) or when {@code open} fails. Opening validates the whole existing segment and
 * refuses a ledger with a torn tail or any corruption: this class never truncates or repairs.
 *
 * <p>{@link #append} returns {@code APPENDED} only after the complete record line has been written and
 * forced to the storage device. That survives a process crash; it is not a transactional guarantee against
 * operating-system or hardware failure, it does not fsync the parent directory, and network file systems are
 * unsupported. If a write fails, the instance becomes unusable (a partial line may exist) and the next
 * writable open reports it as a torn tail. Instances are not thread-safe.
 */
public final class ExecutionLedger implements AutoCloseable {
    private final ScopeId scope;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Path segment;
    private final LedgerState state;
    private final List<LedgerRecord> records;
    private FileChannel segmentChannel;
    private boolean closed;
    private boolean failed;

    private ExecutionLedger(ScopeId scope, FileChannel lockChannel, FileLock lock, Path segment,
                            LedgerScanner.Result loaded) {
        this.scope = scope;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.segment = segment;
        this.state = loaded.state();
        this.records = new ArrayList<>(loaded.records());
    }

    /**
     * Opens (creating the ledger root, manifest and the scope directory when absent) the ledger of
     * {@code scope} for writing.
     */
    public static ExecutionLedger open(Path root, ScopeId scope) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scope, "scope");
        rejectLegacyStore(root);
        Files.createDirectories(root);
        LedgerPaths paths = LedgerPaths.existing(root);
        FileChannel channel = FileChannel.open(paths.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                throw new LedgerLockedException("execution ledger " + root + " is locked by another writer");
            }
            ensureManifest(paths);
            Path scopeDir = prepareScope(paths, scope);
            Path segment = paths.segment(scopeDir);
            LedgerScanner.Result loaded = Files.exists(segment, LinkOption.NOFOLLOW_LINKS)
                    ? LedgerScanner.scan(segment, paths.display(segment), scope)
                    : LedgerScanner.Result.empty();
            if (loaded.hasErrors() || loaded.tornTail()) {
                throw new LedgerException("execution ledger scope " + scope + " is not healthy; refusing a "
                        + "writable open (no automatic repair): " + loaded.diagnostics().get(0).message(),
                        loaded.diagnostics());
            }
            return new ExecutionLedger(scope, channel, lock, segment, loaded);
        } catch (IOException | RuntimeException e) {
            release(lock, channel);
            throw e;
        }
    }

    private static void rejectLegacyStore(Path root) throws LedgerException {
        Path legacy = root.resolve(LedgerPaths.LEGACY_MANIFEST);
        if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(root.resolve(LedgerManifest.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
            throw new LedgerException(root + " holds a legacy " + LedgerPaths.LEGACY_MANIFEST
                    + " store and no " + LedgerManifest.FILE_NAME + "; use a separate execution ledger directory");
        }
    }

    private static void ensureManifest(LedgerPaths paths) throws IOException {
        Path manifest = paths.manifest();
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            LedgerManifest.validate(manifest);
            return;
        }
        if (Files.exists(paths.root().resolve(LedgerPaths.SCOPES_DIR), LinkOption.NOFOLLOW_LINKS)) {
            String message = "ledger data exists but " + LedgerManifest.FILE_NAME
                    + " is missing; refusing to assert a format for it (no automatic repair)";
            throw new LedgerException(message, List.of(LedgerDiagnostic.error(
                    LedgerDiagnosticCategory.MANIFEST_MISSING, LedgerManifest.FILE_NAME, 0, OptionalLong.empty(),
                    message)));
        }
        writeDurably(manifest, LedgerManifest.render().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the scope directory, publishing a new one atomically: it is built under a private staging name
     * (scope.id and ledger/ included) and renamed into place, so a concurrent reader never sees a partial
     * scope. A staging directory left by an earlier crash is only ever ours (we hold the write lock) and is
     * discarded.
     */
    private static Path prepareScope(LedgerPaths paths, ScopeId scope) throws IOException {
        Path scopeDir = paths.scopeDir(scope);
        if (Files.exists(scopeDir, LinkOption.NOFOLLOW_LINKS)) {
            ExecutionLedgerReader.verifyScopeId(paths, scopeDir, scope);
            if (!Files.isDirectory(paths.ledgerDir(scopeDir))) {
                throw new LedgerException("required ledger directory is missing: "
                        + paths.display(paths.ledgerDir(scopeDir)));
            }
            return scopeDir;
        }
        Files.createDirectories(paths.scopesDir());
        Path staging = paths.stagingScopeDir(scope);
        deleteTree(staging);
        Files.createDirectory(staging);
        Files.createDirectory(staging.resolve(LedgerPaths.LEDGER_DIR));
        writeDurably(staging.resolve(LedgerPaths.SCOPE_ID_FILE), scope.value().getBytes(StandardCharsets.UTF_8));
        Files.move(staging, scopeDir, StandardCopyOption.ATOMIC_MOVE);
        return paths.scopeDir(scope);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void writeDurably(Path file, byte[] content) throws IOException {
        try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeFully(out, ByteBuffer.wrap(content));
            out.force(true);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void release(FileLock lock, FileChannel channel) {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // closing the channel below releases the lock as well
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing more can be done
        }
    }

    public ScopeId scope() {
        return scope;
    }

    /**
     * Appends {@code event}.
     *
     * @return {@code APPENDED} (sequence assigned), {@code IDEMPOTENT} (identical event already present,
     *         nothing written) or {@code CONFLICT} (same id with a different payload, or an identity clash;
     *         nothing written)
     * @throws LedgerRejectedException for an invalid event (wrong scope, missing reference, impossible order,
     *                                 oversized record) before any I/O
     */
    public AppendResult append(ExecutionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        requireUsable();
        if (!event.scopeId().equals(scope)) {
            throw new LedgerRejectedException(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH,
                    "event scope '" + event.scopeId() + "' is not the ledger scope '" + scope + "'");
        }
        byte[] payload = EventPayloadCodec.encode(event);
        Optional<LedgerState.Entry> existing = state.find(event.eventId());
        if (existing.isPresent()) {
            return LedgerState.samePayload(existing.get(), payload)
                    ? AppendResult.idempotent(existing.get().sequence())
                    : AppendResult.conflict("event id " + event.eventId()
                    + " already exists with a different payload (revision, content or timestamps differ)");
        }
        Optional<LedgerState.Violation> violation = state.check(event);
        if (violation.isPresent()) {
            if (violation.get().conflict()) {
                return AppendResult.conflict(violation.get().message());
            }
            throw new LedgerRejectedException(violation.get().category(), violation.get().message());
        }
        long sequence = state.lastSequence() + 1;
        byte[] line = RecordLine.frame(sequence, payload);
        if (line.length > RecordLine.MAX_LINE_BYTES) {
            throw new LedgerRejectedException(LedgerDiagnosticCategory.OVERSIZED_RECORD,
                    "encoded record is " + line.length + " bytes; the limit is " + RecordLine.MAX_LINE_BYTES);
        }
        try {
            if (segmentChannel == null) {
                segmentChannel = FileChannel.open(segment, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            }
            writeFully(segmentChannel, ByteBuffer.wrap(line));
            segmentChannel.force(true);
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
        state.apply(sequence, event, payload);
        records.add(new LedgerRecord(sequence, event));
        return AppendResult.appended(sequence);
    }

    /** Exact lookup by event id. */
    public Optional<LedgerRecord> find(EventId eventId) {
        requireUsable();
        return state.find(Objects.requireNonNull(eventId, "eventId"))
                .map(e -> new LedgerRecord(e.sequence(), e.event()));
    }

    /** All records in ascending sequence order. */
    public List<LedgerRecord> replay() {
        requireUsable();
        return List.copyOf(records);
    }

    public ExecutionReplay view() {
        requireUsable();
        return ExecutionReplay.of(scope, records);
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException("execution ledger is closed");
        }
        if (failed) {
            throw new IllegalStateException("execution ledger is unusable after a failed write; close and reopen it");
        }
    }

    /** Releases the writer lock. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (segmentChannel != null) {
            try {
                segmentChannel.close();
            } catch (IOException ignored) {
                // the lock below must still be released
            }
        }
        release(lock, lockChannel);
    }
}
