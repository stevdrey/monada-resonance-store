package com.monada.speech.importer;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.storage.FileSpeechSampleStore;
import com.monada.speech.storage.SpeechSampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for {@link TorgoDatasetImporter#auditFrom}.
 *
 * <p>All fixtures are generated programmatically (no real TORGO dataset required).
 */
class TorgoImportAuditTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // WAV fixture helpers (inlined to avoid cross-package dependency)
    // -------------------------------------------------------------------------

    private static byte[] makeSineWav(int sampleRate, int durationMs, int channels, int bitsPerSample)
            throws IOException {
        int numSamples = (int) ((sampleRate * durationMs) / 1000.0);
        byte[] pcmData;
        if (bitsPerSample == 16) {
            pcmData = new byte[numSamples * channels * 2];
            ByteBuffer buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < numSamples; i++) {
                double t = i / (double) sampleRate;
                short v = (short) (Math.sin(2 * Math.PI * 440.0 * t) * 32767);
                for (int ch = 0; ch < channels; ch++) buf.putShort(v);
            }
        } else {
            pcmData = new byte[numSamples * channels];
            for (int i = 0; i < numSamples; i++) {
                double t = i / (double) sampleRate;
                byte v = (byte) ((Math.sin(2 * Math.PI * 440.0 * t) + 1.0) * 127.5);
                for (int ch = 0; ch < channels; ch++) pcmData[i * channels + ch] = v;
            }
        }
        return buildWavBytes(sampleRate, channels, bitsPerSample, pcmData);
    }

    private static byte[] buildWavBytes(int sampleRate, int channels, int bitsPerSample, byte[] pcm)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + pcm.length);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, (short) 1);
        writeShortLE(out, (short) channels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, (short) blockAlign);
        writeShortLE(out, (short) bitsPerSample);
        writeAscii(out, "data");
        writeIntLE(out, pcm.length);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF); out.write((v >> 24) & 0xFF);
    }
    private static void writeShortLE(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
    }

    private Path writeWav(Path dir, String name, byte[] wavBytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, wavBytes);
        return p;
    }
    private void writeTxt(Path dir, String stem, String text) throws IOException {
        Files.writeString(dir.resolve(stem + ".txt"), text, StandardCharsets.UTF_8);
    }

    private SpeechSampleStore store(Path root) throws IOException {
        return new FileSpeechSampleStore(root);
    }

    // -------------------------------------------------------------------------
    // Dry-run mode
    // -------------------------------------------------------------------------

    @Test
    void dryRunProducesZeroImportedAndDryRunFlag() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 300, 1, 16);
        writeWav(dir, "hello.wav", wav);
        writeTxt(dir, "hello", "hello");

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.dryRun());
        assertEquals(1, report.discoveredAudioFiles());
        assertEquals(0, report.importedSamples());
        assertEquals(0, report.skippedSamples());
    }

    @Test
    void dryRunPopulatesBySpeakerAndByCondition() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dysDir = dataset.resolve("M01/Session1/words");
        Path ctrlDir = dataset.resolve("FC1/Session1/words");
        Files.createDirectories(dysDir);
        Files.createDirectories(ctrlDir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dysDir, "a.wav", wav);
        writeTxt(dysDir, "a", "apple");
        writeWav(ctrlDir, "b.wav", wav);
        writeTxt(ctrlDir, "b", "banana");

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.dryRun());
        assertEquals(2, report.discoveredAudioFiles());
        assertEquals(0, report.importedSamples());

        assertEquals(1, report.bySpeaker().getOrDefault("M01", 0));
        assertEquals(1, report.bySpeaker().getOrDefault("FC1", 0));
        assertEquals(1, report.byCondition().getOrDefault(SpeechCondition.DYSARTHRIC, 0));
        assertEquals(1, report.byCondition().getOrDefault(SpeechCondition.CONTROL, 0));
    }

    @Test
    void dryRunDoesNotWriteToStore() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/sentences");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dir, "x.wav", wav);
        writeTxt(dir, "x", "text");

        int filesBefore = countFiles(dataset);

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.dryRun(), "report must indicate dry-run");
        assertEquals(0, report.importedSamples(), "dry-run must not report any imported samples");
        assertEquals(filesBefore, countFiles(dataset),
                "dry-run must not create or modify any files under the dataset root");
    }

    // -------------------------------------------------------------------------
    // Warning categorisation
    // -------------------------------------------------------------------------

    @Test
    void missingTranscriptReportedAsMissingTranscriptCategory() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        writeWav(dir, "notxt.wav", makeSineWav(16000, 200, 1, 16));

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.warningGroups().containsKey(WarningCategory.MISSING_TRANSCRIPT));
        TorgoAuditWarningGroup group = report.warningGroups().get(WarningCategory.MISSING_TRANSCRIPT);
        assertEquals(1, group.count());
        assertEquals(1, group.pathExamples().size());
    }

    @Test
    void blankTranscriptReportedAsBlankTranscriptCategory() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        writeWav(dir, "blank.wav", makeSineWav(16000, 200, 1, 16));
        writeTxt(dir, "blank", "   ");

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.warningGroups().containsKey(WarningCategory.BLANK_TRANSCRIPT));
        assertEquals(1, report.warningGroups().get(WarningCategory.BLANK_TRANSCRIPT).count());
    }

    @Test
    void unsupportedLayoutReportedAsUnsupportedLayoutCategory() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Files.createDirectories(dataset);
        writeWav(dataset, "rootfile.wav", makeSineWav(16000, 200, 1, 16));
        writeTxt(dataset, "rootfile", "text at root");

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.warningGroups().containsKey(WarningCategory.UNSUPPORTED_LAYOUT));
        assertEquals(1, report.warningGroups().get(WarningCategory.UNSUPPORTED_LAYOUT).count());
    }

    @Test
    void duplicateIdReportedAsDuplicateIdCategory() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dir, "Hello.wav", wav);
        writeTxt(dir, "Hello", "hello upper");
        writeWav(dir, "hello.wav", wav);
        writeTxt(dir, "hello", "hello lower");

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.warningGroups().containsKey(WarningCategory.DUPLICATE_ID));
        assertEquals(1, report.warningGroups().get(WarningCategory.DUPLICATE_ID).count());
    }

    @Test
    void multipleDistinctCategoriesAreEachReported() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dir, "good.wav", wav);
        writeTxt(dir, "good", "good word");
        writeWav(dir, "notxt.wav", wav);
        writeTxt(dir, "blank", "   ");
        writeWav(dir, "blank.wav", wav);

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertTrue(report.warningGroups().containsKey(WarningCategory.MISSING_TRANSCRIPT));
        assertTrue(report.warningGroups().containsKey(WarningCategory.BLANK_TRANSCRIPT));
        assertEquals(2, report.warningCategoryCount());
    }

    // -------------------------------------------------------------------------
    // Path examples cap
    // -------------------------------------------------------------------------

    @Test
    void pathExamplesAreCappedAtFiveForLargeWarningGroup() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 100, 1, 16);
        for (int i = 1; i <= 8; i++) {
            writeWav(dir, "word" + i + ".wav", wav);
        }

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        TorgoAuditWarningGroup group = report.warningGroups().get(WarningCategory.MISSING_TRANSCRIPT);
        assertNotNull(group);
        assertEquals(8, group.count());
        assertEquals(TorgoAuditWarningGroup.MAX_EXAMPLES, group.pathExamples().size());
    }

    @Test
    void pathExamplesAreSubsetOfActualPaths() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 100, 1, 16);
        for (int i = 1; i <= 7; i++) {
            writeWav(dir, "file" + i + ".wav", wav);
        }

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        TorgoAuditWarningGroup group = report.warningGroups().get(WarningCategory.MISSING_TRANSCRIPT);
        assertNotNull(group);
        for (Path example : group.pathExamples()) {
            assertTrue(example.toString().endsWith(".wav"),
                    "example path should be a WAV path: " + example);
        }
    }

    // -------------------------------------------------------------------------
    // Grouped counts
    // -------------------------------------------------------------------------

    @Test
    void byTaskTypeCountsAreCorrect() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path wordsDir = dataset.resolve("M01/Session1/words");
        Path sentDir = dataset.resolve("M01/Session1/sentences");
        Files.createDirectories(wordsDir);
        Files.createDirectories(sentDir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(wordsDir, "w1.wav", wav); writeTxt(wordsDir, "w1", "cat");
        writeWav(wordsDir, "w2.wav", wav); writeTxt(wordsDir, "w2", "dog");
        writeWav(sentDir, "s1.wav", wav);  writeTxt(sentDir, "s1", "the cat sat");

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertEquals(2, report.byTaskType().getOrDefault(SpeechTaskType.WORD, 0));
        assertEquals(1, report.byTaskType().getOrDefault(SpeechTaskType.SENTENCE, 0));
    }

    @Test
    void byLanguageCountsAreCorrect() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dir, "w1.wav", wav); writeTxt(dir, "w1", "apple");
        writeWav(dir, "w2.wav", wav); writeTxt(dir, "w2", "banana");

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertEquals(2, report.byLanguage().getOrDefault("en-US", 0));
    }

    @Test
    void multipleSpeakersGroupedCorrectly() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        for (String speaker : List.of("M01", "M02", "FC1")) {
            Path dir = dataset.resolve(speaker + "/Session1/words");
            Files.createDirectories(dir);
            writeWav(dir, "item.wav", wav);
            writeTxt(dir, "item", "word for " + speaker);
        }

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertEquals(3, report.bySpeaker().size());
        assertEquals(1, report.bySpeaker().get("M01"));
        assertEquals(1, report.bySpeaker().get("M02"));
        assertEquals(1, report.bySpeaker().get("FC1"));
    }

    // -------------------------------------------------------------------------
    // Import mode (store != null)
    // -------------------------------------------------------------------------

    @Test
    void importModeWritesSamplesAndSetsImportedCount() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 300, 1, 16);
        writeWav(dir, "hello.wav", wav);
        writeTxt(dir, "hello", "hello");

        SpeechSampleStore store = store(tempDir.resolve("storeAudit1"));
        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, store);

        assertFalse(report.dryRun());
        assertEquals(1, report.importedSamples());
        assertEquals(1, store.findAll().size());
    }

    @Test
    void importModeGroupedMapsMatchWrittenSamples() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dysDir = dataset.resolve("M01/Session1/sentences");
        Path ctrlDir = dataset.resolve("FC1/Session1/words");
        Files.createDirectories(dysDir);
        Files.createDirectories(ctrlDir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dysDir, "s1.wav", wav); writeTxt(dysDir, "s1", "sentence one");
        writeWav(ctrlDir, "w1.wav", wav); writeTxt(ctrlDir, "w1", "word one");

        SpeechSampleStore store = store(tempDir.resolve("storeAudit2"));
        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, store);

        assertEquals(2, report.importedSamples());
        assertEquals(1, report.byCondition().getOrDefault(SpeechCondition.DYSARTHRIC, 0));
        assertEquals(1, report.byCondition().getOrDefault(SpeechCondition.CONTROL, 0));
        assertEquals(1, report.byTaskType().getOrDefault(SpeechTaskType.SENTENCE, 0));
        assertEquals(1, report.byTaskType().getOrDefault(SpeechTaskType.WORD, 0));
    }

    @Test
    void importModeSkippedFilesStillProduceWarningGroups() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dir, "good.wav", wav); writeTxt(dir, "good", "good");
        writeWav(dir, "notxt.wav", wav);

        SpeechSampleStore store = store(tempDir.resolve("storeAudit3"));
        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, store);

        assertEquals(1, report.importedSamples());
        assertEquals(1, report.skippedSamples());
        assertTrue(report.warningGroups().containsKey(WarningCategory.MISSING_TRANSCRIPT));
    }

    // -------------------------------------------------------------------------
    // Backward compatibility: importFrom() delegation
    // -------------------------------------------------------------------------

    @Test
    void importFromDelegatesToAuditFromAndPreservesExistingBehavior() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        byte[] wav = makeSineWav(16000, 200, 1, 16);
        writeWav(dir, "good.wav", wav); writeTxt(dir, "good", "good");
        writeWav(dir, "notxt.wav", wav);

        SpeechSampleStore store = store(tempDir.resolve("storeCompat"));
        TorgoDatasetImportReport report = new TorgoDatasetImporter().importFrom(dataset, store);

        assertEquals(2, report.discoveredAudioFiles());
        assertEquals(1, report.importedSamples());
        assertEquals(1, report.skippedSamples());
        assertEquals(1, report.warnings().size());
        assertEquals("missing transcript", report.warnings().get(0).reason());
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void emptyDatasetProducesEmptyReport() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Files.createDirectories(dataset);

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        assertEquals(0, report.discoveredAudioFiles());
        assertEquals(0, report.importedSamples());
        assertEquals(0, report.skippedSamples());
        assertTrue(report.bySpeaker().isEmpty());
        assertTrue(report.warningGroups().isEmpty());
    }

    @Test
    void warningGroupIsImmutable() throws IOException {
        Path dataset = tempDir.resolve("torgo");
        Path dir = dataset.resolve("M01/Session1/words");
        Files.createDirectories(dir);
        writeWav(dir, "notxt.wav", makeSineWav(16000, 200, 1, 16));

        TorgoImportAuditReport report = new TorgoDatasetImporter().auditFrom(dataset, null);

        TorgoAuditWarningGroup group = report.warningGroups().get(WarningCategory.MISSING_TRANSCRIPT);
        assertNotNull(group);
        assertThrows(UnsupportedOperationException.class,
                () -> group.pathExamples().add(Path.of("/fake")));
    }

    @Test
    void rejectsImportedPlusSkippedExceedingDiscoveredInNonDryRun() {
        assertThrows(IllegalArgumentException.class, () -> new TorgoImportAuditReport(
                1, 1, 1, false,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void rejectsImportedPlusSkippedExceedingDiscoveredInDryRun() {
        assertThrows(IllegalArgumentException.class, () -> new TorgoImportAuditReport(
                1, 0, 2, true,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void rejectsNonZeroImportedInDryRun() {
        assertThrows(IllegalArgumentException.class, () -> new TorgoImportAuditReport(
                5, 1, 0, true,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void allowsDryRunWithZeroImportedAndSkippedEqualToDiscovered() {
        assertDoesNotThrow(() -> new TorgoImportAuditReport(
                5, 0, 5, true,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void preservesInsertionOrderInGroupedMaps() {
        var bySpeaker = new java.util.LinkedHashMap<String, Integer>();
        bySpeaker.put("M02", 1);
        bySpeaker.put("M01", 1);
        bySpeaker.put("FC1", 1);

        var byCondition = new java.util.EnumMap<SpeechCondition, Integer>(SpeechCondition.class);
        byCondition.put(SpeechCondition.CONTROL, 1);
        byCondition.put(SpeechCondition.DYSARTHRIC, 2);

        var report = new TorgoImportAuditReport(
                3, 3, 0, false,
                bySpeaker, byCondition, Map.of(), Map.of(), Map.of());

        assertEquals(List.of("M02", "M01", "FC1"), List.copyOf(report.bySpeaker().keySet()));
        assertEquals(List.of(SpeechCondition.CONTROL, SpeechCondition.DYSARTHRIC),
                List.copyOf(report.byCondition().keySet()));
    }

    private int countFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return (int) stream.filter(Files::isRegularFile).count();
        }
    }
}
