package com.monada.speech.evaluation;

import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates a mode-labeled speech benchmark.
 *
 * <p>The runner delegates all retrieval and metric computation to
 * {@link SpeechRetrievalEvaluator}. It does not synthesize audio, change ranking, or
 * touch the acoustic encoder; it only attaches the {@link SpeechBenchmarkMode} and the
 * minimum comparison metadata (corpus size, label) to the produced report.
 *
 * <p>Callers populate the stores beforehand: generated WAV fixtures for the protected
 * mode (in tests) or an imported local corpus for the exploratory mode.
 */
public final class SpeechBenchmarkRunner {

    private final SpeechRetrievalEvaluator evaluator;

    public SpeechBenchmarkRunner() {
        this(new SpeechRetrievalEvaluator());
    }

    public SpeechBenchmarkRunner(SpeechRetrievalEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /**
     * Runs the benchmark and produces a labeled report.
     *
     * @param mode         benchmark mode (protected vs exploratory)
     * @param label        human-readable label for the corpus/run (non-blank)
     * @param queries      queries to evaluate (non-empty)
     * @param retriever    retriever used to rank candidates
     * @param sampleStore  source of speech sample metadata
     * @param featureStore source of acoustic feature vectors
     * @param options      evaluation options
     * @return a {@link SpeechBenchmarkReport} wrapping the deterministic evaluation report
     * @throws IOException              if the underlying evaluation fails to read stores or encode audio
     * @throws NullPointerException     if a required parameter is null
     * @throws IllegalArgumentException if {@code label} is blank or {@code queries} is empty
     */
    public SpeechBenchmarkReport run(
            SpeechBenchmarkMode mode,
            String label,
            List<SpeechEvaluationQuery> queries,
            SpeechSampleRetriever retriever,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            SpeechEvaluationOptions options
    ) throws IOException {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(retriever, "retriever");
        Objects.requireNonNull(sampleStore, "sampleStore");
        Objects.requireNonNull(featureStore, "featureStore");
        Objects.requireNonNull(options, "options");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must be non-blank");
        }
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries must be non-empty");
        }

        int corpusSize = sampleStore.findAll().size();
        SpeechEvaluationReport report = evaluator.evaluate(queries, retriever, sampleStore, featureStore, options);
        return new SpeechBenchmarkReport(mode, label, corpusSize, options.k(), report);
    }
}
