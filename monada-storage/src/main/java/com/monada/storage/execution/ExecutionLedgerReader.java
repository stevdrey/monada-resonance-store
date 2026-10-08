package com.monada.storage.execution;

import com.monada.core.execution.EventId;
import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Read-only snapshot of one scope of an execution ledger. Opening never creates, locks or modifies any
 * file, so it may run next to a live writer and sees a valid prefix of the ledger. Problems are reported
 * as {@link #diagnostics()}; the records returned are the longest valid prefix. A missing scope is an
 * empty ledger, a missing or unsupported manifest fails with a {@link LedgerException}.
 */
public final class ExecutionLedgerReader {
    private final ScopeId scope;
    private final LedgerScanner.Result result;

    private ExecutionLedgerReader(ScopeId scope, LedgerScanner.Result result) {
        this.scope = scope;
        this.result = result;
    }

    public static ExecutionLedgerReader open(Path root, ScopeId scope) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scope, "scope");
        LedgerPaths paths = LedgerPaths.existing(root);
        Path manifest = paths.manifest();
        if (!Files.isRegularFile(manifest)) {
            String message = "execution ledger manifest " + LedgerManifest.FILE_NAME + " is missing";
            throw new LedgerException(message, List.of(LedgerDiagnostic.error(
                    LedgerDiagnosticCategory.MANIFEST_MISSING, LedgerManifest.FILE_NAME, 0, OptionalLong.empty(), message)));
        }
        LedgerManifest.validate(manifest);
        Path scopeDir = paths.scopeDir(scope);
        if (!Files.exists(scopeDir, LinkOption.NOFOLLOW_LINKS)) {
            return new ExecutionLedgerReader(scope, LedgerScanner.Result.empty());
        }
        verifyScopeId(paths, scopeDir, scope);
        Path segment = paths.segment(scopeDir);
        if (!Files.exists(segment, LinkOption.NOFOLLOW_LINKS)) {
            return new ExecutionLedgerReader(scope, LedgerScanner.Result.empty());
        }
        return new ExecutionLedgerReader(scope, LedgerScanner.scan(segment, paths.display(segment), scope));
    }

    static void verifyScopeId(LedgerPaths paths, Path scopeDir, ScopeId scope) throws IOException {
        Path file = paths.scopeIdFile(scopeDir);
        String shown = paths.display(file);
        String found;
        try {
            found = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw mismatch(shown, "scope.id is missing or unreadable");
        }
        if (!found.equals(scope.value())) {
            throw mismatch(shown, "scope.id does not match the requested scope");
        }
    }

    private static LedgerException mismatch(String file, String message) {
        return new LedgerException(message, List.of(LedgerDiagnostic.error(
                LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, file, 0, OptionalLong.empty(), message)));
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
