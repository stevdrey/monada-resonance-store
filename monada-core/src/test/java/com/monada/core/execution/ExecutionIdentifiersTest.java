package com.monada.core.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionIdentifiersTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " lead", "trail ", "tab\tinside", "line\nbreak", "nul\u0000", "lone\uD800surrogate"})
    void rejectsInvalidIdentifiers(String value) {
        assertThrows(IllegalArgumentException.class, () -> ScopeId.of(value));
        assertThrows(IllegalArgumentException.class, () -> EventId.of(value));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> TaskId.of(null));
    }

    @Test
    void enforcesLengthInCodePointsNotChars() {
        String emoji128 = "😀".repeat(128);
        assertEquals(emoji128, ExecutionId.of(emoji128).value());
        assertThrows(IllegalArgumentException.class, () -> ExecutionId.of(emoji128 + "a"));
        assertEquals(128, AttemptId.of("a".repeat(128)).value().length());
        assertThrows(IllegalArgumentException.class, () -> AttemptId.of("a".repeat(129)));
    }

    @Test
    void acceptsUnicodeAndKeepsItAsSupplied() {
        String nfd = Normalizer.normalize("café-ejecución-実行", Normalizer.Form.NFD);
        String nfc = Normalizer.normalize(nfd, Normalizer.Form.NFC);
        assertNotEquals(nfd, nfc);
        assertEquals(nfd, ScopeId.of(nfd).value(), "IDs are not re-normalized");
        assertNotEquals(ScopeId.of(nfd), ScopeId.of(nfc), "comparison is exact String.equals");
    }

    @Test
    void comparisonIsCaseSensitive() {
        assertNotEquals(EventId.of("Evt-1"), EventId.of("evt-1"));
    }

    @Test
    void identicalTaskTextCanHaveDistinctExecutionAndAttemptIdentities() {
        var first = Fixtures.started("evt-1", "exec-1");
        var second = Fixtures.started("evt-2", "exec-2");
        assertEquals(first.taskSummary(), second.taskSummary());
        assertNotEquals(first.executionId(), second.executionId());
        assertNotEquals(first.eventId(), second.eventId());
        assertNotEquals(AttemptId.of("attempt-1"), AttemptId.of("attempt-2"));
    }
}
