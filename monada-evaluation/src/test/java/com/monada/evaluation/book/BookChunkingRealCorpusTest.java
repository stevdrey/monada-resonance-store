package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Opt-in check of policy v1 against the real {@code book-corpus-v1} normalized body. Skipped unless
 * {@value #BODY_ENV} names the output of {@code VerifyBookCorpus.java --write-normalized}; tests never download it.
 */
class BookChunkingRealCorpusTest {
    private static final String BODY_ENV = "MONADA_BOOK_CORPUS_V1_BODY";

    @Test
    void policyV1ChunksTheRealBodyIntoTwelveChaptersDeterministically() throws Exception {
        String body = System.getenv(BODY_ENV);
        assumeTrue(body != null && !body.isBlank(), BODY_ENV + " is not set; the real corpus check is opt-in");
        // Gradle runs tests with the module directory as working directory.
        Path manifest = Path.of(System.getProperty("user.dir")).resolve("../" + BookChunkingMain.DEFAULT_MANIFEST)
                .normalize();

        BookChunker.Result first = BookChunker.chunk(VerifiedBookSource.load(manifest, Path.of(body)),
                BookChunkingPolicy.V1);
        BookChunker.Result second = BookChunker.chunk(VerifiedBookSource.load(manifest, Path.of(body)),
                BookChunkingPolicy.V1);
        BookChunkOutputWriter.Output output = BookChunkOutputWriter.render(first);

        assertTrue(first.frontMatter());
        assertEquals(12, first.chapterCount());
        assertTrue(first.chunks().stream().allMatch(c -> c.utf8Bytes() <= BookChunkingPolicy.V1.maxChunkBytes()));
        assertArrayEquals(output.chunks(), BookChunkOutputWriter.render(second).chunks());
        assertArrayEquals(output.sidecar(), BookChunkOutputWriter.render(second).sidecar());
        System.out.print(BookChunkingReport.of(first, output).render());
    }
}
