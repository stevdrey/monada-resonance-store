package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads the golden {@code book-chunking-fixture-v1} chunk files back and rejects pairs that disagree. */
class BookChunkFilesTest {
    private static final String FIXTURE = "/book-chunking-fixture-v1/";

    @TempDir
    Path dir;

    private void copy(String resource, String name) throws IOException {
        try (InputStream in = BookChunkFilesTest.class.getResourceAsStream(FIXTURE + resource)) {
            assertNotNull(in, "missing fixture " + resource);
            Files.write(dir.resolve(name), in.readAllBytes());
        }
    }

    private void copyGolden() throws IOException {
        copy("expected-chunks.jsonl", BookChunkOutputWriter.CHUNKS_FILE);
        copy("expected-sidecar.jsonl", BookChunkOutputWriter.SIDECAR_FILE);
    }

    private BookInputException rejected() {
        return assertThrows(BookInputException.class, () -> BookChunkFiles.load(dir));
    }

    @Test
    void goldenFilesLoadWithProvenanceAndDuplicates() throws Exception {
        copyGolden();
        BookChunkFiles.Loaded loaded = BookChunkFiles.load(dir);

        assertEquals("book-chunking-fixture", loaded.corpus());
        assertEquals("fixture-max200", loaded.policyId());
        assertEquals(10, loaded.entries().size());
        assertEquals(1, loaded.entries().stream().filter(e -> e.duplicateOf() != null).count());
        BookChunkFiles.Entry first = loaded.entries().getFirst();
        assertNull(first.duplicateOf());
        assertEquals(VerifiedBookSource.sha256(first.text().getBytes(StandardCharsets.UTF_8)), first.sha256());
    }

    @Test
    void alteredChunkTextIsRejectedByTheChunksFileDigest() throws Exception {
        copyGolden();
        Path chunks = dir.resolve(BookChunkOutputWriter.CHUNKS_FILE);
        Files.writeString(chunks, Files.readString(chunks).replaceFirst("\"text\":\"", "\"text\":\"X"));
        assertTrue(rejected().getMessage().contains("chunks file digest"));
    }

    @Test
    void sidecarLineThatDisagreesWithItsChunkIsRejected() throws Exception {
        copyGolden();
        Path sidecar = dir.resolve(BookChunkOutputWriter.SIDECAR_FILE);
        String text = Files.readString(sidecar);
        int line = text.indexOf('\n') + 1;
        int sha = text.indexOf("\"sha256\":\"", line) + "\"sha256\":\"".length();
        char flipped = text.charAt(sha) == '0' ? '1' : '0';
        Files.writeString(sidecar, text.substring(0, sha) + flipped + text.substring(sha + 1));
        assertTrue(rejected().getMessage().contains("does not match the sidecar"));
    }

    @Test
    void sidecarLineWithoutDuplicateOfIsRejected() throws Exception {
        copyGolden();
        Path sidecar = dir.resolve(BookChunkOutputWriter.SIDECAR_FILE);
        String text = Files.readString(sidecar);
        Files.writeString(sidecar, text.replaceFirst(",\"duplicateOf\":null", ""));
        assertTrue(rejected().getMessage().contains("missing field duplicateOf"));
    }

    @Test
    void missingSidecarIsAnIoFailure() throws Exception {
        copy("expected-chunks.jsonl", BookChunkOutputWriter.CHUNKS_FILE);
        assertThrows(IOException.class, () -> BookChunkFiles.load(dir));
    }

    @Test
    void truncatedChunksFileIsRejected() throws Exception {
        copyGolden();
        Path chunks = dir.resolve(BookChunkOutputWriter.CHUNKS_FILE);
        String text = Files.readString(chunks);
        Files.writeString(chunks, text.substring(0, text.length() - 1));
        rejected();
    }
}
