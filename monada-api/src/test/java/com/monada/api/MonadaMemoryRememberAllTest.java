package com.monada.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import com.monada.storage.AtomStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonadaMemoryRememberAllTest {
    private static final List<String> QUERIES = List.of("flaky login test", "gradle cache", "text number 7");

    @TempDir
    Path root;

    /** Counts how often the atom log is scanned. */
    private static final class CountingAtomStore implements AtomStore {
        private final AtomStore delegate;
        int scans;

        CountingAtomStore(AtomStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(KnowledgeAtom atom) throws IOException {
            delegate.save(atom);
        }

        @Override
        public Optional<KnowledgeAtom> findById(String id) throws IOException {
            scans++;
            return delegate.findById(id);
        }

        @Override
        public List<KnowledgeAtom> findAll() throws IOException {
            scans++;
            return delegate.findAll();
        }
    }

    private static List<String> texts(int n) {
        return IntStream.range(0, n).mapToObj(i -> "distinct text number " + i + " about topic " + (i * 31)).toList();
    }

    @Test
    void bulkRememberScansTheAtomLogOnceRegardlessOfBatchSize() {
        for (int n : new int[] {5, 50}) {
            CountingAtomStore[] counter = new CountingAtomStore[1];
            MonadaMemory memory = MonadaMemory.open(root.resolve("bulk-" + n), MonadaMemoryOptions.defaults(), s -> {
                counter[0] = new CountingAtomStore(s);
                return counter[0];
            });
            memory.rememberAll(texts(n));
            assertEquals(1, counter[0].scans, "one scan for a batch of " + n);
        }
    }

    @Test
    void sequentialRememberScansOncePerText() {
        CountingAtomStore[] counter = new CountingAtomStore[1];
        MonadaMemory memory = MonadaMemory.open(root.resolve("seq"), MonadaMemoryOptions.defaults(), s -> {
            counter[0] = new CountingAtomStore(s);
            return counter[0];
        });
        texts(20).forEach(memory::remember);
        assertEquals(20, counter[0].scans);
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
