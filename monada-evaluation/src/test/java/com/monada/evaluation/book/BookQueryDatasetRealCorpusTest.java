package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Opt-in check of {@code book-queries-v1} against the real {@code book-corpus-v1} normalized body. Skipped unless
 * {@value #BODY_ENV} names the output of {@code VerifyBookCorpus.java --write-normalized}; tests never download it.
 * It re-derives the chunk map with policy v1 and verifies every judged span's SHA-256 against the body bytes.
 */
class BookQueryDatasetRealCorpusTest {
    private static final String BODY_ENV = "MONADA_BOOK_CORPUS_V1_BODY";

    @Test
    void everyJudgedSpanAndTheChunkMapMatchTheRealBody() throws Exception {
        String body = System.getenv(BODY_ENV);
        assumeTrue(body != null && !body.isBlank(), BODY_ENV + " is not set; the real corpus check is opt-in");
        // Gradle runs tests with the module directory as working directory.
        Path manifest = Path.of(System.getProperty("user.dir")).resolve("../" + BookChunkingMain.DEFAULT_MANIFEST)
                .normalize();
        VerifiedBookSource source = VerifiedBookSource.load(manifest, Path.of(body));
        BookChunkOutputWriter.Output output = BookChunkOutputWriter.render(
                BookChunker.chunk(source, BookChunkingPolicy.V1));
        BookQueryDataset dataset = BookQueryDataset.load();

        assertArrayEquals(BookQueryDataset.resource("chunk-map.jsonl"), output.sidecar(),
                "committed chunk map differs from a fresh book-chunking-v1 run");
        assertEquals(dataset.manifest().getProperty("chunksFile.sha256"), BookQueryDataset.sha256(output.chunks()));

        byte[] bytes = source.text().getBytes(StandardCharsets.UTF_8);
        int spans = 0;
        for (var c : dataset.cases()) {
            for (var j : c.judgments()) {
                byte[] span = Arrays.copyOfRange(bytes, (int) j.byteStart(), (int) j.byteEnd());
                assertEquals(j.spanSha256(), BookQueryDataset.sha256(span),
                        c.id() + " span [" + j.byteStart() + "," + j.byteEnd() + ") no longer matches the body");
                spans++;
            }
        }
        System.out.println("book-queries-v1: " + dataset.cases().size() + " cases, " + spans
                + " judged spans verified against the real body");
    }
}
