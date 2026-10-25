package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Opt-in check of {@code book-queries-v3} against the real {@code book-corpus-v1} normalized body. Skipped unless
 * {@value #BODY_ENV} names the output of {@code VerifyBookCorpus.java --write-normalized}; tests never download it.
 * The body is verified against the pinned corpus manifest, then every v3 span digest is recomputed. This works before
 * sign-off because it reads no query text, label or receipt.
 */
class BookQueryDatasetV3RealCorpusTest {
    private static final String BODY_ENV = "MONADA_BOOK_CORPUS_V1_BODY";

    @Test
    void everyV3SpanMatchesTheRealBody() throws Exception {
        String body = System.getenv(BODY_ENV);
        assumeTrue(body != null && !body.isBlank(), BODY_ENV + " is not set; the real corpus check is opt-in");
        // Gradle runs tests with the module directory as working directory.
        Path manifest = Path.of(System.getProperty("user.dir")).resolve("../" + BookChunkingMain.DEFAULT_MANIFEST)
                .normalize();
        VerifiedBookSource source = VerifiedBookSource.load(manifest, Path.of(body));
        BookQueryDatasetV3 v3 = BookQueryDatasetV3Test.committed();

        int spans = v3.verifySpans(source.text().getBytes(StandardCharsets.UTF_8));
        assertEquals(Integer.parseInt(v3.manifest().getProperty("counts.judgments")), spans);
        System.out.println("book-queries-v3: " + v3.authoredSlots().size() + " cases, " + spans
                + " judged spans verified against the real body");
    }
}
