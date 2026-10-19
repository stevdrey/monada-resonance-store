package com.monada.evaluation.book;

import com.monada.core.KnowledgeAtom;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileManifestStore;
import com.monada.storage.Manifest;
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
 * published lower-level storage API ({@link FileManifestStore} for the atom segment, then
 * {@link FileAtomStore#findAll()}, last-wins per id). It never goes through {@code MonadaMemory}, so no in-memory
 * cache of the seeding instance can stand in for the persisted bytes.
 *
 * <p>Reading never creates anything: both storage classes create missing directories or files, so the manifest and
 * the atom segment must already exist before either is constructed. {@link #treeDigest(Path)} lets callers prove it.
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

    /** Reads every atom of the existing Store at {@code storeRoot}; an absent Store directory is {@link #empty()}. */
    public static BookStoreInventory read(Path storeRoot) throws IOException {
        if (Files.notExists(storeRoot, LinkOption.NOFOLLOW_LINKS)) {
            return empty();
        }
        if (!Files.isRegularFile(storeRoot.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a Store (no manifest.json): " + storeRoot);
        }
        Manifest manifest = new FileManifestStore(storeRoot).load()
                .orElseThrow(() -> new IOException("manifest.json disappeared: " + storeRoot));
        Path root = storeRoot.toAbsolutePath().normalize();
        Path segment = root.resolve(manifest.atomSegment()).normalize();
        if (!segment.startsWith(root) || !Files.isRegularFile(segment, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("atom segment is missing or outside the Store: " + manifest.atomSegment());
        }
        FileAtomStore atoms = new FileAtomStore(storeRoot, manifest.atomSegment());
        List<KnowledgeAtom> all = atoms.findAll();
        Map<String, String> contents = new TreeMap<>();
        for (KnowledgeAtom atom : all) {
            contents.put(atom.id(), atom.content());
        }
        return of(contents, atoms.parsedRecordCount());
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
