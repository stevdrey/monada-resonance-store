package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechBenchmarkMainParseQueriesTest {

    @TempDir
    Path tempDir;

    private Path manifest(String content) throws IOException {
        Path file = tempDir.resolve("manifest.tsv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private void touchAudio(String name) throws IOException {
        Files.writeString(tempDir.resolve(name), "", StandardCharsets.UTF_8);
    }

    @Test
    void parsesWellFormedManifestIgnoringBlankAndComments() throws IOException {
        touchAudio("q1.wav");
        touchAudio("q2.wav");
        Path manifest = manifest("# header\n\nq1\tq1.wav\ts1,s2\nq2\tq2.wav\ts3\n");
        List<SpeechEvaluationQuery> queries = SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2);
        assertEquals(2, queries.size());
        assertEquals("q1", queries.get(0).queryId());
        assertEquals(2, queries.get(0).relevantSampleIds().size());
    }

    @Test
    void parsesExtendedManifestWithFiltersAndExplicitTopK() throws IOException {
        touchAudio("q1.wav");
        Path manifest = manifest("q1\tq1.wav\ts1,s2\t3\ttorgo\tcontrol\tword\tM01\ten-US\n");

        SpeechEvaluationQuery query = SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2).getFirst();

        assertEquals(3, query.retrievalOptions().topK());
        assertEquals(SpeechDatasetSource.TORGO, query.retrievalOptions().datasetSource());
        assertEquals(SpeechCondition.CONTROL, query.retrievalOptions().condition());
        assertEquals(SpeechTaskType.WORD, query.retrievalOptions().taskType());
        assertEquals("M01", query.retrievalOptions().speakerId());
        assertEquals("en-US", query.retrievalOptions().language());
    }

    @Test
    void rejectsMissingQueryWav() throws IOException {
        Path manifest = manifest("q1\tmissing.wav\ts1\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test
    void rejectsInvalidQueryWavPathWithLineNumber() throws IOException {
        Path manifest = manifest("q1\tbad\u0000path.wav\ts1\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("queryWavPath"), ex.getMessage());
    }

    @Test
    void rejectsInvalidCorpusConfigPath() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SpeechBenchmarkMain.parseConfigPath("corpus dir", "bad\u0000path"));
        assertTrue(ex.getMessage().contains("corpus dir"), ex.getMessage());
        assertTrue(ex.getMessage().contains("valid path"), ex.getMessage());
    }

    @Test
    void rejectsInvalidQueryManifestConfigPath() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SpeechBenchmarkMain.parseConfigPath("query manifest", "bad\u0000path"));
        assertTrue(ex.getMessage().contains("query manifest"), ex.getMessage());
        assertTrue(ex.getMessage().contains("valid path"), ex.getMessage());
    }

    @Test
    void rejectsLineWithTooManyFields() throws IOException {
        Path manifest = manifest("q1\tq1.wav\ts1\t2\tTORGO\tCONTROL\tWORD\tM01\ten-US\textra\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("3 to 9"), ex.getMessage());
    }

    @Test
    void acceptsBlankOptionalTrailingField() throws IOException {
        Path manifest = manifest("q1\tq1.wav\ts1\t\n");
        touchAudio("q1.wav");
        SpeechEvaluationQuery query = SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2).getFirst();
        assertEquals(2, query.retrievalOptions().topK());
    }

    @Test
    void rejectsLineWithTooFewFields() throws IOException {
        Path manifest = manifest("q1\tq1.wav\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
    }

    @Test
    void rejectsBlankQueryId() throws IOException {
        touchAudio("q1.wav");
        Path manifest = manifest("q1\tq1.wav\ts1\n\t q2.wav\ts2\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("queryId"), ex.getMessage());
    }

    @Test
    void rejectsLeadingTabAsBlankQueryId() throws IOException {
        touchAudio("q1.wav");
        Path manifest = manifest("\tq1.wav\ts1\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("queryId"), ex.getMessage());
    }

    @Test
    void rejectsBlankQueryWavPath() throws IOException {
        Path manifest = manifest("q1\t\ts1\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("queryWavPath"), ex.getMessage());
    }

    @Test
    void rejectsEmptyRelevantIds() throws IOException {
        Path manifest = manifest("q1\tq1.wav\t , \n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("relevant"), ex.getMessage());
    }

    @Test
    void rejectsTrailingTabAsEmptyRelevantIds() throws IOException {
        touchAudio("q1.wav");
        Path manifest = manifest("q1\tq1.wav\t\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("relevant"), ex.getMessage());
    }

    @Test
    void rejectsDuplicateQueryIds() throws IOException {
        touchAudio("q1.wav");
        Path manifest = manifest("q1\tq1.wav\ts1\nq1\tq1.wav\ts2\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("duplicates queryId"), ex.getMessage());
    }

    @Test
    void rejectsInvalidFilterEnumAndTopKBelowEvaluationK() throws IOException {
        touchAudio("q1.wav");
        Path invalidEnum = manifest("q1\tq1.wav\ts1\t2\tnot-a-source\n");
        IOException enumException = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(invalidEnum, tempDir, 2));
        assertTrue(enumException.getMessage().contains("datasetSource"), enumException.getMessage());

        Path topKTooSmall = manifest("q1\tq1.wav\ts1\t1\n");
        IOException topKException = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(topKTooSmall, tempDir, 2));
        assertTrue(topKException.getMessage().contains("must be >= evaluation k"), topKException.getMessage());
    }

    @Test
    void deleteRecursivelyRemovesNestedTempDirectory() throws IOException {
        Path root = tempDir.resolve("benchmark-cleanup");
        Path nested = Files.createDirectories(root.resolve("samples").resolve("nested"));
        Files.writeString(nested.resolve("file.txt"), "data", StandardCharsets.UTF_8);

        assertTrue(Files.isDirectory(root));
        SpeechBenchmarkMain.deleteRecursively(root);
        assertFalse(Files.exists(root));
    }

    @Test
    void deleteRecursivelyIsNoOpForMissingPath() throws IOException {
        Path missing = tempDir.resolve("does-not-exist");
        assertFalse(Files.exists(missing));
        SpeechBenchmarkMain.deleteRecursively(missing);
        assertFalse(Files.exists(missing));
    }
}
