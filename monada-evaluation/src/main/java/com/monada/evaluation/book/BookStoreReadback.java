package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
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
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
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
 *   (the audited manifest + {@code FileAtomStore.findAll}), on fresh instances, never the seeding instance;</li>
 *   <li><b>structure</b>: the read-only {@link StorageIntegrityAuditor};</li>
 *   <li><b>semantic</b>: public {@link MonadaMemory#resonate(String)} self-recall@1, reported as exploratory and
 *   never counted as readback evidence (top-K ranking does not prove full-source readback).</li>
 * </ul>
 * Chunk keys and provenance stay in the external sidecar: the Store does not persist atom metadata (ADR 0004), so
 * the chunk-to-atom mapping is the content-derived id {@code KnowledgeAtom.text(text).id()}.
 */
public final class BookStoreReadback {

    /** The manifest version {@code MonadaMemory.open} writes for a new Store (not exported by the API). */
    private static final String CURRENT_MANIFEST_VERSION = "0.4";

    /** Serializes seed/verify lock holders within this JVM; a file lock is per JVM, so threads queue here first. */
    private static final Object LOCK_MONITOR = new Object();

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
        /**
         * The Store held atoms that are not chunks of this set, or the work directory was seeded from a different
         * chunk set; nothing (chunk files included) was written and the run fails.
         */
        FOREIGN,
        /**
         * The existing Store was not structurally {@code HEALTHY} (for example an orphan vector left by an import
         * interrupted between vector and atom append) or its atom log ends without a line feed (a record whose
         * terminator was lost); nothing was appended and the run fails. Seed a new work directory instead:
         * appending would duplicate vector index entries or concatenate onto the unterminated record.
         */
        DAMAGED,
        /**
         * The existing Store was written with another manifest version or encoding profile than the default options
         * the runner opens it with; nothing was opened or appended and the run fails.
         */
        INCOMPATIBLE
    }

    /** The structural audit, reduced to deterministic fields (no paths). */
    public record Audit(String status, long info, long warnings, long errors, long fatal) {
        /** The auditor itself threw on data it could not handle: reported as one fatal finding. */
        static Audit failed(String message) {
            return new Audit("AUDIT FAILED (" + message + ")", 0, 0, 0, 1);
        }

        static Audit of(StorageIntegrityReport report) {
            return new Audit(statusLine(report), report.countBySeverity(StorageIntegritySeverity.INFO),
                    report.countBySeverity(StorageIntegritySeverity.WARNING),
                    report.countBySeverity(StorageIntegritySeverity.ERROR),
                    report.countBySeverity(StorageIntegritySeverity.FATAL));
        }

        /**
         * The status exactly as {@link StorageIntegrityReport#render()} prints it, so the severity-to-status mapping
         * has a single source (the report exposes no status accessor, and monada-storage is out of scope here).
         */
        private static String statusLine(StorageIntegrityReport report) {
            for (String line : report.render().split("\\R")) {
                if (line.startsWith("Status:")) {
                    return line.substring("Status:".length()).trim();
                }
            }
            throw new IllegalStateException("integrity report has no Status line");
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
     * @param logsTerminated     the atom segment and the vector index each end with a line feed (the auditor accepts
     *                           an unterminated last record, but it marks an interrupted write)
     */
    public record Verification(BookChunkFiles.Loaded chunks, int attemptedChunks, int duplicateContentChunks,
                               int expectedUniqueAtoms, BookStoreInventory inventory, int exactReadback,
                               int resolvedChunks, List<String> failedHashes, int unexpectedAtoms, Audit audit,
                               boolean storeUnchanged, OptionalInt selfRecallHits,
                               String inventoryError, boolean logsTerminated) {
        public Verification {
            failedHashes = List.copyOf(failedHashes);
        }

        public boolean passed() {
            return inventoryError == null && logsTerminated && failedHashes.isEmpty()
                    && exactReadback == expectedUniqueAtoms && resolvedChunks == attemptedChunks
                    && inventory.uniqueAtoms() == expectedUniqueAtoms && unexpectedAtoms == 0 && audit.healthy()
                    && storeUnchanged;
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
                    && importKind != ImportKind.INCOMPATIBLE
                    && (importKind != ImportKind.REPEAT || storeBytesUnchanged) && verification.passed();
        }
    }

    /**
     * Checks the existing Store and chunk files against {@code result} first; only when the import is accepted does it
     * write the chunk files into {@code work/chunks} (outside the Store, and only if they are not already identical),
     * reload them from disk, remember every chunk into {@code work/store} through the public API and verify the Store
     * on fresh instances. A rejected import leaves both {@code chunks/} and {@code store/} byte for byte untouched.
     * {@code work} must already have passed {@link ExperimentDirectoryGuard#prepareForSeed}.
     *
     * <p>Seeds of one work directory are serialized: threads of one JVM on a monitor, processes by an exclusive lock
     * on {@value ExperimentDirectoryGuard#LOCK_FILE}, held from the first check to the end of verification.
     */
    public static Seed seed(BookChunker.Result result, Path work) throws IOException, BookInputException {
        synchronized (LOCK_MONITOR) {
            // CREATE without TRUNCATE_EXISTING: opening the lock never changes an existing file's bytes.
            try (FileChannel lock = FileChannel.open(work.resolve(ExperimentDirectoryGuard.LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lock.lock()) {
                return seedLocked(result, work);
            }
        }
    }

    private static Seed seedLocked(BookChunker.Result result, Path work) throws IOException, BookInputException {
        Path chunkDir = work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR);
        Path store = work.resolve(ExperimentDirectoryGuard.STORE_DIR);
        BookChunkOutputWriter.Output rendered = BookChunkOutputWriter.render(result);
        if (chunkFilesDiffer(chunkDir, rendered)) {
            // The work directory was seeded from another chunk set: keep its chunk files (the Store's provenance)
            // and its Store untouched, and verify the Store against the chunk files it was seeded from.
            BookStoreInventory before = readQuietly(store, audit(store).manifest());
            return new Seed(ImportKind.FOREIGN, before, before, true,
                    verify(BookChunkFiles.load(chunkDir), store, null));
        }
        // Nothing under chunks/ or store/ is written until the import is accepted: an identical chunk pair on disk is
        // used as is, otherwise the rendered pair is checked in memory and only written once every check passed.
        boolean chunkFilesCurrent = chunkFilesPresent(chunkDir);
        BookChunkFiles.Loaded chunks = chunkFilesCurrent ? BookChunkFiles.load(chunkDir)
                : BookChunkFiles.parse(rendered.chunks(), rendered.sidecar());

        BookStoreInventory before = BookStoreInventory.empty();
        if (Files.exists(store, LinkOption.NOFOLLOW_LINKS)) {
            Audited pre = audit(store);
            ImportKind refused = refusal(store, pre);
            if (refused != null) {
                // Never open or append to a damaged or incompatible Store: an orphan vector from an interrupted
                // import would get a duplicate, a new record would be concatenated onto an unterminated one, and
                // opening with a mismatched profile throws after FileManifestStore may have recreated directories.
                BookStoreInventory existing = readQuietly(store, pre.manifest());
                return new Seed(refused, existing, existing, true, verify(chunks, store, null));
            }
            before = BookStoreInventory.read(store, pre.manifest());
        }
        Map<String, String> expected = expectedContents(chunks);
        if (!before.contents().entrySet().stream()
                .allMatch(atom -> atom.getValue().equals(expected.get(atom.getKey())))) {
            // An unknown id, or an expected id holding other content: rememberAll would skip it as already present.
            // Never append to a Store that holds anything but these chunks: reject it untouched.
            return new Seed(ImportKind.FOREIGN, before, before, true, verify(chunks, store, null));
        }
        if (!chunkFilesCurrent) {
            // Accepted: persist the canonical chunk files first and seed from what is on disk.
            BookChunkOutputWriter.write(result, chunkDir);
            chunks = BookChunkFiles.load(chunkDir);
        }
        String digestBefore = BookStoreInventory.treeDigest(store);
        // MonadaMemory has no close(): the seeding instance is dropped here and everything below reads fresh instances.
        MonadaMemory.open(store).rememberAll(chunks.entries().stream().map(BookChunkFiles.Entry::text).toList());
        String digestAfter = BookStoreInventory.treeDigest(store);
        // The verification audits and inventories the Store on fresh instances; its inventory is the "after" state.
        Verification verification = verify(chunks, store, null);
        BookStoreInventory after = verification.inventory();

        ImportKind kind;
        if (before.uniqueAtoms() == 0) {
            kind = ImportKind.CLEAN;
        } else if (before.fingerprint().equals(after.fingerprint())) {
            kind = ImportKind.REPEAT;
        } else {
            kind = ImportKind.RESUMED;
        }
        return new Seed(kind, before, after, digestBefore.equals(digestAfter), verification);
    }

    /**
     * Whether {@code chunkDir} already holds a complete chunk file pair that differs from {@code rendered}. A missing
     * sidecar means no complete pair (the writer removes it first), so it can be rewritten.
     */
    private static boolean chunkFilesDiffer(Path chunkDir, BookChunkOutputWriter.Output rendered) throws IOException {
        Path chunksFile = chunkDir.resolve(BookChunkOutputWriter.CHUNKS_FILE);
        Path sidecarFile = chunkDir.resolve(BookChunkOutputWriter.SIDECAR_FILE);
        if (Files.notExists(sidecarFile, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return Files.notExists(chunksFile, LinkOption.NOFOLLOW_LINKS)
                || !Arrays.equals(Files.readAllBytes(chunksFile), rendered.chunks())
                || !Arrays.equals(Files.readAllBytes(sidecarFile), rendered.sidecar());
    }

    /** Whether {@code chunkDir} holds a complete chunk pair (the writer removes the sidecar first). */
    private static boolean chunkFilesPresent(Path chunkDir) {
        return Files.isRegularFile(chunkDir.resolve(BookChunkOutputWriter.CHUNKS_FILE), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(chunkDir.resolve(BookChunkOutputWriter.SIDECAR_FILE), LinkOption.NOFOLLOW_LINKS);
    }

    private static BookStoreInventory readQuietly(Path store, Manifest manifest) {
        if (Files.notExists(store, LinkOption.NOFOLLOW_LINKS)) {
            return BookStoreInventory.empty();
        }
        try {
            return BookStoreInventory.read(store, manifest);
        } catch (IOException e) {
            return BookStoreInventory.empty();
        }
    }

    /**
     * Verifies the Store of an already seeded work directory against its chunk files; writes nothing. When the work
     * directory has a seed lock, a shared lock on it keeps a concurrent seed from changing the Store mid-check.
     */
    public static Verification verify(Path work) throws IOException, BookInputException {
        return verify(work, null);
    }

    /** {@link #verify(Path)} with the temporary root for the recall copy ({@code null}: the default temp dir). */
    static Verification verify(Path work, Path recallTmpRoot) throws IOException, BookInputException {
        Path lockFile = work.resolve(ExperimentDirectoryGuard.LOCK_FILE);
        if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            return verifyWork(work, recallTmpRoot);
        }
        synchronized (LOCK_MONITOR) {
            // A shared lock needs a channel open for reading; READ alone creates and changes nothing.
            try (FileChannel lock = FileChannel.open(lockFile, StandardOpenOption.READ);
                 FileLock ignored = lock.lock(0, Long.MAX_VALUE, true)) {
                return verifyWork(work, recallTmpRoot);
            }
        }
    }

    private static Verification verifyWork(Path work, Path recallTmpRoot) throws IOException, BookInputException {
        return verify(BookChunkFiles.load(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR)),
                work.resolve(ExperimentDirectoryGuard.STORE_DIR), recallTmpRoot);
    }

    private static Verification verify(BookChunkFiles.Loaded chunks, Path store, Path recallTmpRoot)
            throws IOException {
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
            inventoryError = withoutPaths(e.getMessage(), store);
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
                recallHits = OptionalInt.of(selfRecallOnCopy(store, expected, recallTmpRoot));
            } catch (IOException | RuntimeException e) {
                // Recall is an optional diagnostic: a Store with a non-default encoding profile cannot be opened with
                // the default options, and the temporary copy can fail (a full or unwritable tmpdir). Either way it is
                // reported as skipped and never aborts the readback verification.
                recallHits = OptionalInt.empty();
            }
        }
        boolean unchanged = digestBefore.equals(BookStoreInventory.treeDigest(store));

        int duplicates = (int) chunks.entries().stream().filter(e -> e.duplicateOf() != null).count();
        return new Verification(chunks, chunks.entries().size(), duplicates, expected.size(), inventory, exact,
                resolved, new ArrayList<>(failed), unexpected, audit, unchanged, recallHits, inventoryError,
                logsTerminated(store, audited.manifest()));
    }

    /**
     * {@code message} with the Store and work directory paths replaced by {@code <store>} and {@code <work>}, so a
     * failure report stays as deterministic as a passing one.
     */
    static String withoutPaths(String message, Path store) {
        if (message == null) {
            return "no detail";
        }
        Path absolute = store.toAbsolutePath().normalize();
        String result = message.replace(absolute.toString(), "<store>").replace(store.toString(), "<store>");
        Path work = absolute.getParent();
        return work == null ? result : result.replace(work.toString(), "<work>");
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
            return new Audited(Audit.failed(e.getClass().getSimpleName() + ": " + withoutPaths(e.getMessage(), store)),
                    null);
        }
    }

    /**
     * Why an existing Store must not be opened for appending, or null when it may be: {@code DAMAGED} when it is not
     * HEALTHY or a line-oriented log an import appends to (the atom segment, the vector index) ends mid-record;
     * {@code INCOMPATIBLE} when its manifest version or encoding profile differs from what the default options
     * expect.
     */
    private static ImportKind refusal(Path store, Audited audited) {
        Manifest manifest = audited.manifest();
        if (!audited.audit().healthy() || manifest == null || !logsTerminated(store, manifest)) {
            return ImportKind.DAMAGED;
        }
        if (!CURRENT_MANIFEST_VERSION.equals(manifest.version())
                || !Objects.equals(manifest.encodingProfile(),
                        MonadaMemory.getExpectedProfile(manifest.dimensions(), MonadaMemoryOptions.defaults()))) {
            return ImportKind.INCOMPATIBLE;
        }
        return null;
    }

    /**
     * Whether the line-oriented logs an import appends to (atom segment, vector index) end with a line feed. An atom
     * segment that resolves outside the Store fails closed.
     */
    private static boolean logsTerminated(Path store, Manifest manifest) {
        if (manifest != null) {
            Path root = store.toAbsolutePath().normalize();
            Path segment = root.resolve(manifest.atomSegment()).normalize();
            if (!segment.startsWith(root) || unterminated(segment)) {
                return false;
            }
        }
        return !unterminated(FileFrequencyStore.resolveVectorMapPath(store));
    }

    /**
     * A non-empty file whose last byte is not a line feed, or a path that is not a readable regular file; an absent
     * file is not unterminated.
     */
    private static boolean unterminated(Path file) {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return true; // a directory or special file where a log belongs: the audit reports it as corruption
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() == 0) {
                return false;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.read(last, channel.size() - 1);
            return last.get(0) != '\n';
        } catch (IOException e) {
            return true; // unreadable: never certified, and the audit result is still reported
        }
    }

    /**
     * Exploratory self-recall@1, run on a throwaway copy of the Store (under {@code tmpRoot}, or the default temp
     * directory when null): opening {@code MonadaMemory} may create missing directories or segment files, and
     * verification must never change the Store it checks.
     */
    private static int selfRecallOnCopy(Path store, Map<String, String> expected, Path tmpRoot) throws IOException {
        return onCopy(store, tmpRoot, copy -> {
            MonadaMemory reopened = MonadaMemory.open(copy);
            int hits = 0;
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                MonadaRecall recall = reopened.resonate(entry.getValue()).topK(1).execute();
                if (!recall.results().isEmpty() && recall.results().getFirst().atom().id().equals(entry.getKey())) {
                    hits++;
                }
            }
            return hits;
        });
    }

    /** Work done on a throwaway Store copy. */
    @FunctionalInterface
    interface CopyTask<T> {
        T run(Path copy) throws IOException;
    }

    /**
     * Runs {@code task} on a throwaway copy of {@code store} (under {@code tmpRoot}, or the default temp directory
     * when null) and deletes the copy afterwards, so nothing that opens {@code MonadaMemory} changes the Store itself.
     */
    static <T> T onCopy(Path store, Path tmpRoot, CopyTask<T> task) throws IOException {
        String prefix = "monada-book-recall-";
        Path copy = tmpRoot == null ? Files.createTempDirectory(prefix) : Files.createTempDirectory(tmpRoot, prefix);
        try {
            try (Stream<Path> walk = Files.walk(store)) {
                for (Path source : walk.filter(p -> !p.equals(store)).toList()) {
                    Files.copy(source, copy.resolve(store.relativize(source).toString()),
                            StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
            }
            return task.run(copy);
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
