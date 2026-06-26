package com.monada.speech.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsLineWithExtraFields() throws IOException {
        Path manifest = manifest("q1\tq1.wav\ts1\textra\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("exactly 3"), ex.getMessage());
    }

    @Test
    void rejectsLineWithExtraTrailingField() throws IOException {
        Path manifest = manifest("q1\tq1.wav\ts1\t\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("exactly 3"), ex.getMessage());
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
}
