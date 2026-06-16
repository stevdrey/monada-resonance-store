/**
 * Speech sample retrieval functionality.
 *
 * <p>This package provides acoustic similarity search capabilities for stored speech samples.
 * The main entry point is {@link com.monada.speech.retrieval.SpeechSampleRetriever}, which
 * can rank stored speech samples by their acoustic similarity to a query audio file.
 *
 * <p>Typical usage:
 * <pre>{@code
 * // Create retriever with encoder
 * AcousticFeatureEncoder encoder = new BasicAcousticFeatureEncoder(128);
 * SpeechSampleRetriever retriever = new SpeechSampleRetriever(encoder);
 *
 * // Configure search options
 * SpeechRetrievalOptions options = new SpeechRetrievalOptions(
 *     5,                    // topK
 *     SpeechDatasetSource.TORGO,  // optional dataset filter
 *     SpeechCondition.DYSARTHRIC, // optional condition filter
 *     null,                 // optional task type filter
 *     null,                 // optional speaker filter
 *     null                  // optional language filter
 * );
 *
 * // Search for similar samples
 * List<SpeechRetrievalResult> results = retriever.search(
 *     queryAudioPath,
 *     sampleStore,
 *     featureStore,
 *     options
 * );
 * }</pre>
 *
 * <p>The retrieval process:
 * <ol>
 *   <li>Encodes the query audio using the provided {@link AcousticFeatureEncoder}</li>
 *   <li>Loads all stored acoustic feature vectors from {@link SpeechFeatureStore}</li>
 *   <li>Resolves each vector to its corresponding {@link SpeechSample}</li>
 *   <li>Applies optional metadata filters</li>
 *   <li>Computes cosine similarity scores</li>
 *   <li>Returns ranked results with deterministic tie-breaking</li>
 * </ol>
 */
package com.monada.speech.retrieval;
