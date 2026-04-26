package com.monada.api;

import com.monada.core.MonadaRecall;
import com.monada.encoder.FrequencyEncoder;
import com.monada.index.ResonanceIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

public class MonadaQuery {

    private final String query;
    private final FrequencyEncoder encoder;
    private final ResonanceIndex resonanceIndex;
    private int topK = 10;
    private double threshold = 0.0;

    MonadaQuery(String query, FrequencyEncoder encoder, ResonanceIndex resonanceIndex) {
        this.query = Objects.requireNonNull(query, "query");
        this.encoder = encoder;
        this.resonanceIndex = resonanceIndex;
    }

    public MonadaQuery topK(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }
        this.topK = topK;
        return this;
    }

    public MonadaQuery threshold(double threshold) {
        this.threshold = threshold;
        return this;
    }

    public MonadaRecall execute() {
        try {
            return new MonadaRecall(resonanceIndex.search(encoder.encode(query), topK, threshold));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
