package com.monada.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
