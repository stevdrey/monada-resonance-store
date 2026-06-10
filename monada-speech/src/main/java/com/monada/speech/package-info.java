/**
 * Monada Speech Module - Storage foundation for speech/audio datasets.
 *
 * <p>This module provides infrastructure for storing labeled speech samples,
 * transcript metadata, speaker information, and acoustic feature vectors.
 * It prepares the foundation for ingesting datasets like TORGO while keeping
 * speech-specific concerns separate from the core text resonance engine.
 *
 * <h2>Three-Layer Speech Storage Model</h2>
 *
 * <ol>
 *   <li><b>SpeechSample</b> - Stores dataset source, speaker ID, audio file path,
 *       transcript text, condition (control/dysarthric), task type, language,
 *       and audio metadata (sample rate, channels, duration, SHA-256 hash).
 *       Located in {@link com.monada.speech.domain}.</li>
 *
 *   <li><b>Transcript KnowledgeAtom</b> - The speech sample's transcript is mapped
 *       to a standard {@link com.monada.core.KnowledgeAtom} with
 *       {@link com.monada.core.AtomType#TEXT}. This allows the existing text
 *       resonance pipeline to work with speech transcripts without modification.
 *       The atom contains empty metadata; audio details remain in SpeechSample.
 *       Use {@link com.monada.speech.bridge.SpeechSampleAtomMapper} for conversion.</li>
 *
 *   <li><b>Acoustic Feature Vector</b> - A {@link com.monada.core.FrequencyVector}
 *       representing the speech/audio resonance signature, linked to the sample via
 *       sampleId. This enables future acoustic similarity search without modifying
 *       the text-based resonance index. Stored via
 *       {@link com.monada.speech.storage.SpeechFeatureStore}.</li>
 * </ol>
 *
 * <h2>Storage Layout</h2>
 *
 * <pre>
 * .monada-speech/
 *   manifest.json
 *   samples/
 *     speech-samples-000001.jsonl
 *   features/
 *     speech-features-000001.f32
 *   indexes/
 *     speech-feature-map.idx
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li><b>Inspectability</b> - JSONL format for samples, raw float32 for vectors</li>
 *   <li><b>Determinism</b> - Last-wins semantics for duplicate sample IDs</li>
 *   <li><b>Separation of Concerns</b> - Audio metadata never leaks into text atoms</li>
 *   <li><b>Extensibility</b> - Foundation for TORGO, UA-Speech, and custom datasets</li>
 * </ul>
 *
 * <h2>Out of Scope (Phase L)</h2>
 *
 * <ul>
 *   <li>No ASR/Whisper/CTC/MFCC/DTW implementation</li>
 *   <li>No TORGO importer (that comes in a future phase)</li>
 *   <li>No changes to KnowledgeAtom, AtomType, FileAtomStore</li>
 *   <li>No public MonadaMemory API for speech yet</li>
 * </ul>
 *
 * @see com.monada.speech.domain.SpeechSample
 * @see com.monada.speech.storage.SpeechSampleStore
 * @see com.monada.speech.storage.SpeechFeatureStore
 * @see com.monada.speech.bridge.SpeechSampleAtomMapper
 */
package com.monada.speech;
