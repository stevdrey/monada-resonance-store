package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the seventh Codex review round of PR 107. */
class LedgerRound7Test {
    @TempDir
    Path root;

    private int valid;

    private List<LedgerDiagnosticCategory> scanWith(String extraLine) throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            for (var e : Events.fullRun()) {
                ledger.append(e);
            }
        }
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        valid = lines.size();
        lines.add(extraLine);
        LedgerFiles.writeLines(segment, lines);
        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertEquals(valid, reader.replay().size(), "the valid prefix is unchanged");
        return reader.diagnostics().stream().map(LedgerDiagnostic::category).toList();
    }

    private static String record(long sequence, long length, String digest, String payload) {
        return "MXL1\t" + sequence + "\t" + length + "\t" + digest + "\t" + payload;
    }

    @Test
    void lengthMismatchDoesNotHideDigestAndSchemaDefects() throws IOException {
        String payload = "9|FUTURE";
        List<LedgerDiagnosticCategory> found = scanWith(record(Events.fullRun().size() + 1, 99, "0".repeat(64), payload));
        assertTrue(found.contains(LedgerDiagnosticCategory.MALFORMED_RECORD), found.toString());
        assertTrue(found.contains(LedgerDiagnosticCategory.DIGEST_MISMATCH), found.toString());
        assertTrue(found.contains(LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA), found.toString());
    }

    @Test
    void lengthMismatchWithGoodDigestStillReportsSchema() throws IOException {
        String payload = "9|FUTURE";
        List<LedgerDiagnosticCategory> found = scanWith(record(Events.fullRun().size() + 1, 1,
                RecordLine.sha256Hex(payload.getBytes(StandardCharsets.UTF_8)), payload));
        assertEquals(List.of(LedgerDiagnosticCategory.MALFORMED_RECORD, LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA),
                found);
    }

    @Test
    void digestMismatchDoesNotHideSchemaDefect() throws IOException {
        String payload = "9|FUTURE";
        List<LedgerDiagnosticCategory> found = scanWith(record(Events.fullRun().size() + 1, payload.length(),
                "f".repeat(64), payload));
        assertEquals(List.of(LedgerDiagnosticCategory.DIGEST_MISMATCH, LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA),
                found);
    }
}
