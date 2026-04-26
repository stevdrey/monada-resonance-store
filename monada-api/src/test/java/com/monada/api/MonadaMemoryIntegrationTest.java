package com.monada.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonadaMemoryIntegrationTest {

    @TempDir
    Path memoryDirectory;

    @Test
    void remembersAndResonatesStoredKnowledge() throws Exception {
        MonadaMemory memory = MonadaMemory.open(memoryDirectory);

        memory.remember("OrientDB is a multi-model database that combines graph and document models.");
        memory.remember("Redis is an in-memory data structure store often used as a cache.");
        memory.remember("ArangoDB is a multi-model database with document graph and search capabilities.");

        var recall = memory.resonate("database with graph and document model")
                .topK(2)
                .threshold(0.1)
                .execute();

        assertFalse(recall.results().isEmpty());
        assertTrue(recall.results().getFirst().atom().content().contains("database"));
        assertTrue(recall.results().getFirst().score() > 0.0);
        assertEquals(2, recall.results().size());
        assertTrue(Files.exists(memoryDirectory.resolve("manifest.json")));
        assertTrue(Files.exists(memoryDirectory.resolve("atoms/segment-000001.log")));
        assertTrue(Files.exists(memoryDirectory.resolve("vectors/segment-000001.f32")));
        assertTrue(Files.exists(memoryDirectory.resolve("indexes/vector-map.idx")));
    }
}
