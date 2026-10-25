package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.monada.evaluation.book.BookQueryProtocolV3.Slot;
import com.monada.evaluation.book.BookQueryProtocolV3.Split;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The committed {@code book-queries-v3} dataset (issue #186): it fills every slot of the signed protocol, its label
 * files are pinned byte for byte, every case is a model draft that needs human review, and it fails closed until the
 * human sign-off. The assertions on gates follow the committed {@code review.status}, so the reviewer's sign-off
 * commit needs no test edit.
 */
class BookQueryDatasetV3Test {
    static final Path DATASET_DIR = Path.of(System.getProperty("user.dir"))
            .resolve("src/main/resources/com/monada/evaluation/book/book-queries-v3").normalize();
    /** The label files never change after review; the manifest and receipts do, at sign-off, and are pinned by it. */
    private static final Map<String, String> PINNED = new TreeMap<>(Map.of(
            ".gitattributes", "f3d12483dbf81a7cefab18a231f4d41254f3f913a4280fcea94ed01711f6df5f",
            BookQueryDatasetV3.QUERIES_FILE, "0ea47d1c6b3d33203382dd84dd868fff7107e852bec5f9dfeb8ae9a96bb08710",
            BookQueryDatasetV3.JUDGMENTS_FILE, "452ef0fc1e72033a22a58832ec34b364ef217cd180d3c9bd2d85f5e1bf218d54"));

    @TempDir
    Path dir;

    static BookQueryDatasetV3 committed() {
        return BookQueryDatasetV3.load(BookQueryProtocolV3Test.DIR, DATASET_DIR);
    }

    @Test
    void theCommittedDatasetFillsEveryPlannedSlotInPlanOrder() {
        BookQueryDatasetV3 v3 = committed();
        assertEquals(v3.protocol().slots().stream().map(Slot::id).toList(), v3.authoredSlots());
        Properties m = v3.manifest();
        assertEquals("40", m.getProperty("counts.queries"));
        assertEquals("CLOSED", m.getProperty("finalGate.status"));
        assertEquals("0", m.getProperty("authoring.retrievalRunsBeforeFreeze"));
        long finalOnly = v3.protocol().slots().stream().filter(s -> s.split() == Split.FINAL_ONLY).count();
        assertEquals(12, finalOnly);
    }

    @Test
    void theLabelFilesArePinnedByteForByte() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (String name : PINNED.keySet()) {
            actual.put(name, BookQueryDataset.sha256(Files.readAllBytes(DATASET_DIR.resolve(name))));
        }
        assertEquals(PINNED, actual);
    }

    @Test
    void everyCaseIsAModelDraftThatNeedsPerCaseVerification() {
        List<String> rows = BookQueryDataset.lines(BookQueryProtocolV3.read(
                DATASET_DIR.resolve(BookQueryDatasetV3.QUERIES_FILE)), BookQueryDatasetV3.QUERIES_FILE);
        rows.subList(1, rows.size()).forEach(row -> assertTrue(row.endsWith("|claude"), row));
        Properties m = committed().manifest();
        assertEquals(BookQueryDatasetV3.DRAFT_VERIFICATION, m.getProperty("draft.verification"));
        assertEquals("2026-10-24", m.getProperty("draft.draftedAt"));
    }

    @Test
    void theGatesFollowTheCommittedReviewStatus() {
        BookQueryDatasetV3 v3 = committed();
        List<Supplier<?>> finalGated = List.of(v3::cases, v3::finalOnlyCases, v3::reviews, v3::finalOnlyReviews);
        finalGated.forEach(gate -> assertThrows(IllegalStateException.class, gate::get));
        if (v3.reviewed()) {
            assertEquals(28, v3.devCases().size());
            assertEquals(28, v3.devReviews().size());
            assertEquals("40", v3.manifest().getProperty("counts.approved"));
        } else {
            assertEquals(BookQueryProtocolV3.PENDING, v3.manifest().getProperty("review.status"));
            var e = assertThrows(IllegalStateException.class, v3::devCases);
            assertTrue(e.getMessage().contains("before human sign-off"), e.getMessage());
            assertThrows(IllegalStateException.class, v3::devReviews);
        }
    }

    @Test
    void spanVerificationFailsClosedOnAnyOtherBody() {
        BookQueryDatasetV3 v3 = committed();
        int size = Integer.parseInt(v3.protocol().manifest().getProperty("source.sizeBytes"));
        var wrong = assertThrows(IllegalStateException.class, () -> v3.verifySpans(new byte[size]));
        assertTrue(wrong.getMessage().contains("no longer matches the body"), wrong.getMessage());
        var shortBody = assertThrows(IllegalStateException.class, () -> v3.verifySpans(new byte[16]));
        assertTrue(shortBody.getMessage().contains("outside the body"), shortBody.getMessage());
    }

    @Test
    void aFreshJvmParsesTheSameDataset() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Fingerprint.class.getName(), BookQueryProtocolV3Test.DIR.toString(), DATASET_DIR.toString())
                .redirectOutput(dir.resolve("child.out").toFile())
                .redirectError(dir.resolve("child.err").toFile())
                .start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
            fail("child JVM timed out");
        }
        assertEquals(0, process.exitValue(), Files.readString(dir.resolve("child.err"), StandardCharsets.UTF_8));
        assertEquals(Fingerprint.of(committed()) + "\n",
                Files.readString(dir.resolve("child.out"), StandardCharsets.UTF_8));
    }

    /** A digest of what any caller may see before sign-off: the authored slots and the validated manifest. */
    static final class Fingerprint {
        static String of(BookQueryDatasetV3 v3) {
            StringBuilder text = new StringBuilder(String.join(",", v3.authoredSlots())).append('\n');
            new TreeMap<>(v3.manifest()).forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
            return BookQueryDataset.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
        }

        public static void main(String[] args) {
            System.out.println(of(BookQueryDatasetV3.load(Path.of(args[0]), Path.of(args[1]))));
        }
    }
}
