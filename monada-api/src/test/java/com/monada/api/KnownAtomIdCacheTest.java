package com.monada.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownAtomIdCacheTest {

    @Test
    void evictsLeastRecentlyUsedIdWhenLimitIsExceeded() {
        var cache = new KnownAtomIdCache(2);
        cache.remember("a");
        cache.remember("b");

        assertTrue(cache.contains("a"));
        cache.remember("c");

        assertTrue(cache.contains("a"));
        assertFalse(cache.contains("b"));
        assertTrue(cache.contains("c"));
    }

    @Test
    void rejectsNonPositiveMaxSize() {
        assertThrows(IllegalArgumentException.class, () -> new KnownAtomIdCache(0));
    }
}
