package com.monada.speech.retrieval;

import com.monada.core.FrequencyVector;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.encoder.AcousticFeatureEncoder;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;
import com.monada.speech.storage.StoredSpeechFeatureVector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service for retrieving speech samples by acoustic similarity to a query audio file.
 *
 * <p>Uses linear scan over stored acoustic feature vectors with cosine similarity scoring.
 * Results are sorted by descending similarity score with deterministic tie-breaking by sample ID.
 */
public final class SpeechSampleRetriever {

    private static final Logger logger = Logger.getLogger(SpeechSampleRetriever.class.getName());

    private final AcousticFeatureEncoder encoder;

    /**
     * Creates a new retriever with the specified acoustic feature encoder.
     *
     * @param encoder the encoder to use for query audio encoding
     * @throws NullPointerException if encoder is null
     */
    public SpeechSampleRetriever(AcousticFeatureEncoder encoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    /**
     * Searches for speech samples similar to the query audio file.
     *
     * @param queryAudio path to the query audio file
     * @param sampleStore store for retrieving speech samples
     * @param featureStore store for retrieving acoustic feature vectors
     * @param options search configuration options
     * @return list of ranked retrieval results
     * @throws IOException if query audio cannot be read or encoded
     * @throws NullPointerException if any required parameter is null
     */
    public List<SpeechRetrievalResult> search(
            Path queryAudio,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            SpeechRetrievalOptions options
    ) throws IOException {
        return executeSearch(queryAudio, sampleStore, featureStore, options, false).results();
    }

    /**
     * Searches for speech samples while capturing deterministic candidate and score diagnostics.
     *
     * <p>The ranked results are produced by the same implementation as {@link #search};
     * diagnostic collection does not affect candidate inclusion, scores, or ordering.
     *
     * @param queryAudio path to the query audio file
     * @param sampleStore store for retrieving speech samples
     * @param featureStore store for retrieving acoustic feature vectors
     * @param options search configuration options
     * @return ranked results together with acoustic retrieval diagnostics
     * @throws IOException if query audio cannot be read or encoded
     * @throws NullPointerException if any required parameter is null
     */
    public SpeechRetrievalOutcome searchWithDiagnostics(
            Path queryAudio,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            SpeechRetrievalOptions options
    ) throws IOException {
        SearchExecution execution = executeSearch(queryAudio, sampleStore, featureStore, options, true);
        return new SpeechRetrievalOutcome(execution.results(), execution.diagnostic());
    }

    private SearchExecution executeSearch(
            Path queryAudio,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            SpeechRetrievalOptions options,
            boolean captureDiagnostics
    ) throws IOException {
        Objects.requireNonNull(queryAudio, "queryAudio");
        Objects.requireNonNull(sampleStore, "sampleStore");
        Objects.requireNonNull(featureStore, "featureStore");
        Objects.requireNonNull(options, "options");

        // Encode query audio
        FrequencyVector queryVector = encoder.encode(queryAudio);

        // Load all samples into a map for O(1) lookups (avoids N+1 file reads)
        Map<String, SpeechSample> samplesById = sampleStore.findAll().stream()
                .collect(Collectors.toMap(SpeechSample::id, Function.identity()));

        // Load all stored feature vectors
        List<StoredSpeechFeatureVector> storedVectors = featureStore.findAll();
        List<CandidateScore> candidates = new ArrayList<>();
        List<SpeechCandidateDiagnostic> candidateDiagnostics = captureDiagnostics
                ? new ArrayList<>(storedVectors.size())
                : List.of();

        // Score each candidate
        for (StoredSpeechFeatureVector storedVector : storedVectors) {
            String sampleId = storedVector.sampleId();
            FrequencyVector candidateVector = storedVector.vector();

            // Resolve sample
            SpeechSample sample = samplesById.get(sampleId);
            if (sample == null) {
                logger.warning("Skipping orphan feature vector for sample: " + sampleId);
                if (captureDiagnostics) {
                    candidateDiagnostics.add(excludedCandidate(
                            sampleId, SpeechCandidateStatus.ORPHAN_VECTOR, List.of()));
                }
                continue;
            }

            // Check dimension compatibility
            if (queryVector.dimensions() != candidateVector.dimensions()) {
                logger.warning("Skipping vector with incompatible dimensions for sample: " + sampleId +
                        " (query: " + queryVector.dimensions() + ", candidate: " + candidateVector.dimensions() + ")");
                if (captureDiagnostics) {
                    candidateDiagnostics.add(excludedCandidate(
                            sampleId, SpeechCandidateStatus.INCOMPATIBLE_DIMENSIONS, List.of()));
                }
                continue;
            }

            // Apply metadata filters
            List<SpeechMetadataFilter> failedFilters = captureDiagnostics
                    ? failedFilters(sample, options)
                    : List.of();
            boolean matchesFilters = captureDiagnostics
                    ? failedFilters.isEmpty()
                    : matchesFilters(sample, options);
            if (!matchesFilters) {
                if (captureDiagnostics) {
                    candidateDiagnostics.add(excludedCandidate(
                            sampleId, SpeechCandidateStatus.METADATA_FILTERED, failedFilters));
                }
                continue;
            }

            // Compute cosine similarity
            double score = cosineSimilarity(queryVector, candidateVector);
            candidates.add(new CandidateScore(sample, score));
        }

        // Sort by score descending, then by sample ID ascending for deterministic tie-breaking
        candidates.sort(Comparator
                .comparing(CandidateScore::score).reversed()
                .thenComparing(c -> c.sample().id()));

        // Apply topK truncation and assign ranks
        List<SpeechRetrievalResult> results = new ArrayList<>();
        int topK = options.topK();
        for (int i = 0; i < Math.min(candidates.size(), topK); i++) {
            CandidateScore candidate = candidates.get(i);
            results.add(new SpeechRetrievalResult(candidate.sample(), candidate.score(), i + 1));
        }

        SpeechRetrievalDiagnostic diagnostic = null;
        if (captureDiagnostics) {
            for (int i = 0; i < candidates.size(); i++) {
                CandidateScore candidate = candidates.get(i);
                candidateDiagnostics.add(new SpeechCandidateDiagnostic(
                        candidate.sample().id(),
                        SpeechCandidateStatus.SCORED,
                        List.of(),
                        candidate.score(),
                        i + 1));
            }
            candidateDiagnostics.sort(Comparator.comparing(SpeechCandidateDiagnostic::sampleId));
            diagnostic = createDiagnostic(storedVectors.size(), candidates, candidateDiagnostics);
        }

        return new SearchExecution(results, diagnostic);
    }

    /**
     * Checks if a speech sample matches the specified filters.
     */
    private boolean matchesFilters(SpeechSample sample, SpeechRetrievalOptions options) {
        if (options.datasetSource() != null && options.datasetSource() != sample.datasetSource()) {
            return false;
        }
        if (options.condition() != null && options.condition() != sample.condition()) {
            return false;
        }
        if (options.taskType() != null && options.taskType() != sample.taskType()) {
            return false;
        }
        if (options.speakerId() != null && !options.speakerId().equals(sample.speakerId())) {
            return false;
        }
        if (options.language() != null && !options.language().equals(sample.language())) {
            return false;
        }
        return true;
    }

    private List<SpeechMetadataFilter> failedFilters(
            SpeechSample sample,
            SpeechRetrievalOptions options
    ) {
        List<SpeechMetadataFilter> failed = new ArrayList<>();
        if (options.datasetSource() != null && options.datasetSource() != sample.datasetSource()) {
            failed.add(SpeechMetadataFilter.DATASET_SOURCE);
        }
        if (options.condition() != null && options.condition() != sample.condition()) {
            failed.add(SpeechMetadataFilter.CONDITION);
        }
        if (options.taskType() != null && options.taskType() != sample.taskType()) {
            failed.add(SpeechMetadataFilter.TASK_TYPE);
        }
        if (options.speakerId() != null && !options.speakerId().equals(sample.speakerId())) {
            failed.add(SpeechMetadataFilter.SPEAKER_ID);
        }
        if (options.language() != null && !options.language().equals(sample.language())) {
            failed.add(SpeechMetadataFilter.LANGUAGE);
        }
        return List.copyOf(failed);
    }

    private SpeechCandidateDiagnostic excludedCandidate(
            String sampleId,
            SpeechCandidateStatus status,
            List<SpeechMetadataFilter> failedFilters
    ) {
        return new SpeechCandidateDiagnostic(sampleId, status, failedFilters, 0.0, 0);
    }

    private SpeechRetrievalDiagnostic createDiagnostic(
            int scannedVectorCount,
            List<CandidateScore> scoredCandidates,
            List<SpeechCandidateDiagnostic> candidateDiagnostics
    ) {
        int orphanCount = 0;
        int incompatibleCount = 0;
        int filteredCount = 0;
        for (SpeechCandidateDiagnostic candidate : candidateDiagnostics) {
            switch (candidate.status()) {
                case ORPHAN_VECTOR -> orphanCount++;
                case INCOMPATIBLE_DIMENSIONS -> incompatibleCount++;
                case METADATA_FILTERED -> filteredCount++;
                case SCORED -> {
                    // Counted from scoredCandidates below.
                }
            }
        }

        int tieCount = 0;
        double minimumScore = 0.0;
        double meanScore = 0.0;
        double maximumScore = 0.0;
        double topResultScore = 0.0;
        double topScoreGap = 0.0;
        if (!scoredCandidates.isEmpty()) {
            minimumScore = scoredCandidates.get(0).score();
            maximumScore = scoredCandidates.get(0).score();
            double scoreSum = 0.0;
            for (int i = 0; i < scoredCandidates.size(); i++) {
                double score = scoredCandidates.get(i).score();
                minimumScore = Math.min(minimumScore, score);
                maximumScore = Math.max(maximumScore, score);
                scoreSum += score;
                if (i > 0 && Double.compare(score, scoredCandidates.get(i - 1).score()) == 0) {
                    tieCount++;
                }
            }
            meanScore = scoreSum / scoredCandidates.size();
            topResultScore = scoredCandidates.get(0).score();
            if (scoredCandidates.size() > 1) {
                topScoreGap = topResultScore - scoredCandidates.get(1).score();
            }
        }

        return new SpeechRetrievalDiagnostic(
                scannedVectorCount,
                orphanCount,
                incompatibleCount,
                filteredCount,
                scoredCandidates.size(),
                tieCount,
                minimumScore,
                meanScore,
                maximumScore,
                topResultScore,
                topScoreGap,
                candidateDiagnostics);
    }

    /**
     * Computes cosine similarity between two frequency vectors.
     *
     * <p>This method computes true cosine similarity with magnitude normalization,
     * making it robust against any valid {@link FrequencyVector} regardless of
     * whether it is L2-normalized.
     *
     * @param a first vector
     * @param b second vector
     * @return cosine similarity in range [-1.0, 1.0], or 0.0 if either vector has zero magnitude
     */
    private double cosineSimilarity(FrequencyVector a, FrequencyVector b) {
        float[] aValues = a.values();
        float[] bValues = b.values();

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < aValues.length; i++) {
            dot += aValues[i] * bValues[i];
            normA += aValues[i] * aValues[i];
            normB += bValues[i] * bValues[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Internal record for tracking scored candidates during processing.
     */
    private record CandidateScore(
            SpeechSample sample,
            double score
    ) {}

    private record SearchExecution(
            List<SpeechRetrievalResult> results,
            SpeechRetrievalDiagnostic diagnostic
    ) {}
}
