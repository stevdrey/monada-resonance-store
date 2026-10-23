package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.MonadaMemory;
import com.monada.evaluation.book.BookFootprintSnapshot.Role;
import com.monada.storage.FileFrequencyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Read-only disk snapshots of a Store (Issue #169): attribution of every byte and logical counts. */
class BookFootprintSnapshotTest {
    private static final List<String> TEXTS = List.of("The gulls circled the breakwater.",
            "Cafe lights blinked across the water at dusk.", "The tide turns twice a day.",
            "The tide turns twice a day.");

    @TempDir
    Path dir;

    private Path seeded() {
        Path store = dir.resolve("store");
        MonadaMemory.open(store).rememberAll(TEXTS);
        return store;
    }

    @Test
    void anAbsentStoreIsEmptyAndIsNotCreated() throws Exception {
        Path store = dir.resolve("missing");
        BookFootprintSnapshot snapshot = BookFootprintSnapshot.take(store);
        assertEquals("ABSENT", snapshot.audit().status());
        assertEquals(0, snapshot.totalBytes());
        assertTrue(snapshot.files().values().stream().allMatch(v -> v == BookFootprintSnapshot.ABSENT));
        assertFalse(Files.exists(store), "a snapshot never creates the Store");
    }

    @Test
    void everyByteIsAttributedAndCountsAgree() throws Exception {
        Path store = seeded();
        String before = BookStoreInventory.treeDigest(store);
        BookFootprintSnapshot snapshot = BookFootprintSnapshot.take(store);

        assertEquals(before, BookStoreInventory.treeDigest(store), "a snapshot writes nothing");
        assertEquals(before, snapshot.treeDigest());
        assertTrue(snapshot.audit().healthy(), snapshot.audit().status());
        assertEquals(3, snapshot.statistics().activeUniqueAtoms(), "the repeated text is one atom");
        assertEquals(3, snapshot.statistics().atomLogPhysicalRecords());
        assertEquals(3, snapshot.statistics().vectorIndexEntries());
        assertTrue(snapshot.dimensions() > 0);
        assertEquals(3 * snapshot.frameBytes(), snapshot.bytes(Role.VECTORS));
        for (Role role : List.of(Role.MANIFEST, Role.ATOMS, Role.VECTORS, Role.VECTOR_INDEX)) {
            assertTrue(snapshot.files().get(role) > 0, role + " has bytes");
        }
        long attributed = snapshot.files().values().stream().mapToLong(v -> Math.max(0, v)).sum()
                + snapshot.otherBytes();
        assertEquals(snapshot.totalBytes(), attributed, "roles plus other files account for every byte");
        assertEquals(0, snapshot.otherBytes());
    }

    @Test
    void anUnexpectedFileIsListedNotHidden() throws Exception {
        Path store = seeded();
        Files.writeString(store.resolve("indexes").resolve("stray.bin"), "12345", StandardCharsets.UTF_8);
        BookFootprintSnapshot snapshot = BookFootprintSnapshot.take(store);
        assertEquals(5L, snapshot.other().get("indexes/stray.bin"));
        assertEquals(5, snapshot.otherBytes());
    }

    @Test
    void anOrphanVectorEntryShowsInTheCountsAndTheStructuralChecks() throws Exception {
        Path store = seeded();
        BookFootprintSnapshot healthy = BookFootprintSnapshot.take(store);
        Files.writeString(FileFrequencyStore.resolveVectorMapPath(store), "orphan-id\t0" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        BookFootprintSnapshot damaged = BookFootprintSnapshot.take(store);
        assertNotEquals(healthy.treeDigest(), damaged.treeDigest());
        assertEquals(4, damaged.statistics().vectorIndexEntries());

        BookFootprintBenchmark.PhaseResult phase = new BookFootprintBenchmark.PhaseResult("DAMAGED", 0, 0, 0, 0, 3,
                0, 0, damaged, false, null);
        List<BookFootprintBenchmark.Check> checks = new java.util.ArrayList<>();
        BookFootprintBenchmark.structural("damaged", phase, 3, checks);
        List<String> failed = checks.stream().filter(c -> !c.passed()).map(BookFootprintBenchmark.Check::name)
                .toList();
        assertTrue(failed.contains("damaged.vectorIndexEntries"), failed.toString());
        assertTrue(failed.contains("damaged.vectorSegmentBytes"), failed.toString());
    }
}
