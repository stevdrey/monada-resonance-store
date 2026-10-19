package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import com.monada.core.MonadaRecall;
import com.monada.storage.audit.StorageIntegrityAuditor;
import com.monada.storage.audit.StorageIntegrityReport;
import com.monada.storage.audit.StorageIntegritySeverity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Seeds a disposable Store from verified book chunk files and proves, from disk, that every unique chunk is persisted
 * and reads back byte for byte (evaluation only; Issue #165).
 *
 * <p>Access levels, kept apart on purpose:
 * <ul>
 *   <li><b>write</b>: the public {@link MonadaMemory#rememberAll(List)} API, nothing lower;</li>
 *   <li><b>exact readback</b>: {@link BookStoreInventory}, the published lower-level storage read API
 *   ({@code FileManifestStore} + {@code FileAtomStore.findAll}), on fresh instances, never the seeding instance;</li>
 *   <li><b>structure</b>: the read-only {@link StorageIntegrityAuditor};</li>
 *   <li><b>semantic</b>: public {@link MonadaMemory#resonate(String)} self-recall@1, reported as exploratory and
 *   never counted as readback evidence (top-K ranking does not prove full-source readback).</li>
 * </ul>
 * Chunk keys and provenance stay in the external sidecar: the Store does not persist atom metadata (ADR 0004), so
 * the chunk-to-atom mapping is the content-derived id {@code KnowledgeAtom.text(text).id()}.
 */
public final class BookStoreReadback {

    private BookStoreReadback() {
    }

    /** How a seeding run related to what the Store already held. */
    public enum ImportKind {
        /** The Store was empty or absent; every unique chunk was appended. */
        CLEAN,
        /** The Store already held exactly these chunks; nothing was appended (no space is reclaimed either). */
        REPEAT,
        /** The Store held a subset of these chunks (an interrupted import); the missing ones were appended. */
        RESUMED,
        /** The Store held atoms that are not chunks of this set; the run is not a valid readback experiment. */
        FOREIGN
    }

    /** The structural audit, reduced to deterministic fields (no paths). */
    public record Audit(String status, long info, long warnings, long errors, long fatal) {
        static Audit of(StorageIntegrityReport report) {
            return new Audit(status(report), report.countBySeverity(StorageIntegritySeverity.INFO),
                    report.countBySeverity(StorageIntegritySeverity.WARNING),
                    report.countBySeverity(StorageIntegritySeverity.ERROR),
                    report.countBySeverity(StorageIntegritySeverity.FATAL));
        }

        /** Mirrors {@link StorageIntegrityReport#render()}: HEALTHY means no finding at WARNING or above. */
        private static String status(StorageIntegrityReport report) {
            if (report.countBySeverity(StorageIntegritySeverity.FATAL) > 0) {
                return "CORRUPTED (FATAL)";
            }
            if (report.countBySeverity(StorageIntegritySeverity.ERROR) > 0) {
                return "INCONSISTENT (ERRORS)";
            }
            return report.countBySeverity(StorageIntegritySeverity.WARNING) > 0 ? "WARNINGS" : "HEALTHY";
        }

        public boolean healthy() {
            return status.equals("HEALTHY");
        }
    }

    /**
     * The result of verifying a seeded Store against its chunk files.
     *
     * @param exactReadback      unique chunk texts whose atom reads back with exactly that content
     * @param resolvedChunks     chunks (repeats included) whose content-derived id reads back exactly
     * @param failedHashes       text digests of unique chunks that are missing or read back different content
     * @param unexpectedAtoms    persisted atoms that are not any chunk of the set
     * @param storeUnchanged     the Store's bytes are identical before and after verification (it wrote nothing)
     * @param selfRecallHits     unique chunks whose own text recalls their atom at rank 1 (exploratory)
     */
    public record Verification(BookChunkFiles.Loaded chunks, int attemptedChunks, int duplicateContentChunks,
                               int expectedUniqueAtoms, BookStoreInventory inventory, int exactReadback,
                               int resolvedChunks, List<String> failedHashes, int unexpectedAtoms, Audit audit,
                               boolean storeUnchanged, int selfRecallHits) {
        public Verification {
            failedHashes = List.copyOf(failedHashes);
        }

        public boolean passed() {
            return failedHashes.isEmpty() && exactReadback == expectedUniqueAtoms
                    && resolvedChunks == attemptedChunks && inventory.uniqueAtoms() == expectedUniqueAtoms
                    && unexpectedAtoms == 0 && audit.healthy() && storeUnchanged;
        }
    }

    /**
     * The result of one seeding run: the Store before and after {@code rememberAll}, then a verification on fresh
     * instances.
     */
    public record Seed(ImportKind importKind, BookStoreInventory before, BookStoreInventory after,
                       boolean storeBytesUnchanged, Verification verification) {
        public int acceptedNewAtoms() {
            return after.uniqueAtoms() - before.uniqueAtoms();
        }

        public boolean passed() {
            return importKind != ImportKind.FOREIGN && verification.passed();
        }
    }

    /**
     * Writes the chunk files of {@code result} into {@code work/chunks} (outside the Store), reloads them from disk,
     * remembers every chunk into {@code work/store} through the public API and verifies the Store on fresh instances.
     * {@code work} must already have passed {@link ExperimentDirectoryGuard#prepareForSeed}.
     */
    public static Seed seed(BookChunker.Result result, Path work) throws IOException, BookInputException {
        Path chunkDir = work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR);
        Path store = work.resolve(ExperimentDirectoryGuard.STORE_DIR);
        BookChunkOutputWriter.write(result, chunkDir);
        BookChunkFiles.Loaded chunks = BookChunkFiles.load(chunkDir);

        BookStoreInventory before = BookStoreInventory.read(store);
        String digestBefore = BookStoreInventory.treeDigest(store);
        // MonadaMemory has no close(): the seeding instance is dropped here and everything below reads fresh instances.
        MonadaMemory.open(store).rememberAll(chunks.entries().stream().map(BookChunkFiles.Entry::text).toList());
        String digestAfter = BookStoreInventory.treeDigest(store);
        BookStoreInventory after = BookStoreInventory.read(store);

        Map<String, String> expected = expectedContents(chunks);
        ImportKind kind;
        if (!expected.keySet().containsAll(before.contents().keySet())) {
            kind = ImportKind.FOREIGN;
        } else if (before.uniqueAtoms() == 0) {
            kind = ImportKind.CLEAN;
        } else if (before.fingerprint().equals(after.fingerprint())) {
            kind = ImportKind.REPEAT;
        } else {
            kind = ImportKind.RESUMED;
        }
        return new Seed(kind, before, after, digestBefore.equals(digestAfter), verify(chunks, store));
    }

    /** Verifies the Store of an already seeded work directory against its chunk files; writes nothing. */
    public static Verification verify(Path work) throws IOException, BookInputException {
        return verify(BookChunkFiles.load(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR)),
                work.resolve(ExperimentDirectoryGuard.STORE_DIR));
    }

    private static Verification verify(BookChunkFiles.Loaded chunks, Path store) throws IOException {
        String digestBefore = BookStoreInventory.treeDigest(store);
        BookStoreInventory inventory = BookStoreInventory.read(store);
        Audit audit = Audit.of(new StorageIntegrityAuditor().audit(store));

        Map<String, String> expected = expectedContents(chunks);
        int exact = 0;
        TreeSet<String> failed = new TreeSet<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (entry.getValue().equals(inventory.contents().get(entry.getKey()))) {
                exact++;
            } else {
                failed.add(sha256(entry.getValue()));
            }
        }
        int resolved = 0;
        for (BookChunkFiles.Entry entry : chunks.entries()) {
            if (entry.text().equals(inventory.contents().get(KnowledgeAtom.text(entry.text()).id()))) {
                resolved++;
            }
        }
        int unexpected = (int) inventory.contents().keySet().stream().filter(id -> !expected.containsKey(id))
                .count();

        int recallHits = 0;
        if (Objects.equals(inventory.contents().keySet(), expected.keySet()) && !expected.isEmpty()) {
            MonadaMemory reopened = MonadaMemory.open(store);
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                MonadaRecall recall = reopened.resonate(entry.getValue()).topK(1).execute();
                if (!recall.results().isEmpty()
                        && recall.results().getFirst().atom().id().equals(entry.getKey())) {
                    recallHits++;
                }
            }
        }
        boolean unchanged = digestBefore.equals(BookStoreInventory.treeDigest(store));

        int duplicates = (int) chunks.entries().stream().filter(e -> e.duplicateOf() != null).count();
        return new Verification(chunks, chunks.entries().size(), duplicates, expected.size(), inventory, exact,
                resolved, new ArrayList<>(failed), unexpected, audit, unchanged, recallHits);
    }

    /** Content-derived atom id to chunk text, one entry per unique text, in first-occurrence order. */
    private static Map<String, String> expectedContents(BookChunkFiles.Loaded chunks) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (BookChunkFiles.Entry entry : chunks.entries()) {
            expected.putIfAbsent(KnowledgeAtom.text(entry.text()).id(), entry.text());
        }
        return expected;
    }

    private static String sha256(String text) {
        return VerifiedBookSource.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
