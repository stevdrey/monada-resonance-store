package com.monada.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import com.monada.storage.FileAtomStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonadaMemoryRememberAllTest {
    private static final List<String> QUERIES = List.of("flaky login test", "gradle cache", "text number 7");

    @TempDir
    Path root;

    private static List<String> texts(int n) {
        return IntStream.range(0, n).mapToObj(i -> "distinct text number " + i + " about topic " + (i * 31)).toList();
    }

    /** Opens a store and captures its FileAtomStore so tests can read the parse counter. */
    private static MonadaMemory open(Path dir, FileAtomStore[] captured) {
        return MonadaMemory.open(dir, MonadaMemoryOptions.defaults(), s -> {
            captured[0] = (FileAtomStore) s;
            return s;
        });
    }

    @Test
    void bulkRememberParsesEachRecordOnce() throws IOException {
        for (int n : new int[] {5, 50}) {
            FileAtomStore[] store = new FileAtomStore[1];
            open(root.resolve("bulk-" + n), store).rememberAll(texts(n));
            store[0].findAll(); // picks up the last appended record
            assertEquals(n, store[0].parsedRecordCount(), "linear parse cost for a batch of " + n);
        }
    }

    @Test
    void sequentialRememberIsAlsoLinear() throws IOException {
        FileAtomStore[] store = new FileAtomStore[1];
        MonadaMemory memory = open(root.resolve("seq"), store);
        texts(20).forEach(memory::remember);
        store[0].findAll(); // picks up the last appended record
        assertEquals(20, store[0].parsedRecordCount());
    }

    @Test
    void batchRespectsRecordsAppendedByAnotherWriterBetweenBatches() throws IOException {
        Path dir = root.resolve("shared");
        MonadaMemory first = MonadaMemory.open(dir);
        MonadaMemory second = MonadaMemory.open(dir);
        first.rememberAll(List.of("alpha topic"));
        second.rememberAll(List.of("beta topic"));

        first.rememberAll(List.of("beta topic", "gamma topic"));

        assertEquals(3, Files.readAllLines(dir.resolve("atoms/segment-000001.log")).size(),
                "no duplicate record for a text another writer already stored");
    }

    @Test
    void rejectsNullAtomStoreDecorator() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> MonadaMemory.open(root.resolve("npe"), MonadaMemoryOptions.defaults(), null));
        assertEquals("atomStoreDecorator", ex.getMessage());
    }

    @Test
    void bulkMatchesSequentialRememberAtomsVectorsAndRanking() throws IOException {
        List<String> input = new java.util.ArrayList<>(texts(12));
        input.add("Repair the flaky login test");
        input.add("Enable the gradle configuration cache");
        input.add(input.get(3)); // repeated within the batch

        MonadaMemory seq = MonadaMemory.open(root.resolve("a"));
        List<String> seqIds = input.stream().map(t -> seq.remember(t).id()).toList();
        MonadaMemory bulk = MonadaMemory.open(root.resolve("b"));
        List<KnowledgeAtom> atoms = bulk.rememberAll(input);

        assertEquals(seqIds, atoms.stream().map(KnowledgeAtom::id).toList());
        assertEquals(input, atoms.stream().map(KnowledgeAtom::content).toList());
        assertArrayEquals(Files.readAllBytes(root.resolve("a/vectors/segment-000001.f32")),
                Files.readAllBytes(root.resolve("b/vectors/segment-000001.f32")), "vector bytes");
        assertEquals(Files.readAllLines(root.resolve("a/atoms/segment-000001.log")).size(),
                Files.readAllLines(root.resolve("b/atoms/segment-000001.log")).size(), "one line per distinct text");
        for (String query : QUERIES) {
            var expected = seq.resonate(query).topK(5).execute().results();
            var actual = bulk.resonate(query).topK(5).execute().results();
            assertEquals(expected.stream().map(r -> r.atom().id()).toList(),
                    actual.stream().map(r -> r.atom().id()).toList(), query);
            assertEquals(expected.stream().map(r -> r.score()).toList(),
                    actual.stream().map(r -> r.score()).toList(), query);
        }
    }

    @Test
    void alreadyStoredTextsAreIdempotent() throws IOException {
        MonadaMemory memory = MonadaMemory.open(root.resolve("idem"));
        KnowledgeAtom first = memory.remember("Write release notes");
        Path log = root.resolve("idem/atoms/segment-000001.log");
        Path vectors = root.resolve("idem/vectors/segment-000001.f32");
        long logBefore = Files.size(log);
        long vectorsBefore = Files.size(vectors);

        List<KnowledgeAtom> result = memory.rememberAll(List.of("Write release notes", "Write release notes"));

        assertEquals(List.of(first.id(), first.id()), result.stream().map(KnowledgeAtom::id).toList());
        assertEquals(logBefore, Files.size(log));
        assertEquals(vectorsBefore, Files.size(vectors));
        assertTrue(memory.rememberAll(List.of()).isEmpty());
    }
}
