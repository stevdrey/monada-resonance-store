package com.monada.encoder;

public interface TextNormalizer {

    NormalizedText normalize(String text);

    /**
     * Returns a stable identifier describing this normalizer's configuration.
     *
     * <p>The default is the implementing class's simple name, which is
     * sufficient for normalizers without configurable state. Implementations
     * with configurable lexical resources (e.g. stop words, plurals, synonyms)
     * must override this to include a deterministic fingerprint of that state,
     * so stores built with different resources are detected as incompatible.
     */
    default String configurationFingerprint() {
        return getClass().getSimpleName();
    }
}
