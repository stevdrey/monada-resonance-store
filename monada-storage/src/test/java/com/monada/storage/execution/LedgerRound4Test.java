package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.monada.storage.execution.audit.ExecutionLedgerAuditor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the fourth Codex review round of PR 107. */
class LedgerRound4Test {
    @TempDir
    Path root;

    private void healthy() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            for (var e : Events.fullRun()) {
                ledger.append(e);
            }
        }
    }

    private static String line(long sequence, String payload) {
        return new String(RecordLine.frame(sequence, payload.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8).stripTrailing();
    }

    @Test
    void scopesThatIsNotADirectoryIsDamageNotAnEmptyLedger() throws IOException {
        healthy();
        Path scopes = root.resolve("scopes");
        Path moved = root.resolve("scopes-moved");
        Files.move(scopes, moved);
        Files.writeString(scopes, "not a directory");
        for (int i = 0; i < 2; i++) {
            LedgerException read = assertThrows(LedgerException.class,
                    () -> ExecutionLedgerReader.open(root, Events.SCOPE));
            assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, read.diagnostics().get(0).category());
            assertThrows(IOException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
            assertFalse(new ExecutionLedgerAuditor().audit(root).isHealthy());
            if (i == 0) {
                // an in-root symbolic link to a regular file is the same damage
                Files.delete(scopes);
                try {
                    Files.createSymbolicLink(scopes, root.resolve("execution-manifest.json"));
                } catch (UnsupportedOperationException | IOException e) {
                    assumeTrue(false, "symbolic links are not available: " + e);
                }
            }
        }
    }

    @Test
    void absentScopesDirectoryIsStillAnEmptyLedger() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.close();
        }
        deleteScopes();
        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertTrue(reader.isHealthy());
        assertEquals(0, reader.replay().size());
    }

    private void deleteScopes() throws IOException {
        try (var walk = Files.walk(root.resolve("scopes"))) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void readerDiagnosticsCannotBeClearedByCallers() throws IOException {
        healthy();
        Files.write(LedgerFiles.segment(root), "MXL1\t99".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertFalse(reader.isHealthy());
        assertThrows(UnsupportedOperationException.class, () -> reader.diagnostics().clear());
        assertThrows(UnsupportedOperationException.class, () -> reader.diagnostics().add(reader.diagnostics().get(0)));
        assertFalse(reader.isHealthy());
        assertEquals(1, reader.diagnostics().size());
    }

    @Test
    void sequenceErrorsDoNotHideIndependentDefectsOnTheSameLine() throws IOException {
        healthy();
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        int valid = lines.size();

        // gap + corrupt digest: sequence jumps to 50 and the digest field is wrong
        String gapAndDigest = line(50, "1|X").replaceFirst("\t[0-9a-f]{64}\t", "\t" + "0".repeat(64) + "\t");
        // duplicate sequence + unknown schema
        String duplicateAndSchema = line(3, "9|FUTURE");
        lines.add(gapAndDigest);
        lines.add(duplicateAndSchema);
        LedgerFiles.writeLines(segment, lines);

        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        List<LedgerDiagnosticCategory> categories = reader.diagnostics().stream().map(LedgerDiagnostic::category).toList();
        assertTrue(categories.contains(LedgerDiagnosticCategory.SEQUENCE_GAP), categories.toString());
        assertTrue(categories.contains(LedgerDiagnosticCategory.DIGEST_MISMATCH), categories.toString());
        assertTrue(categories.contains(LedgerDiagnosticCategory.DUPLICATE_SEQUENCE), categories.toString());
        assertTrue(categories.contains(LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA), categories.toString());
        assertEquals(valid, reader.replay().size(), "the valid prefix is unchanged");
    }
}
