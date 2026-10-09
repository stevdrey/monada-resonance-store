package com.monada.storage.execution;

import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionLimits;
import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * Read-only snapshot of one scope of an execution ledger. Opening never creates, locks or modifies any
 * file, so it may run next to a live writer and sees a valid prefix of the ledger. Problems are reported
 * as {@link #diagnostics()}; the records returned are the longest valid prefix. A missing scope is an
 * empty ledger, a missing or unsupported manifest fails with a {@link LedgerException}. A scope directory
 * is published atomically by the writer, so one that exists without {@code scope.id} or {@code ledger/}
 * is damaged, not in progress.
 */
public final class ExecutionLedgerReader {
    private static final int MAX_SCOPE_ID_BYTES = ExecutionLimits.MAX_IDENTIFIER_CODE_POINTS * 4;

    private final ScopeId scope;
    private final LedgerScanner.Result result;

    private ExecutionLedgerReader(ScopeId scope, LedgerScanner.Result result) {
        this.scope = scope;
        this.result = result;
    }

    public static ExecutionLedgerReader open(Path root, ScopeId scope) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scope, "scope");
        LedgerPaths paths = readableRoot(root);
        if (!scopesDirectoryExists(paths)) {
            return new ExecutionLedgerReader(scope, LedgerScanner.Result.empty());
        }
        Path scopeDir = paths.scopeDir(scope);
        if (!Files.exists(scopeDir, LinkOption.NOFOLLOW_LINKS)) {
            return new ExecutionLedgerReader(scope, LedgerScanner.Result.empty());
        }
        verifyScopeId(paths, scopeDir, scope);
        return load(paths, scopeDir, scope, List.of());
    }

    /**
     * Opens the scope stored in the discovered directory {@code scopes/<directoryName>} (used by the audit).
     * The directory is checked for containment before any child is read, its {@code scope.id} must hash to
     * the directory name, and that same directory is the one scanned.
     */
    public static ExecutionLedgerReader openScopeDirectory(Path root, String directoryName) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(directoryName, "directoryName");
        LedgerPaths paths = readableRoot(root);
        scopesDirectoryExists(paths);
        Path scopeDir = paths.contained(paths.scopesDir().resolve(directoryName));
        String shown = paths.display(scopeDir);
        if (!Files.isDirectory(scopeDir)) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, shown, "scope entry is not a directory");
        }
        String id = readScopeId(paths, scopeDir);
        ScopeId scope;
        try {
            scope = ScopeId.of(id);
        } catch (RuntimeException e) {
            throw failure(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, paths.display(paths.scopeIdFile(scopeDir)),
                    "scope.id is not a valid scope id");
        }
        // A renamed directory is reported, but its records are still scanned so no other damage is hidden.
        List<LedgerDiagnostic> extra = LedgerPaths.scopeDirectoryName(scope).equals(directoryName)
                ? List.of()
                : List.of(LedgerDiagnostic.error(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, shown, 0,
                OptionalLong.empty(), "directory name does not match the SHA-256 of its scope.id"));
        return load(paths, scopeDir, scope, extra);
    }

    /**
     * Checks that {@code write.lock}, if present, is a contained regular file, so an audit does not certify
     * a ledger that cannot accept a writer. The lock is neither created nor acquired.
     */
    public static void verifyWriterLockPath(Path root) throws IOException {
        LedgerPaths paths = readableRoot(Objects.requireNonNull(root, "root"));
        Path lock = paths.lock();
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock)) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, paths.display(lock),
                    "write.lock is not a regular file; a writer cannot open this ledger");
        }
    }

    /** Validates only the ledger manifest of {@code root}; opens no scope. Used by the audit. */
    public static void verifyManifest(Path root) throws IOException {
        readableRoot(Objects.requireNonNull(root, "root"));
    }

    /**
     * Names of the entries of {@code scopes/}, sorted. The directory is checked for containment before it
     * is listed, so a symbolic link leaving the root fails with {@code PATH_ESCAPE} without reading outside.
     * Returns an empty list when the ledger has no {@code scopes/} yet.
     */
    public static List<String> listScopeDirectories(Path root) throws IOException {
        LedgerPaths paths = readableRoot(Objects.requireNonNull(root, "root"));
        if (!scopesDirectoryExists(paths)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(paths.scopesDir())) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * True when {@code scopes/} exists and is a directory; false when it is absent (no scope was ever
     * written). Anything else at that path is structural damage, never an empty ledger.
     */
    private static boolean scopesDirectoryExists(LedgerPaths paths) throws IOException {
        Path scopes = paths.scopesDir();
        if (!Files.exists(scopes, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(scopes)) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, paths.display(scopes),
                    "scopes is not a directory");
        }
        return true;
    }

    private static LedgerPaths readableRoot(Path root) throws IOException {
        LedgerPaths paths = LedgerPaths.existing(root);
        Path manifest = paths.manifest();
        if (!Files.isRegularFile(manifest)) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_MISSING, LedgerManifest.FILE_NAME,
                    "execution ledger manifest " + LedgerManifest.FILE_NAME + " is missing");
        }
        LedgerManifest.validate(manifest);
        return paths;
    }

    private static ExecutionLedgerReader load(LedgerPaths paths, Path scopeDir, ScopeId scope,
                                              List<LedgerDiagnostic> extra) throws IOException {
        Path ledgerDir = paths.ledgerDir(scopeDir);
        if (!Files.isDirectory(ledgerDir)) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, paths.display(ledgerDir),
                    "required ledger directory is missing or not a directory");
        }
        Path segment = paths.segment(scopeDir);
        if (Files.exists(segment, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(segment)) {
            throw failure(LedgerDiagnosticCategory.MALFORMED_RECORD, paths.display(segment),
                    "ledger segment is not a regular file");
        }
        if (!Files.exists(segment, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, paths.display(segment),
                    "ledger segment is missing from an existing scope");
        }
        LedgerScanner.Result scanned = LedgerScanner.scan(segment, paths.display(segment), scope);
        List<LedgerDiagnostic> diagnostics = new ArrayList<>(scanned.diagnostics());
        diagnostics.addAll(extra);
        try (Stream<Path> entries = Files.list(ledgerDir)) {
            for (Path entry : entries.sorted().toList()) {
                if (!entry.getFileName().toString().equals(LedgerPaths.SEGMENT_FILE)) {
                    diagnostics.add(LedgerDiagnostic.warning(LedgerDiagnosticCategory.UNSUPPORTED_VERSION,
                            paths.display(entry), 0, OptionalLong.empty(),
                            "unexpected file in ledger directory (v1 reads a single segment; ignored)"));
                }
            }
        }
        Collections.sort(diagnostics);
        return new ExecutionLedgerReader(scope, new LedgerScanner.Result(scanned.records(), List.copyOf(diagnostics),
                scanned.state(), scanned.tornTail()));
    }

    static void verifyScopeId(LedgerPaths paths, Path scopeDir, ScopeId scope) throws IOException {
        String found = readScopeId(paths, scopeDir);
        if (!found.equals(scope.value())) {
            throw failure(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, paths.display(paths.scopeIdFile(scopeDir)),
                    "scope.id does not match the requested scope");
        }
    }

    private static String readScopeId(LedgerPaths paths, Path scopeDir) throws IOException {
        Path file = paths.scopeIdFile(scopeDir);
        String shown = paths.display(file);
        Optional<byte[]> bytes;
        try {
            bytes = LedgerPaths.readBounded(file, MAX_SCOPE_ID_BYTES);
        } catch (IOException | RuntimeException e) {
            throw failure(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, shown, "scope.id is missing or unreadable");
        }
        if (bytes.isEmpty()) {
            throw failure(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, shown, "scope.id is larger than any valid scope id");
        }
        try {
            return LedgerPaths.decodeStrict(bytes.get());
        } catch (CharacterCodingException e) {
            throw failure(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, shown, "scope.id is not valid UTF-8");
        }
    }

    private static LedgerException failure(LedgerDiagnosticCategory category, String file, String message) {
        return new LedgerException(message, List.of(LedgerDiagnostic.error(category, file, 0, OptionalLong.empty(),
                message)));
    }

    public ScopeId scope() {
        return scope;
    }

    /** Valid records in ascending sequence order. */
    public List<LedgerRecord> replay() {
        return result.records();
    }

    /** Exact lookup by event id within the valid prefix. */
    public Optional<LedgerRecord> find(EventId eventId) {
        return result.state().find(Objects.requireNonNull(eventId, "eventId"))
                .map(e -> new LedgerRecord(e.sequence(), e.event()));
    }

    public ExecutionReplay view() {
        return ExecutionReplay.of(scope, result.records());
    }

    /** Deterministically ordered diagnostics; empty for a healthy ledger. */
    public List<LedgerDiagnostic> diagnostics() {
        return result.diagnostics();
    }

    public boolean isHealthy() {
        return result.diagnostics().isEmpty();
    }

    public boolean hasTornTail() {
        return result.tornTail();
    }
}
