package com.monada.storage.execution;

import com.monada.core.execution.ExecutionLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ledger's own manifest, {@code execution-manifest.json}. It is independent of the legacy store
 * {@code manifest.json}. The file is a flat object with a fixed field order; a small strict parser is
 * enough, so no JSON dependency is needed.
 */
final class LedgerManifest {
    static final String FILE_NAME = "execution-manifest.json";
    static final String FORMAT = "monada-execution-ledger";
    static final String VERSION = "1";
    static final String RECORD_CODEC = "MXL1";
    static final String SCOPE_DIRECTORY_SCHEME = "sha256-hex-v1";

    private static final Pattern SHAPE = Pattern.compile(
            "\\s*\\{\\s*\"format\"\\s*:\\s*\"([^\"\\\\]*)\"\\s*,\\s*\"version\"\\s*:\\s*\"([^\"\\\\]*)\"\\s*,"
                    + "\\s*\"recordCodec\"\\s*:\\s*\"([^\"\\\\]*)\"\\s*,\\s*\"maxRecordBytes\"\\s*:\\s*(\\d{1,9})\\s*,"
                    + "\\s*\"scopeDirectoryScheme\"\\s*:\\s*\"([^\"\\\\]*)\"\\s*\\}\\s*");

    private LedgerManifest() {
    }

    static String render() {
        return "{\"format\":\"" + FORMAT + "\",\"version\":\"" + VERSION + "\",\"recordCodec\":\"" + RECORD_CODEC
                + "\",\"maxRecordBytes\":" + ExecutionLimits.MAX_RECORD_BYTES
                + ",\"scopeDirectoryScheme\":\"" + SCOPE_DIRECTORY_SCHEME + "\"}\n";
    }

    /** Validates an existing manifest file; throws with the category that describes the problem. */
    static void validate(Path manifestFile) throws LedgerException {
        String text;
        try {
            text = Files.readString(manifestFile, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, manifestFile, "unreadable manifest: " + e.getMessage());
        }
        Matcher m = SHAPE.matcher(text);
        if (!m.matches()) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, manifestFile,
                    "manifest is not a version-1 execution ledger manifest");
        }
        if (!FORMAT.equals(m.group(1))) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, manifestFile,
                    "unknown manifest format '" + m.group(1) + "'");
        }
        if (!VERSION.equals(m.group(2))) {
            throw failure(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, manifestFile,
                    "unsupported ledger version '" + m.group(2) + "' (this reader supports " + VERSION + ")");
        }
        if (!RECORD_CODEC.equals(m.group(3))) {
            throw failure(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, manifestFile,
                    "unsupported record codec '" + m.group(3) + "'");
        }
        if (!SCOPE_DIRECTORY_SCHEME.equals(m.group(5))) {
            throw failure(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, manifestFile,
                    "unsupported scope directory scheme '" + m.group(5) + "'");
        }
        if (Integer.parseInt(m.group(4)) != ExecutionLimits.MAX_RECORD_BYTES) {
            throw failure(LedgerDiagnosticCategory.MANIFEST_INVALID, manifestFile,
                    "maxRecordBytes " + m.group(4) + " differs from the v1 limit " + ExecutionLimits.MAX_RECORD_BYTES);
        }
    }

    private static LedgerException failure(LedgerDiagnosticCategory category, Path file, String message) {
        return new LedgerException(message, java.util.List.of(
                LedgerDiagnostic.error(category, file.getFileName().toString(), 0, java.util.OptionalLong.empty(), message)));
    }
}
