# monada-speech

Storage foundation for speech/audio datasets in Monada Resonance Store.

## Purpose

This module provides infrastructure for storing labeled speech samples, transcript metadata, speaker information, and acoustic feature vectors. It prepares the foundation for ingesting datasets like TORGO while keeping speech-specific concerns separate from the core text resonance engine.

## Three-Layer Speech Storage Model

### 1. SpeechSample

Stores dataset source, speaker ID, audio file path, transcript text, condition (control/dysarthric), task type, language, and audio metadata (sample rate, channels, duration, SHA-256 hash).

```java
var sample = new SpeechSample(
    "session_001",                          // id
    "speaker_M01",                          // speakerId
    SpeechDatasetSource.TORGO,              // datasetSource
    Path.of("/data/waves/session_001.wav"), // audioPath
    "Please say hello",                   // transcript
    List.of("hello", "greeting"),         // aliases
    SpeechCondition.DYSARTHRIC,           // condition
    SpeechTaskType.SENTENCE,              // taskType
    "en-US",                              // language
    new AudioMetadata(16000, 1, 1250, "sha256..."), // audioMetadata
    Instant.now()                         // createdAt
);
```

### 2. Transcript KnowledgeAtom

The speech sample's transcript is mapped to a standard `KnowledgeAtom` with `AtomType.TEXT`. This allows the existing text resonance pipeline to work with speech transcripts without modification.

```java
var mapper = new SpeechSampleAtomMapper();
KnowledgeAtom atom = mapper.toTranscriptAtom(sample);
// atom has TEXT type, empty metadata, transcript as content
```

### 3. Acoustic Feature Vector

A `FrequencyVector` representing the speech/audio resonance signature, linked to the sample via sampleId. This enables future acoustic similarity search without modifying the text-based resonance index.

```java
var encoder = new BasicAcousticFeatureEncoder(32);
var featureStore = new FileSpeechFeatureStore(rootPath);
FrequencyVector acousticVector = encoder.encode(sample.audioPath());
featureStore.save(sample.id(), acousticVector);
```

## Acoustic Feature Encoder

`AcousticFeatureEncoder` is the contract for converting an audio file into a
`FrequencyVector`. `BasicAcousticFeatureEncoder` is the first deterministic
implementation:

- **Input**: WAV container, PCM encoding, mono or stereo, 8-bit or 16-bit
  little-endian samples. Stereo input is downmixed to mono by averaging
  channels. Unsupported formats fail with clear `IOException`s.
- **Features**: duration, average absolute amplitude, RMS energy,
  zero-crossing rate, peak amplitude, silence ratio, and an 8-window energy
  distribution.
- **Output**: features are projected into a configurable fixed-size vector and
  L2-normalized, mirroring `SimpleFrequencyEncoder` behavior.
- **Determinism**: the same audio file always produces the same vector. No
  randomness, no external ML dependencies.
- **Silent audio**: rejected explicitly with an `IOException` (zero RMS
  energy), never an ambiguous zero vector.

## Storage Layout

```
.monada-speech/
  manifest.json
  samples/
    speech-samples-000001.jsonl
  features/
    speech-features-000001.f32
  indexes/
    speech-feature-map.idx
```

## Dependencies

- `monada-core` - FrequencyVector, KnowledgeAtom, AtomType
- `monada-storage` - Storage patterns and utilities (tests only)
- `monada-encoder` - Text encoding reference (tests only)

## Out of Scope (Phase L)

This phase intentionally does NOT implement:

- ASR/Whisper/CTC/MFCC/DTW pipelines
- TORGO dataset importer (coming in future phase)
- Changes to KnowledgeAtom, AtomType, or FileAtomStore
- Public MonadaMemory API for speech
- Speech-specific ranking or feedback

## Future Work

- TORGO dataset importer
- Speech sample retrieval by acoustic resonance
- Speech-specific evaluation metrics
- Integration with monada-api for speech queries
