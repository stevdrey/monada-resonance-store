package com.monada.evaluation.book;

import com.monada.storage.FileFrequencyStore;
import com.monada.storage.Manifest;
import com.monada.storage.audit.StorageIntegrityAuditor;
import com.monada.storage.audit.StorageIntegrityReport;
import com.monada.storage.audit.StorageIntegrityStatistics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What one Store holds at one moment, read only from disk (Issue #169): the structural audit's logical counts plus the
 * size of every file, attributed to its role (manifest, atom segment, vector segment, vector index, feedback segment)
 * and every other regular file listed by its relative path, so no byte is unaccounted for.
 *
 * <p>Taking a snapshot never opens {@code MonadaMemory} and never creates anything: the auditor is read-only, sizes
 * come from file attributes, and {@link BookStoreInventory#treeDigest(Path)} is how callers prove nothing changed.
 *
 * @param audit       the auditor's status line and severity counts
 * @param statistics  the auditor's logical counts ({@link StorageIntegrityStatistics#empty()} for an absent Store)
 * @param dimensions  manifest vector dimensions, or 0 when there is no readable manifest
 * @param frameBytes  bytes one vector frame occupies per the manifest's physical format, or 0 when unknown
 * @param files       bytes per role, in role order; {@link #ABSENT} when that file does not exist
 * @param other       bytes of every other regular file, by relative path (sorted)
 * @param totalBytes  bytes of every regular file below the Store root
 * @param treeDigest  {@link BookStoreInventory#treeDigest(Path)} of the Store root
 */
record BookFootprintSnapshot(BookStoreReadback.Audit audit, StorageIntegrityStatistics statistics, int dimensions,
                             long frameBytes, Map<Role, Long> files, Map<String, Long> other, long totalBytes,
                             String treeDigest) {
    static final long ABSENT = -1;

    /** The file roles a Store is made of. */
    enum Role {
        MANIFEST("manifest"),
        ATOMS("atoms"),
        VECTORS("vectors"),
        VECTOR_INDEX("vectorIndex"),
        FEEDBACK("feedback");

        final String key;

        Role(String key) {
            this.key = key;
        }
    }

    BookFootprintSnapshot {
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        other = Collections.unmodifiableMap(new TreeMap<>(other));
    }

    /** Snapshots the Store at {@code store}; an absent Store is an empty snapshot with every file {@link #ABSENT}. */
    static BookFootprintSnapshot take(Path store) throws IOException {
        Map<Role, Long> files = new LinkedHashMap<>();
        if (Files.notExists(store, LinkOption.NOFOLLOW_LINKS)) {
            for (Role role : Role.values()) {
                files.put(role, ABSENT);
            }
            return new BookFootprintSnapshot(new BookStoreReadback.Audit("ABSENT", 0, 0, 0, 0),
                    StorageIntegrityStatistics.empty(), 0, 0, files, Map.of(), 0, "absent");
        }
        String digestBefore = BookStoreInventory.treeDigest(store);
        BookStoreReadback.Audit audit;
        StorageIntegrityStatistics statistics;
        Manifest manifest;
        try {
            StorageIntegrityReport report = new StorageIntegrityAuditor().audit(store);
            audit = BookStoreReadback.Audit.of(report);
            statistics = report.statistics();
            manifest = report.manifest();
        } catch (RuntimeException e) {
            audit = BookStoreReadback.Audit.failed(e.getClass().getSimpleName() + ": "
                    + BookStoreReadback.withoutPaths(e.getMessage(), store));
            statistics = StorageIntegrityStatistics.empty();
            manifest = null;
        }

        Path root = store.toAbsolutePath().normalize();
        Map<Role, Path> rolePaths = new LinkedHashMap<>();
        rolePaths.put(Role.MANIFEST, root.resolve("manifest.json"));
        rolePaths.put(Role.ATOMS, manifest == null ? null : root.resolve(manifest.atomSegment()).normalize());
        rolePaths.put(Role.VECTORS, manifest == null ? null : root.resolve(manifest.vectorSegment()).normalize());
        rolePaths.put(Role.VECTOR_INDEX, FileFrequencyStore.resolveVectorMapPath(root));
        rolePaths.put(Role.FEEDBACK, manifest == null ? null : root.resolve(manifest.feedbackSegment()).normalize());

        Map<String, Long> other = new TreeMap<>();
        long total = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted().toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                long size = Files.size(path);
                total += size;
                if (!rolePaths.containsValue(path)) {
                    other.put(root.relativize(path).toString().replace('\\', '/'), size);
                }
            }
        }
        for (Map.Entry<Role, Path> role : rolePaths.entrySet()) {
            Path path = role.getValue();
            boolean present = path != null && path.startsWith(root)
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
            files.put(role.getKey(), present ? Files.size(path) : ABSENT);
        }
        int dimensions = manifest == null ? 0 : manifest.dimensions();
        long frame = manifest == null ? 0 : manifest.vectorFormatProfile() == null ? 4L * dimensions
                : manifest.vectorFormatProfile().frameSizeInBytes();
        String digestAfter = BookStoreInventory.treeDigest(store);
        if (!digestBefore.equals(digestAfter)) {
            // The snapshot itself must be read-only; a concurrent writer would make its numbers meaningless.
            throw new IllegalStateException("the Store changed while it was being snapshotted");
        }
        return new BookFootprintSnapshot(audit, statistics, dimensions, frame, files, other, total, digestAfter);
    }

    /** Bytes of {@code role}, 0 when absent. */
    long bytes(Role role) {
        return Math.max(0, files.get(role));
    }

    /** Sum of every file that is not a named role. */
    long otherBytes() {
        return other.values().stream().mapToLong(Long::longValue).sum();
    }
}
