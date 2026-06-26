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

    @Test
    void parsesWellFormedManifestIgnoringBlankAndComments() throws IOException {
        Path manifest = manifest("# header\n\nq1\tq1.wav\ts1,s2\nq2\tq2.wav\ts3\n");
        List<SpeechEvaluationQuery> queries = SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2);
        assertEquals(2, queries.size());
        assertEquals("q1", queries.get(0).queryId());
        assertEquals(2, queries.get(0).relevantSampleIds().size());
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
    void rejectsLineWithTooFewFields() throws IOException {
        Path manifest = manifest("q1\tq1.wav\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
    }

    @Test
    void rejectsBlankQueryId() throws IOException {
        Path manifest = manifest("q1\tq1.wav\ts1\n\t q2.wav\ts2\n");
        IOException ex = assertThrows(IOException.class,
                () -> SpeechBenchmarkMain.parseQueries(manifest, tempDir, 2));
        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
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
}
