package com.monada.storage;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAtomStoreTest {

    @TempDir
    Path root;

    private KnowledgeAtom atom(String id, String content, Map<String, Object> metadata) {
        return new KnowledgeAtom(id, AtomType.TEXT, content, metadata, 1.0, Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void savesAndReadsBackAtom() throws IOException {
        var store = new FileAtomStore(root);
        var original = atom("a-1", "hello world", Map.of());
        store.save(original);

        var found = store.findById("a-1");
        assertTrue(found.isPresent());
        assertEquals("hello world", found.get().content());
        assertEquals(AtomType.TEXT, found.get().type());
    }

    @Test
    void savesAndReadsBackAliases() throws IOException {
        var store = new FileAtomStore(root);
        var original = new KnowledgeAtom("a-2", AtomType.TEXT, "CQRS",
                List.of("command query responsibility segregation", "read path write path"),
                Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        store.save(original);

        var found = store.findById("a-2").orElseThrow();

        assertEquals(original.aliases(), found.aliases());
        assertEquals("CQRS command query responsibility segregation read path write path",
                found.searchableContent());
    }

    @Test
    void rejectsAtomWithNonEmptyMetadata() throws IOException {
        var store = new FileAtomStore(root);
        var withMetadata = atom("a-2", "x", Map.of("k", "v"));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> store.save(withMetadata));
        assertTrue(ex.getMessage().contains("metadata"));
    }

    @Test
    void parseWrapsLowLevelErrorsWithLineContext() throws IOException {
        var store = new FileAtomStore(root);
        var log = root.resolve("atoms/segment-000001.log");
        // Five fields but weight is not numeric -> NumberFormatException
        Files.writeString(log,
                "id-1\tTEXT\tnot-a-number\t2024-01-01T00:00:00Z\taGVsbG8=" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IllegalStateException ex = assertThrows(IllegalStateException.class, store::findAll);
        assertTrue(ex.getMessage().contains("Corrupt atom log entry"));
        assertTrue(ex.getMessage().contains("not-a-number"));
        assertTrue(ex.getCause() instanceof NumberFormatException);
    }

    @Test
    void parseFailsOnMalformedLineWithDescriptiveError() throws IOException {
        var store = new FileAtomStore(root);
        var log = root.resolve("atoms/segment-000001.log");
        Files.writeString(log, "only-one-field" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IllegalStateException ex = assertThrows(IllegalStateException.class, store::findAll);
        assertTrue(ex.getMessage().contains("Malformed atom log entry"));
        assertTrue(ex.getMessage().contains("expected 5 or 6"));
    }

    @Test
    void parsesLegacyFiveFieldAtomLogEntriesWithEmptyAliases() throws IOException {
        var store = new FileAtomStore(root);
        var log = root.resolve("atoms/segment-000001.log");
        Files.writeString(log,
                "legacy-id\tTEXT\t1.0\t2024-01-01T00:00:00Z\tbGVnYWN5IGNvbnRlbnQ=" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        var found = store.findById("legacy-id").orElseThrow();

        assertEquals("legacy content", found.content());
        assertEquals(List.of(), found.aliases());
    }

    @Test
    void latestEntryWinsWhenSameIdAppearsMultipleTimes() throws IOException {
        var store = new FileAtomStore(root);
        var first = new KnowledgeAtom("dup-id", AtomType.TEXT, "content",
                List.of("alias-a"), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        var second = new KnowledgeAtom("dup-id", AtomType.TEXT, "content",
                List.of("alias-a", "alias-b"), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        store.save(first);
        store.save(second);

        var found = store.findById("dup-id").orElseThrow();
        assertEquals(List.of("alias-a", "alias-b"), found.aliases(),
                "findById must return the last-appended entry for a given id");

        var all = store.findAll();
        assertEquals(1, all.size(),
                "findAll must deduplicate entries with the same id, keeping the last one");
        assertEquals(List.of("alias-a", "alias-b"), all.getFirst().aliases());
    }

    @Test
    void findAllPreservesFirstSeenInsertionOrderAfterDeduplication() throws IOException {
        var store = new FileAtomStore(root);
        var a = new KnowledgeAtom("id-a", AtomType.TEXT, "alpha",
                List.of(), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        var b = new KnowledgeAtom("id-b", AtomType.TEXT, "beta",
                List.of(), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        var aUpdated = new KnowledgeAtom("id-a", AtomType.TEXT, "alpha",
                List.of("extra"), Map.of(), 1.0, Instant.parse("2024-01-01T00:00:00Z"));
        store.save(a);
        store.save(b);
        store.save(aUpdated);

        var all = store.findAll();
        assertEquals(2, all.size());
        assertEquals("id-a", all.get(0).id(),
                "id-a was seen first and must appear first even after being updated");
        assertEquals("id-b", all.get(1).id());
        assertEquals(List.of("extra"), all.get(0).aliases());
    }

    @Test
    void parsesEachRecordOnceAcrossManyLookups() throws IOException {
        var store = new FileAtomStore(root);
        for (int i = 0; i < 40; i++) {
            store.save(atom("id-" + i, "content " + i, Map.of()));
            assertTrue(store.findById("id-" + i).isPresent());
            assertTrue(store.findById("missing").isEmpty());
        }
        assertEquals(40, store.findAll().size());
        assertEquals(40, store.parsedRecordCount(), "each record is parsed exactly once, not once per lookup");
    }

    @Test
    void seesRecordsAppendedByAnotherStoreInstance() throws IOException {
        var reader = new FileAtomStore(root);
        var writer = new FileAtomStore(root);
        assertTrue(reader.findById("late").isEmpty());

        writer.save(atom("late", "appended elsewhere", Map.of()));

        assertEquals("appended elsewhere", reader.findById("late").orElseThrow().content());
        assertEquals(1, reader.parsedRecordCount());
    }

    @Test
    void reloadsWhenTheLogIsTruncated() throws IOException {
        var store = new FileAtomStore(root);
        store.save(atom("old-1", "one", Map.of()));
        store.save(atom("old-2", "two", Map.of()));
        assertEquals(2, store.findAll().size());

        Files.writeString(root.resolve("atoms/segment-000001.log"), "");
        new FileAtomStore(root).save(atom("new-1", "fresh", Map.of()));

        assertEquals(List.of("new-1"), store.findAll().stream().map(KnowledgeAtom::id).toList());
    }

    @Test
    void unterminatedFinalLineIsAppliedAndSupersededOnceCompleted() throws IOException {
        var store = new FileAtomStore(root);
        var log = root.resolve("atoms/segment-000001.log");
        Files.writeString(log, "tail-id\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYQ==", StandardOpenOption.APPEND);
        assertEquals("a", store.findById("tail-id").orElseThrow().content(), "legacy unterminated line is readable");

        Files.writeString(log, "\n", StandardOpenOption.APPEND);
        assertEquals("a", store.findById("tail-id").orElseThrow().content());
        assertEquals(1, store.findAll().size());
    }

    private static final String TAIL = "tail-id\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYQ==";

    @Test
    void removedUnterminatedTailWithNewIdIsRolledBack() throws IOException {
        var store = new FileAtomStore(root);
        store.save(atom("prefix", "kept", Map.of()));
        var log = root.resolve("atoms/segment-000001.log");
        byte[] prefix = Files.readAllBytes(log);
        Files.writeString(log, TAIL, StandardOpenOption.APPEND);
        assertEquals(List.of("prefix", "tail-id"), store.findAll().stream().map(KnowledgeAtom::id).toList());

        Files.write(log, prefix);

        assertEquals(List.of("prefix"), store.findAll().stream().map(KnowledgeAtom::id).toList());
        assertTrue(store.findById("tail-id").isEmpty());
    }

    @Test
    void removedUnterminatedUpdateRestoresThePreviousValueAndOrder() throws IOException {
        var store = new FileAtomStore(root);
        store.save(atom("first", "one", Map.of()));
        store.save(atom("second", "two", Map.of()));
        var log = root.resolve("atoms/segment-000001.log");
        byte[] prefix = Files.readAllBytes(log);
        // unterminated update of "first" (content "a")
        Files.writeString(log, "first\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYQ==", StandardOpenOption.APPEND);
        assertEquals("a", store.findById("first").orElseThrow().content());
        assertEquals(List.of("first", "second"), store.findAll().stream().map(KnowledgeAtom::id).toList());

        Files.write(log, prefix);

        assertEquals("one", store.findById("first").orElseThrow().content());
        assertEquals(List.of("first", "second"), store.findAll().stream().map(KnowledgeAtom::id).toList());
    }

    @Test
    void replacedUnterminatedTailSupersedesTheProvisionalRecord() throws IOException {
        var store = new FileAtomStore(root);
        store.save(atom("prefix", "kept", Map.of()));
        var log = root.resolve("atoms/segment-000001.log");
        byte[] prefix = Files.readAllBytes(log);
        Files.writeString(log, TAIL, StandardOpenOption.APPEND);
        assertTrue(store.findById("tail-id").isPresent());

        Files.write(log, prefix);
        Files.writeString(log, "other-id\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYg==", StandardOpenOption.APPEND);

        assertTrue(store.findById("tail-id").isEmpty());
        assertEquals("b", store.findById("other-id").orElseThrow().content());
    }

    @Test
    void rejectsMalformedUtf8LikeAFullRead() throws IOException {
        var store = new FileAtomStore(root);
        var log = root.resolve("atoms/segment-000001.log");
        byte[] rest = "\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYQ==\n".getBytes(StandardCharsets.UTF_8);
        byte[] bad = new byte[rest.length + 1];
        bad[0] = (byte) 0xC3;
        System.arraycopy(rest, 0, bad, 1, rest.length);
        Files.write(log, bad);

        assertThrows(java.nio.charset.MalformedInputException.class, store::findAll);
        assertThrows(java.nio.charset.MalformedInputException.class, () -> store.findById("x"));
    }

    @Test
    void rejectsMalformedUtf8AppendedIncrementallyAndKeepsEarlierRecords() throws IOException {
        var store = new FileAtomStore(root);
        store.save(atom("good", "fine", Map.of()));
        assertEquals(1, store.findAll().size());
        var log = root.resolve("atoms/segment-000001.log");
        byte[] rest = "\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYQ==\n".getBytes(StandardCharsets.UTF_8);
        byte[] bad = new byte[rest.length + 1];
        bad[0] = (byte) 0xC3;
        System.arraycopy(rest, 0, bad, 1, rest.length);
        Files.write(log, bad, StandardOpenOption.APPEND);

        assertThrows(java.nio.charset.MalformedInputException.class, store::findAll);
    }

    @Test
    void readsValidNonAsciiIdsInitiallyAndIncrementally() throws IOException {
        var store = new FileAtomStore(root);
        var log = root.resolve("atoms/segment-000001.log");
        Files.writeString(log, "idé-ñ\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYQ==\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        assertTrue(store.findById("idé-ñ").isPresent());

        Files.writeString(log, "日本-2\tTEXT\t1.0\t2024-01-01T00:00:00Z\tYg==\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertEquals("b", store.findById("日本-2").orElseThrow().content());
        assertEquals(2, store.findAll().size());
    }
}
