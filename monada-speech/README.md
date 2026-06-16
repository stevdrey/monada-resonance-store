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

## TORGO Dataset Importer

`TorgoDatasetImporter` scans a local TORGO-style directory tree and persists
valid `SpeechSample` records using `SpeechSampleStore`.

```java
var importer = new TorgoDatasetImporter();
TorgoDatasetImportReport report = importer.importFrom(
        Path.of("/data/torgo"), sampleStore);
System.out.println("imported: " + report.importedSamples());
System.out.println("skipped:  " + report.skippedSamples());
report.warnings().forEach(w -> System.out.println(w.path() + ": " + w.reason()));
```

Expected local layout (TORGO naming conventions):

```
<datasetRoot>/
  <speakerId>/         e.g. M01, FC1
    <session>/         e.g. Session1
      <taskType>/      e.g. words, sentences, commands
        <stem>.wav
        <stem>.txt     companion transcript file
```

Key properties:

- **Deterministic order**: WAV files are sorted lexicographically before import.
- **Stable IDs**: derived from the dataset-root-relative path; repeatable across runs.
- **Condition inference**: `FC*`/`MC*` → `CONTROL`; `F##`/`M##` → `DYSARTHRIC`.
- **Task type inference**: directory name `words` → `WORD`, `sentences` → `SENTENCE`, etc.
- **Skip on missing/blank transcript**: reported as a `TorgoDatasetImportWarning`.
- **No real dataset required**: tests use programmatic WAV fixtures.

> **Note:** This phase supports a conservative TORGO-style normalized layout
> (`<speakerId>/<session>/<taskType>/<file>.wav`). Not every possible real TORGO
> directory variant is handled yet — future phases may extend the importer to
> cover additional layouts.

## Out of Scope (Phase L)

This phase intentionally does NOT implement:

- ASR/Whisper/CTC/MFCC/DTW pipelines
- Changes to KnowledgeAtom, AtomType, or FileAtomStore
- Public MonadaMemory API for speech
- Speech-specific ranking or feedback

## Speech Retrieval

`SpeechSampleRetriever` enables acoustic similarity search over stored speech samples:

```java
// Create retriever with encoder
AcousticFeatureEncoder encoder = new BasicAcousticFeatureEncoder(128);
SpeechSampleRetriever retriever = new SpeechSampleRetriever(encoder);

// Configure search options
SpeechRetrievalOptions options = new SpeechRetrievalOptions(
    5,                    // topK
    SpeechDatasetSource.TORGO,  // optional dataset filter
    SpeechCondition.DYSARTHRIC, // optional condition filter
    null,                 // optional task type filter
    null,                 // optional speaker filter
    null                  // optional language filter
);

// Search for similar samples
List<SpeechRetrievalResult> results = retriever.search(
    queryAudioPath,
    sampleStore,
    featureStore,
    options
);

// Inspect ranked results
for (SpeechRetrievalResult result : results) {
    System.out.println("Rank " + result.rank() + ": " + 
        result.sample().transcript() + " (score: " + result.score() + ")");
}
```

### Complete Workflow Example

```java
// 1. Import speech samples
var importer = new TorgoDatasetImporter();
TorgoDatasetImportReport report = importer.importFrom(datasetPath, sampleStore);

// 2. Encode and persist acoustic vectors
AcousticFeatureEncoder encoder = new BasicAcousticFeatureEncoder(64);
for (SpeechSample sample : sampleStore.findAll()) {
    FrequencyVector vector = encoder.encode(sample.audioPath());
    featureStore.save(sample.id(), vector);
}

// 3. Query by acoustic similarity
Path queryWav = Path.of("/path/to/query.wav");
SpeechRetrievalOptions options = new SpeechRetrievalOptions(3, null, null, null, null, null);
List<SpeechRetrievalResult> results = retriever.search(queryWav, sampleStore, featureStore, options);

// 4. Process results
SpeechSample bestMatch = results.get(0).sample();
double similarityScore = results.get(0).score();
```

### Retrieval Characteristics

- **Acoustic similarity**: Uses cosine similarity between L2-normalized feature vectors
- **Deterministic ranking**: Results sorted by score DESC, with tie-breaking by sample ID ASC
- **Metadata filtering**: Optional filters for dataset source, condition, task type, speaker, language
- **Graceful handling**: Skips orphan vectors and dimension mismatches with warnings
- **Linear scan**: Suitable for MVP scale, matches existing `LinearScanResonanceIndex` pattern

## Future Work

- Speech-specific evaluation metrics
- Integration with monada-api for speech queries
- Advanced acoustic features (MFCC, spectral analysis)
- Hybrid transcript + acoustic ranking
