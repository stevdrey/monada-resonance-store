package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Opt-in check of {@code book-queries-v2} against the real {@code book-corpus-v1} normalized body. Skipped unless
 * {@value #BODY_ENV} names the output of {@code VerifyBookCorpus.java --write-normalized}; tests never download it.
 * Every added judgment and every finding span must hash to its recorded SHA-256 in the body bytes.
 */
class BookQueryDatasetV2RealCorpusTest {
    private static final String BODY_ENV = "MONADA_BOOK_CORPUS_V1_BODY";

    @Test
    void everyReviewedSpanMatchesTheRealBody() throws Exception {
        String body = System.getenv(BODY_ENV);
        assumeTrue(body != null && !body.isBlank(), BODY_ENV + " is not set; the real corpus check is opt-in");
        // Gradle runs tests with the module directory as working directory.
        Path manifest = Path.of(System.getProperty("user.dir")).resolve("../" + BookChunkingMain.DEFAULT_MANIFEST)
                .normalize();
        VerifiedBookSource source = VerifiedBookSource.load(manifest, Path.of(body));
        BookQueryDatasetV2 v2 = BookQueryDatasetV2.load();
        assertEquals(v2.base().manifest().getProperty("source.sha256"), source.sha256());

        byte[] bytes = source.text().getBytes(StandardCharsets.UTF_8);
        int spans = 0;
        for (var r : v2.reviews()) {
            if (r.decision() == BookQueryDatasetV2.Decision.UNRESOLVED) {
                continue;
            }
            byte[] span = Arrays.copyOfRange(bytes, (int) r.byteStart(), (int) r.byteEnd());
            assertEquals(r.spanSha256(), BookQueryDataset.sha256(span),
                    r.pairId() + " span [" + r.byteStart() + "," + r.byteEnd() + ") does not match the body");
            spans++;
        }
        // The worksheet renders from the same body; an unfilled one yields no decision.
        String sheet = BookReviewWorksheet.render(bytes, v2.base(), v2.selection());
        assertEquals(BookQueryDatasetV2.REVIEW_HEADER + "\n",
                BookReviewWorksheet.apply(sheet, bytes, v2.base(), v2.selection()));
        Map<String, Long> decisions = new TreeMap<>();
        v2.reviews().forEach(r -> decisions.merge(r.decision().name(), 1L, Long::sum));
        System.out.println("book-queries-v2: " + v2.reviews().size() + "/" + v2.selection().size() + " pairs decided "
                + decisions + ", " + spans + " reviewed spans verified against the real body");
    }
}
