package com.monada.encoder;

public interface QueryNormalizer extends TextNormalizer {

    @Override
    NormalizedQuery normalize(String query);
}
