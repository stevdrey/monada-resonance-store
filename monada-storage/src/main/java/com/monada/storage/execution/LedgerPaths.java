package com.monada.storage.execution;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

/**
 * Safe identifier-to-path mapping and fail-closed containment for one ledger root. Raw identifiers are
 * never used as path segments: scopes map to {@code s-<sha256hex(UTF-8 scope id)>}. Every path that is
 * touched must resolve (following symbolic links) to a location inside the real root.
 */
final class LedgerPaths {
    static final String SCOPES_DIR = "scopes";
    static final String LOCK_FILE = "write.lock";
    static final String SCOPE_ID_FILE = "scope.id";
    static final String LEDGER_DIR = "ledger";
    static final String SEGMENT_FILE = "events-000001.log";
    static final String LEGACY_MANIFEST = "manifest.json";

    private final Path root;
    private final Path realRoot;

    private LedgerPaths(Path root, Path realRoot) {
        this.root = root;
        this.realRoot = realRoot;
    }

    /** Requires an existing root directory; never creates anything. */
    static LedgerPaths existing(Path root) throws LedgerException {
        try {
            if (!Files.isDirectory(root)) {
                throw new LedgerException("ledger root is not an existing directory: " + root);
            }
            return new LedgerPaths(root.toAbsolutePath().normalize(), root.toRealPath());
        } catch (IOException e) {
            throw new LedgerException("cannot resolve ledger root " + root + ": " + e.getMessage());
        }
    }

    Path root() {
        return root;
    }

    Path manifest() throws LedgerException {
        return contained(root.resolve(LedgerManifest.FILE_NAME));
    }

    Path lock() throws LedgerException {
        return contained(root.resolve(LOCK_FILE));
    }

    Path legacyManifest() {
        return root.resolve(LEGACY_MANIFEST);
    }

    Path scopesDir() throws LedgerException {
        return contained(root.resolve(SCOPES_DIR));
    }

    static String scopeDirectoryName(ScopeId scope) {
        return "s-" + RecordLine.sha256Hex(scope.value().getBytes(StandardCharsets.UTF_8));
    }

    Path scopeDir(ScopeId scope) throws LedgerException {
        return contained(root.resolve(SCOPES_DIR).resolve(scopeDirectoryName(scope)));
    }

    Path scopeIdFile(Path scopeDir) throws LedgerException {
        return contained(scopeDir.resolve(SCOPE_ID_FILE));
    }

    Path ledgerDir(Path scopeDir) throws LedgerException {
        return contained(scopeDir.resolve(LEDGER_DIR));
    }

    Path segment(Path scopeDir) throws LedgerException {
        return contained(scopeDir.resolve(LEDGER_DIR).resolve(SEGMENT_FILE));
    }

    /** Path of a child scope directory discovered while listing (already a direct child of scopes/). */
    Path contained(Path path) throws LedgerException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw escape(path, "path leaves the ledger root");
        }
        Path probe = normalized;
        while (probe != null && !Files.exists(probe, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            throw escape(path, "no existing ancestor");
        }
        try {
            // A dangling symlink exists with NOFOLLOW_LINKS but cannot be resolved: treat it as an escape.
            Path real = probe.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw escape(path, "resolves outside the ledger root through a symbolic link");
            }
        } catch (IOException e) {
            throw escape(path, "cannot be resolved safely: " + e.getMessage());
        }
        return normalized;
    }

    private static LedgerException escape(Path path, String why) {
        String message = "path " + path + " " + why;
        return new LedgerException(message, List.of(LedgerDiagnostic.error(LedgerDiagnosticCategory.PATH_ESCAPE,
                path.toString(), 0, OptionalLong.empty(), message)));
    }

    /** Display name of a path relative to the root, with forward slashes, for deterministic diagnostics. */
    String display(Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        return relative.toString().replace(java.io.File.separatorChar, '/');
    }

    /** Reads at most {@code max} bytes; returns empty when the file is larger (never loads it whole). */
    static java.util.Optional<byte[]> readBounded(Path file, int max) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(file.getFileName() + " is not a regular file");
        }
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(max + 1);
            return bytes.length > max ? java.util.Optional.empty() : java.util.Optional.of(bytes);
        }
    }

    /** Private staging directory used to publish a complete new scope atomically. */
    Path stagingScopeDir(ScopeId scope) throws LedgerException {
        return contained(root.resolve(SCOPES_DIR).resolve(".staging-" + scopeDirectoryName(scope)));
    }

    /** UTF-8 decoding that reports malformed input instead of substituting U+FFFD. */
    static String decodeStrict(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
