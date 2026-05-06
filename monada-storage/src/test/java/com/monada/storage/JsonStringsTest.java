package com.monada.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStringsTest {

    @Test
    void escapeRejectsNullValueWithParameterName() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> JsonStrings.escape(null));
        assertEquals("value", ex.getMessage());
    }

    @Test
    void unescapeRejectsNullValueWithParameterName() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> JsonStrings.unescape(null, "test source"));
        assertEquals("value", ex.getMessage());
    }

    @Test
    void unescapeRejectsNullSourceNameWithParameterName() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> JsonStrings.unescape("value", null));
        assertEquals("sourceName", ex.getMessage());
    }

    @Test
    void unescapeRejectsTrailingBackslash() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JsonStrings.unescape("bad\\", "test source"));
        assertTrue(ex.getMessage().contains("test source"));
    }

    @Test
    void unescapeRejectsInvalidUnicodeHex() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JsonStrings.unescape("\\uZZZZ", "test source"));
        assertTrue(ex.getMessage().contains("test source"));
        assertTrue(ex.getMessage().contains("ZZZZ"));
    }

    @Test
    void escapeAndUnescapeRoundTrip() {
        String value = "quotes \" slash \\ newline\n tab\t";
        assertEquals(value, JsonStrings.unescape(JsonStrings.escape(value), "test source"));
    }

    @Test
    void parseFlatExtractsFieldsCorrectly() {
        var fields = JsonStrings.parseFlat(
                "{\"query\":\"alpha\",\"delta\":0.05,\"createdAt\":\"2026-05-01T00:00:00Z\"}",
                "test source");
        assertEquals("alpha", fields.get("query"));
        assertEquals("0.05", fields.get("delta"));
        assertEquals("2026-05-01T00:00:00Z", fields.get("createdAt"));
    }

    @Test
    void parseFlatIgnoresEmbeddedFieldPatterns() {
        var fields = JsonStrings.parseFlat(
                "{\"query\":\"contains \\\"delta\\\": 99.9 inside text\",\"delta\":0.05}",
                "test source");
        assertEquals("contains \"delta\": 99.9 inside text", fields.get("query"));
        assertEquals("0.05", fields.get("delta"));
    }
}
