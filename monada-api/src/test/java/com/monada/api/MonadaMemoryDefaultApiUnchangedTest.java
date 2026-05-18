package com.monada.api;

import com.monada.core.MonadaRecall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests verifying that the default {@link MonadaMemory#open(Path)} API
 * continues to behave exactly as before after the introduction of
 * {@link MonadaMemoryOptions}.
 *
 * <p>These tests deliberately avoid relying on implementation internals —
 * they exercise only the public contract: atoms can be stored and recalled
 * using the default configuration.
 */
class MonadaMemoryDefaultApiUnchangedTest {

    @Test
    void defaultOpenRemembersAndResonatesSuccessfully(@TempDir Path tempDir) {
        var memory = MonadaMemory.open(tempDir);

        var atom = memory.remember("cosine similarity measures angle between frequency vectors");
        assertNotNull(atom, "remembered atom must not be null");
        assertNotNull(atom.id(), "atom id must not be null");

        MonadaRecall recall = memory.resonate("cosine similarity").topK(3).execute();
        assertNotNull(recall);
        assertFalse(recall.results().isEmpty(), "resonate must return at least one result");
        assertTrue(recall.results().get(0).atom().id().equals(atom.id()),
                "top result must be the stored atom");
    }

    @Test
    void defaultOpenStringPathRemembersAndResonates(@TempDir Path tempDir) {
        var memory = MonadaMemory.open(tempDir.toString());

        memory.remember("approximate nearest neighbor search over embeddings");
        MonadaRecall recall = memory.resonate("nearest neighbor embeddings").topK(5).execute();

        assertNotNull(recall);
        assertFalse(recall.results().isEmpty());
    }

    @Test
    void defaultOptionsEquivalentToDefaultOpen(@TempDir Path tempDir1, @TempDir Path tempDir2) {
        // Both should produce identical top-1 results for the same content and query.
        var defaultMemory = MonadaMemory.open(tempDir1);
        var optionsMemory = MonadaMemory.open(tempDir2, MonadaMemoryOptions.defaults());

        var content = "linear scan resonance index similarity scoring";
        var query = "linear scan similarity";

        defaultMemory.remember(content);
        optionsMemory.remember(content);

        var defaultRecall = defaultMemory.resonate(query).topK(1).execute();
        var optionsRecall = optionsMemory.resonate(query).topK(1).execute();

        assertFalse(defaultRecall.results().isEmpty());
        assertFalse(optionsRecall.results().isEmpty());
        // Both should return the same atom content.
        assertEquals(
                defaultRecall.results().get(0).atom().content(),
                optionsRecall.results().get(0).atom().content());
    }
}
