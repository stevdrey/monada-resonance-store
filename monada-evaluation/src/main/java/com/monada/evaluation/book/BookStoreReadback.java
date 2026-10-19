package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import com.monada.core.MonadaRecall;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.Manifest;
import com.monada.storage.audit.StorageIntegrityAuditor;
import com.monada.storage.audit.StorageIntegrityReport;
import com.monada.storage.audit.StorageIntegritySeverity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.stream.Stream;

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
        /** The Store held atoms that are not chunks of this set; nothing was appended and the run fails. */
        FOREIGN,
        /**
         * The existing Store was not structurally {@code HEALTHY} (for example an orphan vector left by an import
         * interrupted between vector and atom append) or its atom log ends without a line feed (a record whose
         * terminator was lost); nothing was appended and the run fails. Seed a new work directory instead:
         * appending would duplicate vector index entries or concatenate onto the unterminated record.
         */
        DAMAGED
    }

    /** The structural audit, reduced to deterministic fields (no paths). */
    public record Audit(String status, long info, long warnings, long errors, long fatal) {
        /** The auditor itself threw on data it could not handle: reported as one fatal finding. */
        static Audit failed(RuntimeException e) {
            return new Audit("AUDIT FAILED (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", 0, 0, 0,
                    1);
        }

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
     * @param selfRecallHits     unique chunks whose own text recalls their atom at rank 1 (exploratory); empty when
     *                           recall was skipped because the inventory is incomplete or the audit not HEALTHY
     * @param inventoryError     why the atom inventory could not be read (the audit report says more), or null
     */
    public record Verification(BookChunkFiles.Loaded chunks, int attemptedChunks, int duplicateContentChunks,
                               int expectedUniqueAtoms, BookStoreInventory inventory, int exactReadback,
                               int resolvedChunks, List<String> failedHashes, int unexpectedAtoms, Audit audit,
                               boolean storeUnchanged, OptionalInt selfRecallHits,
                               String inventoryError) {
        public Verification {
            failedHashes = List.copyOf(failedHashes);
        }

        public boolean passed() {
            return inventoryError == null && failedHashes.isEmpty() && exactReadback == expectedUniqueAtoms
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
            return importKind != ImportKind.FOREIGN && importKind != ImportKind.DAMAGED
                    && (importKind != ImportKind.REPEAT || storeBytesUnchanged) && verification.passed();
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

        if (Files.exists(store, LinkOption.NOFOLLOW_LINKS) && damaged(store)) {
            // Never append to a damaged Store: an orphan vector from an interrupted import would get a duplicate, and
            // a new record would be concatenated onto an unterminated one.
            BookStoreInventory before;
            try {
                before = BookStoreInventory.read(store);
            } catch (IOException e) {
                before = BookStoreInventory.empty();
            }
            return new Seed(ImportKind.DAMAGED, before, before, true, verify(chunks, store));
        }
        BookStoreInventory before = BookStoreInventory.read(store);
        Map<String, String> expected = expectedContents(chunks);
        if (!before.contents().entrySet().stream()
                .allMatch(atom -> atom.getValue().equals(expected.get(atom.getKey())))) {
            // An unknown id, or an expected id holding other content: rememberAll would skip it as already present.
            // Never append to a Store that holds anything but these chunks: reject it untouched.
            return new Seed(ImportKind.FOREIGN, before, before, true, verify(chunks, store));
        }
        String digestBefore = BookStoreInventory.treeDigest(store);
        // MonadaMemory has no close(): the seeding instance is dropped here and everything below reads fresh instances.
        MonadaMemory.open(store).rememberAll(chunks.entries().stream().map(BookChunkFiles.Entry::text).toList());
        String digestAfter = BookStoreInventory.treeDigest(store);
        BookStoreInventory after = BookStoreInventory.read(store);

        ImportKind kind;
        if (before.uniqueAtoms() == 0) {
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
        // Audit first: it tolerates corruption, so a malformed manifest or atom log still yields a report, and its
        // parsed manifest is the read-only way to find the atom segment.
        Audited audited = audit(store);
        Audit audit = audited.audit();
        BookStoreInventory inventory;
        String inventoryError = null;
        try {
            inventory = BookStoreInventory.read(store, audited.manifest());
        } catch (IOException e) {
            inventory = BookStoreInventory.empty();
            inventoryError = e.getMessage();
        }

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

        OptionalInt recallHits = OptionalInt.empty();
        if (Objects.equals(inventory.contents().keySet(), expected.keySet()) && !expected.isEmpty()
                && audit.healthy()) {
            try {
                recallHits = OptionalInt.of(selfRecallOnCopy(store, expected));
            } catch (RuntimeException e) {
                // Recall is an optional diagnostic (e.g. a non-default encoding profile cannot be opened with the
                // default options): it is reported as skipped and never aborts the readback verification.
                recallHits = OptionalInt.empty();
            }
        }
        boolean unchanged = digestBefore.equals(BookStoreInventory.treeDigest(store));

        int duplicates = (int) chunks.entries().stream().filter(e -> e.duplicateOf() != null).count();
        return new Verification(chunks, chunks.entries().size(), duplicates, expected.size(), inventory, exact,
                resolved, new ArrayList<>(failed), unexpected, audit, unchanged, recallHits, inventoryError);
    }

    /** An audit plus the manifest it parsed (null when unreadable or the audit failed). */
    private record Audited(Audit audit, Manifest manifest) {
    }

    /** Runs the read-only auditor; an exception from the auditor itself becomes a failed audit, not a crash. */
    private static Audited audit(Path store) {
        try {
            StorageIntegrityReport report = new StorageIntegrityAuditor().audit(store);
            return new Audited(Audit.of(report), report.manifest());
        } catch (RuntimeException e) {
            return new Audited(Audit.failed(e), null);
        }
    }

    /**
     * Whether an existing Store must not be appended to: not HEALTHY, or one of the line-oriented logs an import
     * appends to (the atom segment and the vector index) ends mid-record, since the next append would be
     * concatenated onto it.
     */
    private static boolean damaged(Path store) throws IOException {
        Audited audited = audit(store);
        if (!audited.audit().healthy() || audited.manifest() == null) {
            return true;
        }
        return unterminated(store.resolve(audited.manifest().atomSegment()))
                || unterminated(FileFrequencyStore.resolveVectorMapPath(store));
    }

    /** A non-empty file whose last byte is not a line feed; an absent file is not unterminated. */
    private static boolean unterminated(Path file) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() == 0) {
                return false;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.read(last, channel.size() - 1);
            return last.get(0) != '\n';
        }
    }

    /**
     * Exploratory self-recall@1, run on a throwaway copy of the Store: opening {@code MonadaMemory} may create
     * missing directories or segment files, and verification must never change the Store it checks.
     */
    private static int selfRecallOnCopy(Path store, Map<String, String> expected) throws IOException {
        Path copy = Files.createTempDirectory("monada-book-recall-");
        try {
            try (Stream<Path> walk = Files.walk(store)) {
                for (Path source : walk.filter(p -> !p.equals(store)).toList()) {
                    Files.copy(source, copy.resolve(store.relativize(source).toString()),
                            StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
            }
            MonadaMemory reopened = MonadaMemory.open(copy);
            int hits = 0;
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                MonadaRecall recall = reopened.resonate(entry.getValue()).topK(1).execute();
                if (!recall.results().isEmpty() && recall.results().getFirst().atom().id().equals(entry.getKey())) {
                    hits++;
                }
            }
            return hits;
        } finally {
            try (Stream<Path> walk = Files.walk(copy)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
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
