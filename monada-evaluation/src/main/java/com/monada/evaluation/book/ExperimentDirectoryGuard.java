package com.monada.evaluation.book;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Fails closed unless a user-selected work directory is a disposable directory dedicated to the book Store readback
 * experiment, so the runner never writes into an arbitrary path (a home directory, a repository, a real Store).
 *
 * <p>A work directory is accepted when it is either absent (its parent must already exist; only the directory itself
 * is created, together with the {@value #MARKER_FILE} marker) or an existing directory that already carries the
 * marker and holds nothing but the marker and the experiment children {@value #STORE_DIR} and {@value #CHUNKS_DIR}.
 * Symbolic links and hard-linked files are refused anywhere the runner writes to (the work directory and
 * everything below it), and no input file may live inside the work
 * directory. Every check runs before anything is written.
 */
public final class ExperimentDirectoryGuard {
    public static final String MARKER_FILE = ".monada-book-store-experiment";
    public static final String STORE_DIR = "store";
    public static final String CHUNKS_DIR = "chunks";
    static final String MARKER_CONTENT = "monada-book-store-experiment v1\n";
    private static final Set<String> EXPECTED_CHILDREN = Set.of(MARKER_FILE, STORE_DIR, CHUNKS_DIR);

    private ExperimentDirectoryGuard() {
    }

    /** The work directory is not a disposable experiment directory; nothing was written. */
    public static final class UnsafeDirectoryException extends Exception {
        UnsafeDirectoryException(String message) {
            super(message);
        }
    }

    /**
     * Accepts {@code work} for seeding, creating it with its marker when absent. {@code inputs} are files the run
     * reads (the body and the manifest); none of them may live inside {@code work}.
     */
    public static Path prepareForSeed(Path work, List<Path> inputs) throws UnsafeDirectoryException, IOException {
        Path absolute = work.toAbsolutePath().normalize();
        if (Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = absolute.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new UnsafeDirectoryException("work directory parent does not exist: " + parent
                        + " (create it first; only the work directory itself is created)");
            }
            requireNoInputInside(absolute, inputs);
            Files.createDirectory(absolute);
            Files.writeString(absolute.resolve(MARKER_FILE), MARKER_CONTENT, StandardCharsets.UTF_8);
            return absolute;
        }
        requireDedicated(absolute);
        requireNoInputInside(absolute, inputs);
        return absolute;
    }

    /** Accepts an existing, already seeded work directory for read-only verification. */
    public static Path requireSeeded(Path work) throws UnsafeDirectoryException, IOException {
        Path absolute = work.toAbsolutePath().normalize();
        if (Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafeDirectoryException("work directory does not exist: " + absolute);
        }
        requireDedicated(absolute);
        for (String child : List.of(STORE_DIR, CHUNKS_DIR)) {
            if (!Files.isDirectory(absolute.resolve(child), LinkOption.NOFOLLOW_LINKS)) {
                throw new UnsafeDirectoryException("work directory has not been seeded (missing " + child + "/): "
                        + absolute);
            }
        }
        return absolute;
    }

    private static void requireDedicated(Path work) throws UnsafeDirectoryException, IOException {
        if (Files.isSymbolicLink(work)) {
            throw new UnsafeDirectoryException("work directory is a symbolic link: " + work);
        }
        if (!Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafeDirectoryException("work path is not a directory: " + work);
        }
        Path marker = work.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker, StandardCharsets.UTF_8).equals(MARKER_CONTENT)) {
            throw new UnsafeDirectoryException("refusing a work directory that is not a dedicated experiment"
                    + " directory (no " + MARKER_FILE + " marker): " + work
                    + " (pass a path that does not exist yet; it is created with the marker)");
        }
        Set<String> unexpected = new TreeSet<>();
        try (Stream<Path> children = Files.list(work)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                String name = child.getFileName().toString();
                if (!EXPECTED_CHILDREN.contains(name)) {
                    unexpected.add(name);
                } else if (Files.isSymbolicLink(child)) {
                    throw new UnsafeDirectoryException("work directory entry is a symbolic link: " + child);
                } else if (!name.equals(MARKER_FILE) && !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UnsafeDirectoryException("work directory entry is not a directory: " + child);
                }
            }
        }
        if (!unexpected.isEmpty()) {
            throw new UnsafeDirectoryException("work directory holds unexpected entries " + unexpected + ": " + work);
        }
        // Writes land below store/ and chunks/, so a link anywhere beneath them could redirect a write outside work.
        for (String child : List.of(STORE_DIR, CHUNKS_DIR)) {
            Path dir = work.resolve(child);
            if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            List<Path> entries;
            try (Stream<Path> walk = Files.walk(dir)) {
                entries = walk.toList();
            }
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw new UnsafeDirectoryException("work directory contains a symbolic link: " + entry);
                }
                // A hard link shares its inode with a file elsewhere, so appending through it writes outside work.
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && linkCount(entry) > 1) {
                    throw new UnsafeDirectoryException("work directory contains a hard-linked file: " + entry);
                }
            }
        }
    }

    /**
     * The file's hard link count. Fails closed where the file system does not report one (for example NTFS through
     * the default Windows provider), since a hard link could not be ruled out; a fresh work directory still works.
     */
    private static int linkCount(Path file) throws IOException, UnsafeDirectoryException {
        try {
            return (Integer) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new UnsafeDirectoryException("cannot determine the hard-link count of " + file
                    + " on this file system; pass a work directory that does not exist yet");
        }
    }

    private static void requireNoInputInside(Path work, List<Path> inputs) throws UnsafeDirectoryException,
            IOException {
        Path realWork = Files.exists(work) ? work.toRealPath() : work;
        for (Path input : inputs) {
            Path absolute = input.toAbsolutePath().normalize();
            Path real = Files.exists(absolute) ? absolute.toRealPath() : absolute;
            if (absolute.startsWith(work) || real.startsWith(realWork)) {
                throw new UnsafeDirectoryException("input file lives inside the work directory: " + input);
            }
        }
    }
}
