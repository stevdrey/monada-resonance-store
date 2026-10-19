package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentDirectoryGuardTest {
    @TempDir
    Path dir;

    @Test
    void absentDirectoryIsCreatedWithItsMarkerOnly() throws Exception {
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        try (var children = Files.list(work)) {
            assertEquals(List.of(work.resolve(ExperimentDirectoryGuard.MARKER_FILE)), children.toList());
        }
        assertEquals(work, ExperimentDirectoryGuard.prepareForSeed(work, List.of()), "a marked directory is reusable");
    }

    @Test
    void missingParentIsNotCreated() {
        Path work = dir.resolve("missing/work");
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(work, List.of()));
        assertFalse(Files.exists(dir.resolve("missing")));
    }

    @Test
    void existingDirectoryWithoutMarkerIsRefusedUntouched() throws Exception {
        Path work = Files.createDirectory(dir.resolve("home-like"));
        Files.writeString(work.resolve("notes.txt"), "keep me", StandardCharsets.UTF_8);
        String digest = BookStoreInventory.treeDigest(work);

        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(work, List.of()));
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(Files.createDirectory(dir.resolve("empty")), List.of()));
        assertEquals(digest, BookStoreInventory.treeDigest(work));
    }

    @Test
    void markedDirectoryWithUnexpectedEntriesIsRefused() throws Exception {
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        Files.createDirectory(work.resolve(".git"));
        var e = assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(work, List.of()));
        assertTrue(e.getMessage().contains(".git"), e.getMessage());
    }

    @Test
    void symbolicLinksAreRefused() throws Exception {
        Path real = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("real"), List.of());
        Path link = Files.createSymbolicLink(dir.resolve("link"), real);
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(link, List.of()));

        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createSymbolicLink(real.resolve(ExperimentDirectoryGuard.STORE_DIR), elsewhere);
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(real, List.of()));
    }

    @Test
    void symbolicLinkBelowTheStoreIsRefused() throws Exception {
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        Path store = Files.createDirectory(work.resolve(ExperimentDirectoryGuard.STORE_DIR));
        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createSymbolicLink(store.resolve("atoms"), elsewhere);
        var e = assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(work, List.of()));
        assertTrue(e.getMessage().contains("atoms"), e.getMessage());
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.requireSeeded(work));
    }

    @Test
    void hardLinkedFileBelowTheStoreIsRefused() throws Exception {
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        Path atoms = Files.createDirectories(work.resolve(ExperimentDirectoryGuard.STORE_DIR).resolve("atoms"));
        Path outside = Files.writeString(dir.resolve("other-store-segment.log"), "", StandardCharsets.UTF_8);
        try {
            Files.createLink(atoms.resolve("segment-000001.log"), outside);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("hard links are not supported here");
        }
        var e = assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(work, List.of()));
        assertTrue(e.getMessage().contains("hard-linked"), e.getMessage());
    }

    @Test
    void specialFileBelowTheStoreIsRefused() throws Exception {
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        Path store = Files.createDirectory(work.resolve(ExperimentDirectoryGuard.STORE_DIR));
        Path fifo = store.resolve("unused.pipe");
        Process mkfifo;
        try {
            mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
        } catch (IOException e) {
            Assumptions.abort("mkfifo is not available here");
            return;
        }
        Assumptions.assumeTrue(mkfifo.waitFor() == 0 && Files.exists(fifo), "mkfifo failed");
        var e = assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.requireSeeded(work));
        assertTrue(e.getMessage().contains("special file"), e.getMessage());
    }

    @Test
    void inputInsideTheWorkDirectoryIsRefused() throws Exception {
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        Path chunks = Files.createDirectory(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR));
        Path input = Files.writeString(chunks.resolve("body.txt"), "body", StandardCharsets.UTF_8);
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.prepareForSeed(work, List.of(input)));
    }

    @Test
    void verificationNeedsASeededDirectory() throws Exception {
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.requireSeeded(work));
        assertThrows(ExperimentDirectoryGuard.UnsafeDirectoryException.class,
                () -> ExperimentDirectoryGuard.requireSeeded(dir.resolve("absent")));
        assertFalse(Files.exists(dir.resolve("absent")));
    }
}
