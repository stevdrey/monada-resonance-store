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
import java.util.Map;
import java.util.Optional;

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
        FileAtomStore store = new FileAtomStore(root);
        KnowledgeAtom original = atom("a-1", "hello world", Map.of());
        store.save(original);

        Optional<KnowledgeAtom> found = store.findById("a-1");
        assertTrue(found.isPresent());
        assertEquals("hello world", found.get().content());
        assertEquals(AtomType.TEXT, found.get().type());
    }

    @Test
    void rejectsAtomWithNonEmptyMetadata() throws IOException {
        FileAtomStore store = new FileAtomStore(root);
        KnowledgeAtom withMetadata = atom("a-2", "x", Map.of("k", "v"));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> store.save(withMetadata));
        assertTrue(ex.getMessage().contains("metadata"));
    }

    @Test
    void parseWrapsLowLevelErrorsWithLineContext() throws IOException {
        FileAtomStore store = new FileAtomStore(root);
        Path log = root.resolve("atoms/segment-000001.log");
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
        FileAtomStore store = new FileAtomStore(root);
        Path log = root.resolve("atoms/segment-000001.log");
        Files.writeString(log, "only-one-field" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IllegalStateException ex = assertThrows(IllegalStateException.class, store::findAll);
        assertTrue(ex.getMessage().contains("Malformed atom log entry"));
        assertTrue(ex.getMessage().contains("expected 5"));
    }
}
