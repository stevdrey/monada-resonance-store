package com.monada.storage.audit;

import com.monada.core.AtomType;
import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.FileManifestStore;
import com.monada.storage.Manifest;
import com.monada.storage.VectorFormatProfile;
import com.monada.storage.feedback.FeedbackEvent;
import com.monada.storage.feedback.FeedbackSignal;
import com.monada.storage.feedback.FileFeedbackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageIntegrityAuditorTest {

    @TempDir
    Path root;

    private final StorageIntegrityAuditor auditor = new StorageIntegrityAuditor();

    private void createCleanStore(Path dir, int dimensions) throws IOException {
        var manifestStore = new FileManifestStore(dir);
        var vectorProfile = VectorFormatProfile.currentFixedRaw(dimensions);
        var manifest = new Manifest(
                "0.4",
                dimensions,
                "vectors/segment-000001.f32",
                "atoms/segment-000001.log",
                "feedback/feedback-000001.log",
                null,
                vectorProfile
        );
        manifestStore.save(manifest);

        var atomStore = new FileAtomStore(dir);
        var freqStore = FileFrequencyStore.create(dir, "vectors/segment-000001.f32", vectorProfile);
        var feedbackStore = new FileFeedbackStore(dir);

        var atom1 = new KnowledgeAtom("a-1", AtomType.TEXT, "first atom content", List.of("alias-1"), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        var atom2 = new KnowledgeAtom("a-2", AtomType.TEXT, "second atom content", List.of(), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));

        float[] v1 = new float[dimensions];
        v1[0] = 1.0f;
        float[] v2 = new float[dimensions];
        v2[1] = 2.0f;

        freqStore.save("a-1", new FrequencyVector(v1));
        atomStore.save(atom1);

        freqStore.save("a-2", new FrequencyVector(v2));
        atomStore.save(atom2);

        feedbackStore.append(new FeedbackEvent("test query", "test query", "a-1", FeedbackSignal.POSITIVE, 0.1, Instant.parse("2024-01-01T00:00:00Z")));
    }

    @Test
    void cleanStoreReportsNoErrorsOrFatalFindings() throws IOException {
        createCleanStore(root, 4);

        StorageIntegrityReport report = auditor.audit(root);

        assertTrue(report.isHealthy());
        assertFalse(report.hasErrorsOrFatal());
        assertEquals(0, report.countBySeverity(StorageIntegritySeverity.FATAL));
        assertEquals(0, report.countBySeverity(StorageIntegritySeverity.ERROR));
        assertEquals(0, report.countBySeverity(StorageIntegritySeverity.WARNING));
        assertEquals(2, report.statistics().activeUniqueAtoms());
        assertEquals(2, report.statistics().vectorIndexEntries());
        assertEquals(2, report.statistics().activeAtomsWithVector());
        assertEquals(0, report.statistics().activeAtomsWithoutVector());
        assertEquals(0, report.statistics().vectorEntriesWithoutActiveAtom());
        assertEquals(1, report.statistics().feedbackLogRecords());

        String rendered = report.render();
        assertTrue(rendered.contains("Status:           HEALTHY"));
        assertTrue(rendered.contains("Atom Physical Records:             2"));
    }

    @Test
    void auditIsProvablySideEffectFree() throws IOException {
        createCleanStore(root, 4);

        Path manifest = root.resolve("manifest.json");
        Path atomLog = root.resolve("atoms/segment-000001.log");
        Path vectorFile = root.resolve("vectors/segment-000001.f32");
        Path vectorMap = root.resolve("indexes/vector-map.idx");
        Path feedbackLog = root.resolve("feedback/feedback-000001.log");

        long manifestSizeBefore = Files.size(manifest);
        byte[] atomLogBytesBefore = Files.readAllBytes(atomLog);
        byte[] vectorFileBytesBefore = Files.readAllBytes(vectorFile);
        byte[] vectorMapBytesBefore = Files.readAllBytes(vectorMap);
        byte[] feedbackBytesBefore = Files.readAllBytes(feedbackLog);

        FileTime manifestMtimeBefore = Files.getLastModifiedTime(manifest);
        FileTime atomLogMtimeBefore = Files.getLastModifiedTime(atomLog);
        FileTime vectorFileMtimeBefore = Files.getLastModifiedTime(vectorFile);
        FileTime vectorMapMtimeBefore = Files.getLastModifiedTime(vectorMap);
        FileTime feedbackMtimeBefore = Files.getLastModifiedTime(feedbackLog);

        // Run the audit multiple times
        StorageIntegrityReport report1 = auditor.audit(root);
        StorageIntegrityReport report2 = auditor.audit(root);

        assertTrue(report1.isHealthy());
        assertTrue(report2.isHealthy());

        assertEquals(manifestSizeBefore, Files.size(manifest));
        assertArrayEquals(atomLogBytesBefore, Files.readAllBytes(atomLog));
        assertArrayEquals(vectorFileBytesBefore, Files.readAllBytes(vectorFile));
        assertArrayEquals(vectorMapBytesBefore, Files.readAllBytes(vectorMap));
        assertArrayEquals(feedbackBytesBefore, Files.readAllBytes(feedbackLog));

        assertEquals(manifestMtimeBefore, Files.getLastModifiedTime(manifest));
        assertEquals(atomLogMtimeBefore, Files.getLastModifiedTime(atomLog));
        assertEquals(vectorFileMtimeBefore, Files.getLastModifiedTime(vectorFile));
        assertEquals(vectorMapMtimeBefore, Files.getLastModifiedTime(vectorMap));
        assertEquals(feedbackMtimeBefore, Files.getLastModifiedTime(feedbackLog));
    }

    @Test
    void nonExistentStoreRootReportsFatalSegmentMissing() {
        Path missingDir = root.resolve("does-not-exist");

        StorageIntegrityReport report = auditor.audit(missingDir);

        assertFalse(report.isHealthy());
        assertEquals(1, report.countBySeverity(StorageIntegritySeverity.FATAL));
        assertEquals(StorageIntegrityCategory.SEGMENT_MISSING, report.findings().getFirst().category());
    }

    @Test
    void missingManifestReportsFatalManifestInvalid() throws IOException {
        Files.createDirectories(root.resolve("atoms"));
        Files.createDirectories(root.resolve("vectors"));
        Files.createDirectories(root.resolve("indexes"));
        Files.createFile(root.resolve("atoms/segment-000001.log"));
        Files.createFile(root.resolve("vectors/segment-000001.f32"));
        Files.createFile(root.resolve("indexes/vector-map.idx"));

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.FATAL && f.category() == StorageIntegrityCategory.MANIFEST_INVALID));
    }

    @Test
    void malformedManifestJsonReportsFatalManifestInvalid() throws IOException {
        Files.writeString(root.resolve("manifest.json"), "{ invalid-json", StandardCharsets.UTF_8);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.FATAL && f.category() == StorageIntegrityCategory.MANIFEST_INVALID));
    }

    @Test
    void missingReferencedSegmentsReportsFatalSegmentMissing() throws IOException {
        var manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.4",
                4,
                "vectors/segment-000001.f32",
                "atoms/segment-000001.log",
                "feedback/feedback-000001.log",
                null,
                VectorFormatProfile.currentFixedRaw(4)
        ));

        // Delete the created files so they are truly missing
        Files.deleteIfExists(root.resolve("atoms/segment-000001.log"));
        Files.deleteIfExists(root.resolve("vectors/segment-000001.f32"));
        Files.deleteIfExists(root.resolve("indexes/vector-map.idx"));

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        long missingSegments = report.findings().stream()
                .filter(f -> f.severity() == StorageIntegritySeverity.FATAL && f.category() == StorageIntegrityCategory.SEGMENT_MISSING)
                .count();
        assertEquals(3, missingSegments);
    }

    @Test
    void malformedAtomLogEntryReportsErrorWithoutCrashingAudit() throws IOException {
        createCleanStore(root, 4);

        Path atomLog = root.resolve("atoms/segment-000001.log");
        Files.writeString(atomLog, "bad-line-without-tabs\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.ATOM_LOG_CORRUPT));
        assertEquals(3, report.statistics().atomLogPhysicalRecords());
        assertEquals(2, report.statistics().activeUniqueAtoms());
        assertEquals(0, report.statistics().atomHistoryDuplicates());
        assertFalse(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.INFO && f.message().contains("historical update records")));
    }

    @Test
    void corruptBase64InAtomLogReportsError() throws IOException {
        createCleanStore(root, 4);

        Path atomLog = root.resolve("atoms/segment-000001.log");
        Files.writeString(atomLog, "a-bad\tTEXT\t1.0\t2024-01-01T00:00:00Z\tNOT_BASE_64!@#\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.ATOM_LOG_CORRUPT
                        && f.message().contains("Corrupt Base64")));
    }

    @Test
    void repeatedAtomIdHistoryIsRecognizedAsValidHistoryAndNotMislabeledAsCorruption() throws IOException {
        createCleanStore(root, 4);

        FileAtomStore atomStore = new FileAtomStore(root);
        // Append updated atom with new alias
        var updated = new KnowledgeAtom("a-1", AtomType.TEXT, "first atom content", List.of("alias-1", "alias-2"), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        atomStore.save(updated);

        // Also append vector for updated atom
        FileFrequencyStore freqStore = FileFrequencyStore.openExisting(root, "vectors/segment-000001.f32", VectorFormatProfile.currentFixedRaw(4));
        freqStore.save("a-1", new FrequencyVector(new float[]{1.0f, 1.0f, 1.0f, 1.0f}));

        StorageIntegrityReport report = auditor.audit(root);

        // Valid last-wins history is not an error or fatal
        assertEquals(0, report.countBySeverity(StorageIntegritySeverity.FATAL));
        assertEquals(0, report.countBySeverity(StorageIntegritySeverity.ERROR));
        assertEquals(3, report.statistics().atomLogPhysicalRecords());
        assertEquals(2, report.statistics().activeUniqueAtoms());
        assertEquals(1, report.statistics().atomHistoryDuplicates());

        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.INFO && f.category() == StorageIntegrityCategory.ATOM_LOG_CORRUPT
                        && f.message().contains("historical update records")));
    }

    @Test
    void malformedVectorMapReportsError() throws IOException {
        createCleanStore(root, 4);

        Path vectorMap = root.resolve("indexes/vector-map.idx");
        Files.writeString(vectorMap, "corrupt-entry-no-tab\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.VECTOR_INDEX_MALFORMED));
    }

    @Test
    void outOfBoundsOffsetReportsError() throws IOException {
        createCleanStore(root, 4);

        Path vectorMap = root.resolve("indexes/vector-map.idx");
        Files.writeString(vectorMap, "a-99\t999999\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.VECTOR_OFFSET_OUT_OF_BOUNDS));
    }

    @Test
    void misalignedOffsetReportsError() throws IOException {
        createCleanStore(root, 4); // frameSize = 16 bytes

        Path vectorMap = root.resolve("indexes/vector-map.idx");
        // Overwrite second entry with misaligned offset 5
        Files.writeString(vectorMap, "a-1\t0\na-2\t5\n", StandardCharsets.UTF_8);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.VECTOR_OFFSET_MISALIGNED));
    }

    @Test
    void duplicateVectorOffsetReportsError() throws IOException {
        createCleanStore(root, 4);

        Path vectorMap = root.resolve("indexes/vector-map.idx");
        // Both point to offset 0
        Files.writeString(vectorMap, "a-1\t0\na-2\t0\n", StandardCharsets.UTF_8);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.DUPLICATE_VECTOR_OFFSET));
        assertEquals(1, report.statistics().duplicateVectorOffsets());
    }

    @Test
    void truncatedVectorSegmentProducesActionableFinding() throws IOException {
        createCleanStore(root, 4);

        Path vectorFile = root.resolve("vectors/segment-000001.f32");
        long size = Files.size(vectorFile);
        // Truncate the file so the last vector is incomplete
        try (var raf = new RandomAccessFile(vectorFile.toFile(), "rw")) {
            raf.setLength(size - 4);
        }

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.VECTOR_SEGMENT_SIZE_MISMATCH));
    }

    @Test
    void atomWithoutVectorReportsError() throws IOException {
        createCleanStore(root, 4);

        // Add atom without saving corresponding vector
        FileAtomStore atomStore = new FileAtomStore(root);
        atomStore.save(new KnowledgeAtom("a-orphan-atom", AtomType.TEXT, "no vector here", Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z")));

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.ATOM_WITHOUT_VECTOR
                        && f.message().contains("a-orphan-atom")));
        assertEquals(1, report.statistics().activeAtomsWithoutVector());
    }

    @Test
    void vectorWithoutAtomReportsWarning() throws IOException {
        createCleanStore(root, 4);

        // Add vector to index and segment without atom in atom log
        Path vectorMap = root.resolve("indexes/vector-map.idx");
        Path vectorFile = root.resolve("vectors/segment-000001.f32");
        long currentSize = Files.size(vectorFile);

        // Append 4 floats (16 bytes)
        try (var raf = new RandomAccessFile(vectorFile.toFile(), "rw")) {
            raf.seek(currentSize);
            raf.writeFloat(1f);
            raf.writeFloat(2f);
            raf.writeFloat(3f);
            raf.writeFloat(4f);
        }
        Files.writeString(vectorMap, "orphan-vector-id\t" + currentSize + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        StorageIntegrityReport report = auditor.audit(root);

        // Warning severity for orphan vector
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.WARNING && f.category() == StorageIntegrityCategory.VECTOR_WITHOUT_ATOM
                        && f.message().contains("orphan-vector-id")));
        assertEquals(1, report.statistics().vectorEntriesWithoutActiveAtom());
    }

    @Test
    void legacyManifestsClassifiedWithInfoFinding() throws IOException {
        var manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest("0.3", 4, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        var atomStore = new FileAtomStore(root);
        atomStore.save(new KnowledgeAtom("a-1", AtomType.TEXT, "legacy", Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z")));

        var freqStore = FileFrequencyStore.create(root, "vectors/segment-000001.f32", VectorFormatProfile.currentFixedRaw(4));
        freqStore.save("a-1", new FrequencyVector(new float[]{1f, 2f, 3f, 4f}));

        StorageIntegrityReport report = auditor.audit(root);

        assertTrue(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.INFO && f.category() == StorageIntegrityCategory.FORMAT_INCOMPATIBLE
                        && f.message().contains("legacy 0.3")));
    }

    @Test
    void legacyManifestWithIllegalPhysicalFieldsReportsError() throws IOException {
        Files.createDirectories(root.resolve("atoms"));
        Files.createDirectories(root.resolve("vectors"));
        Files.createDirectories(root.resolve("indexes"));
        Files.createFile(root.resolve("atoms/segment-000001.log"));
        Files.createFile(root.resolve("vectors/segment-000001.f32"));
        Files.createFile(root.resolve("indexes/vector-map.idx"));

        Files.writeString(root.resolve("manifest.json"), """
                {
                  "version": "0.3",
                  "dimensions": 4,
                  "vectorSegment": "vectors/segment-000001.f32",
                  "atomSegment": "atoms/segment-000001.log",
                  "vectorFormatVersion": 1
                }
                """, StandardCharsets.UTF_8);

        StorageIntegrityReport report = auditor.audit(root);

        assertFalse(report.isHealthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR && f.category() == StorageIntegrityCategory.MANIFEST_INVALID
                        && f.message().contains("must not declare physical vector format metadata")));
    }

    @Test
    void deterministicOrderingOfFindings() {
        var finding1 = new StorageIntegrityFinding(StorageIntegritySeverity.INFO, StorageIntegrityCategory.FORMAT_INCOMPATIBLE, "target-z", "msg 1");
        var finding2 = new StorageIntegrityFinding(StorageIntegritySeverity.FATAL, StorageIntegrityCategory.MANIFEST_INVALID, "target-a", "msg 2");
        var finding3 = new StorageIntegrityFinding(StorageIntegritySeverity.ERROR, StorageIntegrityCategory.ATOM_LOG_CORRUPT, "target-b", "msg 3");
        var finding4 = new StorageIntegrityFinding(StorageIntegritySeverity.WARNING, StorageIntegrityCategory.VECTOR_WITHOUT_ATOM, "target-c", "msg 4");

        var report = new StorageIntegrityReport(root, null, List.of(finding1, finding2, finding3, finding4), StorageIntegrityStatistics.empty());

        assertEquals(StorageIntegritySeverity.FATAL, report.findings().get(0).severity());
        assertEquals(StorageIntegritySeverity.ERROR, report.findings().get(1).severity());
        assertEquals(StorageIntegritySeverity.WARNING, report.findings().get(2).severity());
        assertEquals(StorageIntegritySeverity.INFO, report.findings().get(3).severity());
    }
}
