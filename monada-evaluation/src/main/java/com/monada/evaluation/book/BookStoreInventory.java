package com.monada.evaluation.book;

import com.monada.core.KnowledgeAtom;
import com.monada.storage.FileAtomStore;
import com.monada.storage.Manifest;
import com.monada.storage.audit.StorageIntegrityAuditor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * An exhaustive, exact-content inventory of the atoms persisted in a Store, read straight from disk through the
 * published lower-level storage API (the manifest as parsed by the read-only {@link StorageIntegrityAuditor},
 * then {@link FileAtomStore#findAll()}, last-wins per id). It never goes through {@code MonadaMemory}, so no in-memory
 * cache of the seeding instance can stand in for the persisted bytes.
 *
 * <p>Reading never creates anything: {@code FileManifestStore.load()} recreates missing directories, so it is not
 * used, and {@link FileAtomStore} is constructed only once its segment exists. {@link #treeDigest(Path)} lets callers prove it.
 *
 * @param contents         atom id to exact original content, ordered by id
 * @param physicalRecords  atom log records parsed (history duplicates included)
 * @param fingerprint      SHA-256 over {@code id \t sha256(content) \n} in id order: equal fingerprints mean equal
 *                         persisted id-to-content mappings
 */
public record BookStoreInventory(Map<String, String> contents, long physicalRecords, String fingerprint) {

    public BookStoreInventory {
        contents = Collections.unmodifiableMap(new TreeMap<>(contents));
    }

    /** The inventory of a Store that does not exist yet. */
    public static BookStoreInventory empty() {
        return of(Map.of(), 0);
    }

    /**
     * Reads every atom of the existing Store at {@code storeRoot}; an absent Store directory is {@link #empty()}.
     * The manifest comes from the read-only {@link StorageIntegrityAuditor}, because
     * {@code FileManifestStore.load()} recreates missing standard directories.
     */
    public static BookStoreInventory read(Path storeRoot) throws IOException {
        if (Files.notExists(storeRoot, LinkOption.NOFOLLOW_LINKS)) {
            return empty();
        }
        Manifest manifest;
        try {
            manifest = new StorageIntegrityAuditor().audit(storeRoot).manifest();
        } catch (RuntimeException e) {
            throw new IOException("store could not be audited: " + e.getMessage(), e);
        }
        return read(storeRoot, manifest);
    }

    /**
     * Reads every atom of the Store at {@code storeRoot} through the atom segment of {@code manifest} (as parsed by
     * a read-only audit). A missing manifest or segment, or an unparseable atom log, is an {@link IOException}.
     */
    public static BookStoreInventory read(Path storeRoot, Manifest manifest) throws IOException {
        if (manifest == null) {
            throw new IOException("store has no readable manifest.json");
        }
        Path root = storeRoot.toAbsolutePath().normalize();
        Path segment = root.resolve(manifest.atomSegment()).normalize();
        if (!segment.startsWith(root) || !Files.isRegularFile(segment, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("atom segment is missing or outside the Store: " + manifest.atomSegment());
        }
        try {
            // The segment and its parent exist, so constructing the store creates nothing.
            FileAtomStore atoms = new FileAtomStore(storeRoot, manifest.atomSegment());
            List<KnowledgeAtom> all = atoms.findAll();
            Map<String, String> contents = new TreeMap<>();
            for (KnowledgeAtom atom : all) {
                contents.put(atom.id(), atom.content());
            }
            return of(contents, atoms.parsedRecordCount());
        } catch (RuntimeException e) {
            throw new IOException("atom log is unreadable: " + e.getMessage(), e);
        }
    }

    private static BookStoreInventory of(Map<String, String> contents, long physicalRecords) {
        StringBuilder lines = new StringBuilder();
        new TreeMap<>(contents).forEach((id, content) -> lines.append(id).append('\t')
                .append(VerifiedBookSource.sha256(content.getBytes(StandardCharsets.UTF_8))).append('\n'));
        return new BookStoreInventory(contents, physicalRecords,
                VerifiedBookSource.sha256(lines.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public int uniqueAtoms() {
        return contents.size();
    }

    /**
     * SHA-256 over the relative name, kind and bytes of every entry below {@code dir} in name order, directories
     * included; links are never followed and contribute their target. An absent directory digests as
     * {@code "absent"}. Equal digests before and after an operation mean it wrote nothing there.
     */
    public static String treeDigest(Path dir) throws IOException {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return "absent";
        }
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(dir)) {
            entries = walk.sorted().toList();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path entry : entries) {
                digest.update(dir.relativize(entry).toString().getBytes(StandardCharsets.UTF_8));
                if (Files.isSymbolicLink(entry)) {
                    digest.update(("\0L" + Files.readSymbolicLink(entry)).getBytes(StandardCharsets.UTF_8));
                } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    digest.update("\0D".getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] bytes = Files.readAllBytes(entry);
                    digest.update(("\0F" + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                    digest.update(bytes);
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
