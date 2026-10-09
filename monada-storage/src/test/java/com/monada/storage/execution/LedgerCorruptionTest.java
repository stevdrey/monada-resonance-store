package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerCorruptionTest {
    @TempDir
    Path root;

    @BeforeEach
    void healthyLedger() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            for (var e : Events.fullRun()) {
                ledger.append(e);
            }
        }
    }

    private LedgerDiagnostic firstError(ExecutionLedgerReader reader) {
        return reader.diagnostics().stream().filter(d -> d.severity() == LedgerDiagnostic.Severity.ERROR)
                .findFirst().orElseThrow();
    }

    private void assertWritableOpenRejects(LedgerDiagnosticCategory category) {
        LedgerException e = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.category() == category), e.diagnostics().toString());
    }

    @Test
    void interiorCorruptionIsReportedWithFileLineAndSequence() throws IOException {
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        String victim = lines.get(2);
        lines.set(2, victim.substring(0, victim.length() - 3) + "XYZ");
        LedgerFiles.writeLines(segment, lines);

        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        LedgerDiagnostic d = firstError(reader);
        assertEquals(LedgerDiagnosticCategory.DIGEST_MISMATCH, d.category());
        assertEquals(3, d.line());
        assertEquals(3, d.sequence().getAsLong());
        assertTrue(d.file().endsWith("ledger/events-000001.log"));
        assertEquals(2, reader.replay().size(), "valid prefix only; later records are not trusted");
        assertWritableOpenRejects(LedgerDiagnosticCategory.DIGEST_MISMATCH);
        assertEquals(lines, LedgerFiles.lines(segment), "no repair");
    }

    @Test
    void malformedUnknownCodecAndOversizedLinesAreCategorised() throws IOException {
        Path segment = LedgerFiles.segment(root);
        List<String> base = LedgerFiles.lines(segment);

        List<String> malformed = new ArrayList<>(base);
        malformed.set(1, "MXL1\t2\t3");
        LedgerFiles.writeLines(segment, malformed);
        assertEquals(LedgerDiagnosticCategory.MALFORMED_RECORD,
                firstError(ExecutionLedgerReader.open(root, Events.SCOPE)).category());

        List<String> codec = new ArrayList<>(base);
        codec.set(1, codec.get(1).replaceFirst("^MXL1", "MXL9"));
        LedgerFiles.writeLines(segment, codec);
        assertEquals(LedgerDiagnosticCategory.MALFORMED_RECORD,
                firstError(ExecutionLedgerReader.open(root, Events.SCOPE)).category());

        List<String> oversized = new ArrayList<>(base);
        oversized.set(1, "x".repeat(RecordLine.MAX_LINE_BYTES + 10));
        LedgerFiles.writeLines(segment, oversized);
        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertEquals(LedgerDiagnosticCategory.OVERSIZED_RECORD, firstError(reader).category());
        assertEquals(1, reader.replay().size());
        assertWritableOpenRejects(LedgerDiagnosticCategory.OVERSIZED_RECORD);
    }

    @Test
    void unknownPayloadSchemaIsReportedPerRecord() throws IOException {
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        byte[] future = "2|SOMETHING_NEW|x".getBytes(StandardCharsets.UTF_8);
        lines.set(1, new String(RecordLine.frame(2, future), StandardCharsets.UTF_8).stripTrailing());
        LedgerFiles.writeLines(segment, lines);

        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA, firstError(reader).category());
        assertEquals(2, firstError(reader).line());
        assertWritableOpenRejects(LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA);
    }

    @Test
    void sequenceGapAndDuplicateAreDetected() throws IOException {
        Path segment = LedgerFiles.segment(root);
        List<String> lines = LedgerFiles.lines(segment);

        List<String> gap = new ArrayList<>(lines);
        gap.remove(3);
        LedgerFiles.writeLines(segment, gap);
        assertEquals(LedgerDiagnosticCategory.SEQUENCE_GAP,
                firstError(ExecutionLedgerReader.open(root, Events.SCOPE)).category());

        List<String> duplicate = new ArrayList<>(lines);
        duplicate.add(2, lines.get(1));
        LedgerFiles.writeLines(segment, duplicate);
        assertEquals(LedgerDiagnosticCategory.DUPLICATE_SEQUENCE,
                firstError(ExecutionLedgerReader.open(root, Events.SCOPE)).category());
        assertWritableOpenRejects(LedgerDiagnosticCategory.DUPLICATE_SEQUENCE);
    }

    @Test
    void sameEventIdWrittenTwiceIsAConflictingDuplicate() throws IOException {
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        // re-frame line 1's payload as sequence 9 after the last record: a duplicate event id
        String payload = lines.get(0).split("\t", 5)[4];
        lines.add(new String(RecordLine.frame(lines.size() + 1, payload.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8).stripTrailing());
        LedgerFiles.writeLines(segment, lines);
        assertEquals(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                firstError(ExecutionLedgerReader.open(root, Events.SCOPE)).category());
    }

    @Test
    void danglingReferenceInTheFileIsDetected() throws IOException {
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        lines.remove(0); // drop EXECUTION_STARTED
        // renumber so only the reference problem remains
        List<String> renumbered = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String payload = lines.get(i).split("\t", 5)[4];
            renumbered.add(new String(RecordLine.frame(i + 1, payload.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8).stripTrailing());
        }
        LedgerFiles.writeLines(segment, renumbered);
        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertEquals(LedgerDiagnosticCategory.DANGLING_REFERENCE, firstError(reader).category());
        assertEquals(0, reader.replay().size());
    }

    @Test
    void unknownManifestVersionIsRejectedWithoutUpgrade() throws IOException {
        Path manifest = root.resolve("execution-manifest.json");
        String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace("\"version\":\"1\"", "\"version\":\"2\""));
        LedgerException read = assertThrows(LedgerException.class, () -> ExecutionLedgerReader.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, read.diagnostics().get(0).category());
        LedgerException write = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, write.diagnostics().get(0).category());
        assertEquals(original.replace("\"version\":\"1\"", "\"version\":\"2\""), Files.readString(manifest));

        Files.writeString(manifest, "not json");
        assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        Files.delete(manifest);
        assertEquals(LedgerDiagnosticCategory.MANIFEST_MISSING, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
    }

    @Test
    void scopeIdFileMustMatch() throws IOException {
        Path scopeId = LedgerFiles.segment(root).getParent().getParent().resolve("scope.id");
        Files.writeString(scopeId, "someone-else");
        assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, assertThrows(LedgerException.class,
                () -> ExecutionLedger.open(root, Events.SCOPE)).diagnostics().get(0).category());
    }

    @Test
    void scopeIdentifiersAreNeverPathSegments() throws IOException {
        ScopeId hostile = ScopeId.of("../../escape/..\\x");
        try (ExecutionLedger ledger = ExecutionLedger.open(root, hostile)) {
            ledger.append(Events.started("e1", hostile, Events.EXEC, "hostile scope"));
        }
        try (var walk = Files.walk(root)) {
            assertTrue(walk.noneMatch(p -> p.getFileName().toString().contains("escape")));
        }
        assertTrue(Files.exists(root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(hostile))));
        assertEquals(1, ExecutionLedgerReader.open(root, hostile).replay().size());
    }

    @Test
    void symlinkEscapesAreRejectedFailClosed(@TempDir Path outside) throws IOException {
        Path scopeDir = LedgerFiles.segment(root).getParent().getParent();
        Path elsewhere = outside.resolve("stolen");
        Files.createDirectories(elsewhere.resolve("ledger"));
        Files.move(scopeDir.resolve("ledger").resolve("events-000001.log"), elsewhere.resolve("ledger")
                .resolve("events-000001.log"));
        Files.move(scopeDir.resolve("scope.id"), elsewhere.resolve("scope.id"));
        Files.delete(scopeDir.resolve("ledger"));
        Files.delete(scopeDir);
        try {
            Files.createSymbolicLink(scopeDir, elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available: " + e);
        }
        Map<String, String> outsideBefore = LedgerFiles.digests(outside);
        Map<String, String> rootBefore = LedgerFiles.digests(root);

        assertEquals(LedgerDiagnosticCategory.PATH_ESCAPE, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        assertEquals(LedgerDiagnosticCategory.PATH_ESCAPE, assertThrows(LedgerException.class,
                () -> ExecutionLedger.open(root, Events.SCOPE)).diagnostics().get(0).category());
        assertEquals(outsideBefore, LedgerFiles.digests(outside));
        assertEquals(rootBefore, LedgerFiles.digests(root), "the failed opens changed nothing inside the root");
    }

    @Test
    void symlinkedLockFileCannotEscape(@TempDir Path outside) throws IOException {
        Path other = root.resolve("fresh");
        Files.createDirectories(other);
        try {
            Files.createSymbolicLink(other.resolve("write.lock"), outside.resolve("target.lock"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available: " + e);
        }
        assertThrows(LedgerException.class, () -> ExecutionLedger.open(other, Events.SCOPE));
        assertFalse(Files.exists(outside.resolve("target.lock")));
    }
}
