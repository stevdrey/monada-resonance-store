package com.monada.api;

import java.util.LinkedHashMap;
import java.util.Map;

final class KnownAtomIdCache {
    private final int maxSize;
    private final LinkedHashMap<String, Boolean> ids;

    KnownAtomIdCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be greater than zero");
        }
        this.maxSize = maxSize;
        this.ids = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > KnownAtomIdCache.this.maxSize;
            }
        };
    }

    boolean contains(String atomId) {
        return ids.get(atomId) != null;
    }

    void remember(String atomId) {
        ids.put(atomId, Boolean.TRUE);
    }
}
