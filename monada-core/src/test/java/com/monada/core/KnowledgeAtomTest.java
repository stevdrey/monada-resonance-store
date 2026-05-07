package com.monada.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeAtomTest {

    @Test
    void textProducesDeterministicIdForSameContent() {
        var first = KnowledgeAtom.text("hello world");
        var second = KnowledgeAtom.text("hello world");

        assertEquals(first.id(), second.id(),
                "Same content must yield the same atom id across invocations");
    }

    @Test
    void textProducesDifferentIdForDifferentContent() {
        var a = KnowledgeAtom.text("alpha");
        var b = KnowledgeAtom.text("beta");

        assertNotEquals(a.id(), b.id(),
                "Different content must produce different atom ids");
    }

    @Test
    void aliasesAreImmutableAndSearchable() {
        var aliases = new ArrayList<>(List.of("command query responsibility segregation"));
        var atom = KnowledgeAtom.text("CQRS separates read and write models.", aliases);
        aliases.add("mutated");

        assertEquals(List.of("command query responsibility segregation"), atom.aliases());
        assertEquals("CQRS separates read and write models. command query responsibility segregation",
                atom.searchableContent());
    }

    @Test
    void rejectsBlankAliases() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeAtom.text("content", List.of(" ")));
    }
}
