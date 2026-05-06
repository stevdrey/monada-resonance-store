package com.monada.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStringsTest {

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
}
