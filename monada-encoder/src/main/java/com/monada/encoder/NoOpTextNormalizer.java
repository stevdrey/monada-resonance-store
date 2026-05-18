package com.monada.encoder;

import java.util.List;
import java.util.Objects;

/**
 * A {@link TextNormalizer} that preserves the original text as-is.
 *
 * <p>Used in A/B evaluation harnesses to represent the RAW retrieval profile,
 * where no lexical enrichment is applied before encoding. Passing blank or
 * empty text through returns it unchanged; the encoder and query pipeline are
 * responsible for handling blank inputs.
 */
public final class NoOpTextNormalizer implements TextNormalizer {

    public NoOpTextNormalizer() {
    }

    @Override
    public NormalizedText normalize(String text) {
        Objects.requireNonNull(text, "text");
        return new NormalizedText(text, text, List.of());
    }
}
