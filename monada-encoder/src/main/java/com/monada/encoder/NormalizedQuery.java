package com.monada.encoder;

import java.util.List;

public final class NormalizedQuery extends NormalizedText {

    public NormalizedQuery(String original, String normalized, List<String> expansions) {
        super(original, normalized, expansions);
    }
}
