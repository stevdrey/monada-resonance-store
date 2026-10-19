package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seeds Stores from the {@code book-chunking-fixture-v1} chunks and checks exact readback on fresh instances, the
 * import kinds and the failure reports. The fixture policy yields 10 chunks of which one repeats earlier text.
 */
class BookStoreReadbackTest {
    private static final String FIXTURE = "/book-chunking-fixture-v1/";
    private static final BookChunkingPolicy FIXTURE_POLICY = new BookChunkingPolicy("fixture-max200",
            "CHAPTER [IVXLC]+\\.", 200, 0);

    @TempDir
    Path dir;

    private record Run(int exit, String out, String err) {
    }

    private Path copyFixture(String name) throws IOException {
        try (InputStream in = BookStoreReadbackTest.class.getResourceAsStream(FIXTURE + name)) {
            assertNotNull(in, "missing fixture " + name);
            Path target = dir.resolve(name);
            Files.write(target, in.readAllBytes());
            return target;
        }
    }

    private BookChunker.Result fixtureResult() throws Exception {
        return BookChunker.chunk(VerifiedBookSource.load(copyFixture("manifest.properties"),
                copyFixture("body.txt")), FIXTURE_POLICY);
    }

    private Path work(String name) throws Exception {
        return ExperimentDirectoryGuard.prepareForSeed(dir.resolve(name), List.of());
    }

    private static Run runMain(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookStoreReadbackMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cleanSeedPersistsEveryUniqueChunkAndReadsItBackExactly() throws Exception {
        Path work = work("clean");
        BookStoreReadback.Seed seed = BookStoreReadback.seed(fixtureResult(), work);
        BookStoreReadback.Verification v = seed.verification();

        assertEquals(BookStoreReadback.ImportKind.CLEAN, seed.importKind());
        assertEquals(0, seed.before().uniqueAtoms());
        assertEquals(9, seed.acceptedNewAtoms());
        assertEquals(10, v.attemptedChunks());
        assertEquals(1, v.duplicateContentChunks());
        assertEquals(9, v.expectedUniqueAtoms());
        assertEquals(9, v.inventory().uniqueAtoms());
        assertEquals(9, v.exactReadback());
        assertEquals(10, v.resolvedChunks());
        assertEquals(List.of(), v.failedHashes());
        assertEquals(0, v.unexpectedAtoms());
        assertEquals("HEALTHY", v.audit().status());
        assertEquals(0, v.audit().warnings());
        assertTrue(v.storeUnchanged(), "verification must not write");
        assertTrue(seed.passed());
        assertTrue(Files.exists(work.resolve("chunks").resolve(BookChunkOutputWriter.SIDECAR_FILE)),
                "the sidecar lives outside the Store");
        assertFalse(Files.exists(work.resolve("store").resolve(BookChunkOutputWriter.SIDECAR_FILE)));
    }

    @Test
    void freshReopenProducesTheIdenticalInventoryWithoutWriting() throws Exception {
        Path work = work("reopen");
        BookStoreReadback.Seed seed = BookStoreReadback.seed(fixtureResult(), work);
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        BookStoreReadback.Verification reopened = BookStoreReadback.verify(work);

        assertEquals(seed.after().fingerprint(), reopened.inventory().fingerprint());
        assertEquals(seed.verification().inventory().contents(), reopened.inventory().contents());
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")));
        assertTrue(reopened.passed());
    }

    @Test
    void secondIdenticalImportIsReportedAsRepeatAndAppendsNothing() throws Exception {
        Path work = work("repeat");
        BookStoreReadback.Seed first = BookStoreReadback.seed(fixtureResult(), work);
        BookStoreReadback.Seed second = BookStoreReadback.seed(fixtureResult(), work);

        assertEquals(BookStoreReadback.ImportKind.REPEAT, second.importKind());
        assertEquals(0, second.acceptedNewAtoms());
        assertEquals(first.after().physicalRecords(), second.after().physicalRecords());
        assertEquals(first.after().fingerprint(), second.after().fingerprint());
        assertTrue(second.storeBytesUnchanged());
        assertTrue(second.passed());
        assertTrue(BookStoreReadbackReport.render(second).contains("import kind:                   REPEAT"));
    }

    @Test
    void interruptedImportIsResumed() throws Exception {
        Path work = work("resume");
        BookChunker.Result result = fixtureResult();
        MonadaMemory.open(work.resolve("store"))
                .rememberAll(result.chunks().subList(0, 3).stream().map(BookChunk::text).toList());

        BookStoreReadback.Seed seed = BookStoreReadback.seed(result, work);

        assertEquals(BookStoreReadback.ImportKind.RESUMED, seed.importKind());
        assertEquals(3, seed.before().uniqueAtoms());
        assertEquals(6, seed.acceptedNewAtoms());
        assertTrue(seed.passed());
    }

    @Test
    void foreignAtomIsReportedAndFails() throws Exception {
        Path work = work("foreign");
        BookStoreReadback.seed(fixtureResult(), work);
        MonadaMemory.open(work.resolve("store")).remember("an atom that is no chunk of this book");

        BookStoreReadback.Verification v = BookStoreReadback.verify(work);
        assertEquals(1, v.unexpectedAtoms());
        assertFalse(v.passed());
        BookStoreReadback.Seed again = BookStoreReadback.seed(fixtureResult(), work);
        assertEquals(BookStoreReadback.ImportKind.FOREIGN, again.importKind());
        assertFalse(again.passed());
    }

    @Test
    void foreignStoreIsRejectedBeforeAnythingIsAppended() throws Exception {
        Path work = work("foreign-partial");
        BookChunker.Result result = fixtureResult();
        MonadaMemory memory = MonadaMemory.open(work.resolve("store"));
        memory.remember("an atom that is no chunk of this book");
        memory.remember(result.chunks().getFirst().text());
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        BookStoreReadback.Seed seed = BookStoreReadback.seed(result, work);

        assertEquals(BookStoreReadback.ImportKind.FOREIGN, seed.importKind());
        assertEquals(0, seed.acceptedNewAtoms());
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")), "a FOREIGN Store is not touched");
        assertFalse(seed.passed());
    }

    @Test
    void corruptManifestIsReportedWithoutCrashingOrWriting() throws Exception {
        Path work = work("corrupt");
        BookStoreReadback.seed(fixtureResult(), work);
        Files.writeString(work.resolve("store/manifest.json"), "{", StandardCharsets.UTF_8);
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        BookStoreReadback.Verification v = BookStoreReadback.verify(work);

        assertNotNull(v.inventoryError());
        assertFalse(v.audit().healthy());
        assertFalse(v.passed());
        assertEquals(9, v.failedHashes().size());
        assertTrue(BookStoreReadbackReport.render(v).contains("inventory:                     UNREADABLE"));
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")));
        Run cli = runMain("verify", "--work", work.toString());
        assertEquals(1, cli.exit(), cli.err());
    }

    @Test
    void verifyDoesNotRecreateMissingStoreDirectories() throws Exception {
        Path work = work("missing-dirs");
        BookStoreReadback.seed(fixtureResult(), work);
        try (var walk = Files.walk(work.resolve("store/feedback"))) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }

        BookStoreReadback.Verification v = BookStoreReadback.verify(work);

        assertFalse(Files.exists(work.resolve("store/feedback")), "verify must not recreate directories");
        assertTrue(v.storeUnchanged());
        assertEquals(OptionalInt.of(9), v.selfRecallHits(), "recall runs on a throwaway copy");
    }

    @Test
    void verifyDoesNotRecreateAMissingFeedbackLogButARepeatSeedFailsWhenItDoes() throws Exception {
        Path work = work("missing-log");
        BookStoreReadback.seed(fixtureResult(), work);
        Path log = work.resolve("store/feedback/feedback-000001.log");
        Files.delete(log);
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        BookStoreReadback.Verification v = BookStoreReadback.verify(work);
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")));
        assertTrue(v.passed());

        BookStoreReadback.Seed repeat = BookStoreReadback.seed(fixtureResult(), work);
        assertEquals(BookStoreReadback.ImportKind.REPEAT, repeat.importKind());
        // Opening MonadaMemory recreates the missing feedback log, so the Store bytes changed.
        assertFalse(repeat.storeBytesUnchanged());
        assertFalse(repeat.passed(), "a REPEAT that changed Store bytes must fail");
        assertTrue(BookStoreReadbackReport.render(repeat).contains("FAIL: a repeat import must not change"));
    }

    @Test
    void storeWithAnOrphanVectorIsDamagedAndNotAppendedTo() throws Exception {
        Path work = work("orphan");
        BookStoreReadback.seed(fixtureResult(), work);
        // Simulate an import interrupted after the vector append but before the atom append.
        Path atomLog = work.resolve("store/atoms/segment-000001.log");
        List<String> lines = Files.readAllLines(atomLog, StandardCharsets.UTF_8);
        Files.write(atomLog, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        BookStoreReadback.Seed seed = BookStoreReadback.seed(fixtureResult(), work);

        assertEquals(BookStoreReadback.ImportKind.DAMAGED, seed.importKind());
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")), "a DAMAGED Store is not touched");
        assertFalse(seed.passed());
        assertTrue(BookStoreReadbackReport.render(seed).contains("SKIPPED"));
    }

    /** Rewrites field {@code index} of the atom log record for {@code id}. */
    private static void rewriteAtomField(Path work, String id, int index, String value) throws IOException {
        Path log = work.resolve("store/atoms/segment-000001.log");
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields[0].equals(id)) {
                fields[index] = value;
                line = String.join("\t", fields);
            }
            lines.add(line);
        }
        Files.write(log, lines, StandardCharsets.UTF_8);
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void expectedIdHoldingOtherContentIsForeignAndNotAppendedTo() throws Exception {
        Path work = work("spoofed");
        BookChunker.Result result = fixtureResult();
        String first = result.chunks().getFirst().text();
        MonadaMemory.open(work.resolve("store")).remember(first);
        rewriteAtomField(work, KnowledgeAtom.text(first).id(), 4, base64("spoofed content"));
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        BookStoreReadback.Seed seed = BookStoreReadback.seed(result, work);

        assertEquals(BookStoreReadback.ImportKind.FOREIGN, seed.importKind());
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")), "a FOREIGN Store is not touched");
        assertFalse(seed.passed());
    }

    @Test
    void auditorFailureIsReportedInsteadOfCrashing() throws Exception {
        Path work = work("audit-throws");
        BookChunker.Result result = fixtureResult();
        BookStoreReadback.seed(result, work);
        // A Base64-valid alias that decodes to a blank string makes atom parsing throw.
        rewriteAtomField(work, KnowledgeAtom.text(result.chunks().getFirst().text()).id(), 5, base64(" "));

        BookStoreReadback.Verification v = BookStoreReadback.verify(work);

        assertFalse(v.passed());
        assertFalse(v.audit().healthy());
        assertTrue(BookStoreReadbackReport.render(v).contains("Result: FAIL"));
        Run cli = runMain("verify", "--work", work.toString());
        assertEquals(1, cli.exit(), cli.err());
        BookStoreReadback.Seed seed = BookStoreReadback.seed(result, work);
        assertEquals(BookStoreReadback.ImportKind.DAMAGED, seed.importKind());
    }

    @Test
    void unterminatedAtomLogIsDamagedAndNotAppendedTo() throws Exception {
        Path work = work("unterminated");
        BookChunker.Result result = fixtureResult();
        MonadaMemory.open(work.resolve("store"))
                .rememberAll(result.chunks().subList(0, 3).stream().map(BookChunk::text).toList());
        Path log = work.resolve("store/atoms/segment-000001.log");
        byte[] bytes = Files.readAllBytes(log);
        Files.write(log, Arrays.copyOf(bytes, bytes.length - 1));
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        BookStoreReadback.Seed seed = BookStoreReadback.seed(result, work);

        assertEquals(BookStoreReadback.ImportKind.DAMAGED, seed.importKind());
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")), "a DAMAGED Store is not touched");
        assertFalse(seed.passed());
    }

    @Test
    void alteredPersistedContentIsAFailedHash() throws Exception {
        Path work = work("altered");
        BookChunker.Result result = fixtureResult();
        BookStoreReadback.seed(result, work);
        BookChunk victim = result.chunks().get(4);
        String id = KnowledgeAtom.text(victim.text()).id();
        Path log = work.resolve("store/atoms/segment-000001.log");
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields[0].equals(id)) {
                fields[4] = Base64.getEncoder().encodeToString("tampered".getBytes(StandardCharsets.UTF_8));
                line = String.join("\t", fields);
            }
            lines.add(line);
        }
        Files.write(log, lines, StandardCharsets.UTF_8);

        BookStoreReadback.Verification v = BookStoreReadback.verify(work);

        assertEquals(List.of(victim.sha256()), v.failedHashes());
        assertEquals(8, v.exactReadback());
        assertFalse(v.passed());
    }

    @Test
    void reportIsDeterministicAcrossWorkDirectories() throws Exception {
        String a = BookStoreReadbackReport.render(BookStoreReadback.seed(fixtureResult(), work("a")));
        String b = BookStoreReadbackReport.render(BookStoreReadback.seed(fixtureResult(), work("b")));
        assertEquals(a, b);
        assertFalse(a.contains(dir.toString()), "the report must not contain paths");
        assertTrue(a.contains("self-recall@1 (exploratory)"));
    }

    @Test
    void commandLineSeedsRepeatsAndVerifies() throws Exception {
        Path manifest = copyFixture("manifest.properties");
        Path body = copyFixture("body.txt");
        String work = dir.resolve("cli").toString();
        String[] seed = {"seed", "--manifest", manifest.toString(), "--input", body.toString(), "--work", work};

        Run clean = runMain(seed);
        assertEquals(0, clean.exit(), clean.err());
        assertTrue(clean.out().contains("import kind:                   CLEAN"), clean.out());
        assertTrue(clean.out().contains("chunking policy:               book-chunking-v1"), clean.out());
        assertTrue(clean.out().endsWith("Result: PASS\n"), clean.out());

        Run repeat = runMain(seed);
        assertEquals(0, repeat.exit(), repeat.err());
        assertTrue(repeat.out().contains("import kind:                   REPEAT"), repeat.out());

        Run verify = runMain("verify", "--work", work);
        assertEquals(0, verify.exit(), verify.err());
        assertTrue(verify.out().contains("integrity audit:               HEALTHY"), verify.out());
    }

    @Test
    void commandLineRejectsUsageErrorsAndUnseededDirectories() throws Exception {
        assertEquals(2, runMain().exit());
        assertEquals(2, runMain("import", "--work", "x").exit());
        assertEquals(2, runMain("verify", "--input", "x", "--work", "y").exit());
        assertEquals(2, runMain("seed", "--work", "x").exit());
        Run unseeded = runMain("verify", "--work", dir.resolve("never-seeded").toString());
        assertEquals(2, unseeded.exit());
        assertFalse(Files.exists(dir.resolve("never-seeded")), "verify must not create the work directory");
    }

    @Test
    void wrongBodyCreatesNothing() throws Exception {
        Path manifest = copyFixture("manifest.properties");
        Path body = dir.resolve("wrong.txt");
        Files.writeString(body, "not the verified body\n", StandardCharsets.UTF_8);
        Path work = dir.resolve("wrong-work");

        Run run = runMain("seed", "--manifest", manifest.toString(), "--input", body.toString(), "--work",
                work.toString());

        assertEquals(1, run.exit(), run.err());
        assertFalse(Files.exists(work));
    }
}
