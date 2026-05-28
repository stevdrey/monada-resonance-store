package com.monada.api;

import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.storage.feedback.FeedbackSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void rememberingSameContentIsIdempotent() {
        var memory = MonadaMemory.open(memoryDirectory);

        var first = memory.remember("PostgreSQL is a relational SQL database.");
        var second = memory.remember("PostgreSQL is a relational SQL database.");

        assertEquals(first.id(), second.id());

        var recall = memory.resonate("relational sql database")
                .topK(5)
                .threshold(0.0)
                .execute();

        assertEquals(1, recall.results().size());
        assertEquals(first.id(), recall.results().getFirst().atom().id());
    }

    @Test
    void aliasesContributeToStoredVectorWithoutReplacingOriginalContent() {
        var memory = MonadaMemory.open(memoryDirectory);
        var atom = memory.remember("CQRS separates the read model from the write model.",
                List.of("command query responsibility segregation", "read path write path"));

        var recall = memory.resonate("command query responsibility segregation")
                .topK(1)
                .threshold(0.0)
                .execute();

        assertEquals(atom.id(), recall.results().getFirst().atom().id());
        assertEquals("CQRS separates the read model from the write model.",
                recall.results().getFirst().atom().content());
        assertEquals(List.of("command query responsibility segregation", "read path write path"),
                recall.results().getFirst().atom().aliases());
    }

    @Test
    void stopWordOnlyQueryReturnsNoResultsAfterAtomsExist() {
        var memory = MonadaMemory.open(memoryDirectory);
        memory.remember("Redis is an in-memory data structure store often used as a cache.");
        memory.remember("SQLite is an embedded relational database.");

        var recall = memory.resonate("the and of")
                .topK(5)
                .threshold(0.0)
                .execute();

        assertTrue(recall.results().isEmpty());
    }

    @Test
    void atomSearchableContentIsNormalizedBeforeEncoding() {
        var memory = MonadaMemory.open(memoryDirectory);
        var atom = memory.remember("Operational indexes support analytical queries.");

        var recall = memory.resonate("operational index support analytical query")
                .topK(1)
                .threshold(0.99)
                .execute();

        assertEquals(atom.id(), recall.results().getFirst().atom().id());
    }

    @Test
    void rememberWithNewAliasesMergesAndPreservesContentDerivedId() {
        var memory = MonadaMemory.open(memoryDirectory);
        var first = memory.remember("Apache Kafka is a distributed event streaming platform.");
        var second = memory.remember("Apache Kafka is a distributed event streaming platform.",
                List.of("kafka", "event streaming"));

        assertEquals(first.id(), second.id(),
                "Content-derived id must not change after alias merge");
        assertEquals(List.of("kafka", "event streaming"), second.aliases());

        var recall = memory.resonate("event streaming")
                .topK(1)
                .threshold(0.0)
                .execute();
        assertEquals(first.id(), recall.results().getFirst().atom().id(),
                "Atom must be recallable via the newly added alias");
    }

    @Test
    void twoSuccessiveAliasMergesProduceUnionInOrder() {
        var memory = MonadaMemory.open(memoryDirectory);
        memory.remember("Apache Flink is a stateful stream processing framework.",
                List.of("stream processing"));
        var merged = memory.remember("Apache Flink is a stateful stream processing framework.",
                List.of("flink", "stateful computation"));

        assertEquals(List.of("stream processing", "flink", "stateful computation"),
                merged.aliases(),
                "Alias union must preserve existing order then append new aliases");
    }

    @Test
    void rememberWithSameAliasesIsIdempotent() throws Exception {
        var memory = MonadaMemory.open(memoryDirectory);
        memory.remember("ClickHouse is an OLAP column-store database.", List.of("column store", "olap"));
        memory.remember("ClickHouse is an OLAP column-store database.", List.of("column store", "olap"));

        var atomLog = memoryDirectory.resolve("atoms/segment-000001.log");
        long lineCount = java.nio.file.Files.lines(atomLog)
                .filter(l -> !l.isBlank())
                .count();
        assertEquals(1, lineCount,
                "Calling remember with identical aliases must not append a new atom log entry");
    }

    @Test
    void feedbackAcceptsRememberedAtomsAndRejectsUnknownAtoms() {
        var memory = MonadaMemory.open(memoryDirectory);
        var atom = memory.remember("SQLite is an embedded relational database.");

        memory.feedback("embedded relational database", atom.id(), FeedbackSignal.POSITIVE);
        memory.feedback("embedded relational database", atom.id(), FeedbackSignal.POSITIVE);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> memory.feedback("embedded relational database", "missing-atom", FeedbackSignal.POSITIVE));
        assertTrue(ex.getMessage().contains("Unknown atomId"));
    }

    @Test
    void feedbackAcceptsAtomsAfterReopenViaStorageFallback() {
        var memory = MonadaMemory.open(memoryDirectory);
        var atom = memory.remember("DuckDB is an embedded analytical database.");

        var reopened = MonadaMemory.open(memoryDirectory);
        reopened.feedback("embedded analytical database", atom.id(), FeedbackSignal.POSITIVE);

        var recall = reopened.resonate("embedded analytical database")
                .topK(1)
                .threshold(0.0)
                .execute();
        assertEquals(atom.id(), recall.results().getFirst().atom().id());
    }

    @Test
    void customLargeExpansionWeightsDoesNotCrashWithAliases() {
        var customOptions = new MonadaMemoryOptions(
                new LexicalEnrichmentPipeline(),
                true,
                new LexicalExpansionOptions(2.0, 1.2) // expansionWeight > 1.0 (1.2 * 1.2 = 1.44 > 1.2)
        );
        var memory = MonadaMemory.open(memoryDirectory, customOptions);
        var atom = memory.remember("Redis is a key-value store.", List.of("in-memory db", "cache"));
        
        assertEquals(List.of("in-memory db", "cache"), atom.aliases());
    }
}
