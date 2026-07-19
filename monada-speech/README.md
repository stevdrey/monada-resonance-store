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

## Corpus Import Audit

`TorgoDatasetImporter.auditFrom(datasetRoot, sampleStore)` scans a TORGO-style
corpus and returns a rich `TorgoImportAuditReport` with grouped counts and
categorised warnings.

Pass `null` as `sampleStore` for a **dry-run** scan that validates the dataset
without writing any samples:

```java
var importer = new TorgoDatasetImporter();

// Dry-run: inspect coverage without persisting anything
TorgoImportAuditReport audit = importer.auditFrom(Path.of("/data/torgo"), null);
System.out.println("dry-run: " + audit.dryRun());           // true
System.out.println("discovered: " + audit.discoveredAudioFiles());
System.out.println("would-import: " + audit.bySpeaker());   // {M01=12, FC1=8, …}
System.out.println("by condition: " + audit.byCondition()); // {DYSARTHRIC=12, CONTROL=8}
System.out.println("by task:      " + audit.byTaskType());  // {WORD=10, SENTENCE=10}

// Inspect warning categories and example paths
for (var entry : audit.warningGroups().entrySet()) {
    TorgoAuditWarningGroup g = entry.getValue();
    System.out.println(g.category() + ": " + g.count() + " files");
    g.pathExamples().forEach(p -> System.out.println("  example: " + p));
}
```

Pass a real `SpeechSampleStore` for an **import + audit** run:

```java
SpeechSampleStore store = new FileSpeechSampleStore(Path.of(".monada-speech"));
TorgoImportAuditReport audit = importer.auditFrom(Path.of("/data/torgo"), store);
System.out.println("imported: " + audit.importedSamples());
```

### Report fields

| Field | Description |
|---|---|
| `discoveredAudioFiles` | Total `.wav` files found under the dataset root. |
| `importedSamples` | Samples written to the store (0 in dry-run). |
| `skippedSamples` | Files skipped for any reason. |
| `dryRun` | `true` when no store was provided. |
| `bySpeaker` | Importable count per speaker ID. |
| `byCondition` | Importable count per `SpeechCondition`. |
| `byTaskType` | Importable count per `SpeechTaskType`. |
| `byLanguage` | Importable count per language tag (currently always `en-US`). |
| `warningGroups` | One `TorgoAuditWarningGroup` per `WarningCategory`; each group has `count` and up to 5 example paths. |

### Warning categories

| Category | Reason |
|---|---|
| `MISSING_TRANSCRIPT` | No sibling `.txt` file found. |
| `BLANK_TRANSCRIPT` | Sibling `.txt` exists but is blank after trimming. |
| `UNREADABLE_TRANSCRIPT` | Sibling `.txt` exists but threw an I/O error on read. |
| `UNREADABLE_AUDIO` | WAV file could not be parsed or has an unsupported format. |
| `UNSUPPORTED_LAYOUT` | Path does not conform to `<speaker>/<session>/<task>/<file>.wav` (e.g. file at dataset root). |
| `DUPLICATE_ID` | Two files derive the same stable sample ID within one run. |

### Running the audit from the command line

Use the existing `runSpeechBenchmark` Gradle task to perform a full import +
evaluation against a local corpus. To audit only (no evaluation), call
`auditFrom` with `null` store from a small standalone script or integrate it
into a custom `main` method under `monada-speech/src/test/java` gated by an
environment variable (see `LocalSpeechBenchmarkTest` for a pattern).

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

## Speech Evaluation

`SpeechRetrievalEvaluator` measures whether `SpeechSampleRetriever` rankings are useful
for speech-specific scenarios. It evaluates ranked results against explicit relevant
sample IDs and produces deterministic aggregate and per-query metrics.

```java
// 1. Create or load speech samples
SpeechSampleStore sampleStore = new FileSpeechSampleStore(rootPath);
SpeechFeatureStore featureStore = new FileSpeechFeatureStore(rootPath);

// 2. Encode and persist acoustic vectors
AcousticFeatureEncoder encoder = new BasicAcousticFeatureEncoder(64);
for (SpeechSample sample : sampleStore.findAll()) {
    FrequencyVector vector = encoder.encode(sample.audioPath());
    featureStore.save(sample.id(), vector);
}

// 3. Run evaluation
SpeechSampleRetriever retriever = new SpeechSampleRetriever(encoder);
SpeechRetrievalEvaluator evaluator = new SpeechRetrievalEvaluator();

var query = new SpeechEvaluationQuery(
    "q1",
    Path.of("/path/to/query.wav"),
    Set.of("expected_sample_1", "expected_sample_2"),
    new SpeechRetrievalOptions(5, null, null, null, null, null)
);

var options = new SpeechEvaluationOptions(5, true);
SpeechEvaluationReport report = evaluator.evaluate(
    List.of(query), retriever, sampleStore, featureStore, options);

// 4. Inspect aggregate metrics
System.out.println("Precision@5: " + report.precisionAtK());
System.out.println("Recall@5: " + report.recallAtK());
System.out.println("Hit rate@5: " + report.hitRateAtK());
System.out.println("MRR: " + report.meanReciprocalRank());

// 5. Inspect per-query diagnostics
for (SpeechQueryEvaluationResult result : report.queryResults()) {
    System.out.println("Query " + result.queryId() + " -> " + result.retrievedSampleIds());
    System.out.println("  missed: " + result.missedRelevantSampleIds());
}

// 6. Inspect grouped metrics
for (Map.Entry<SpeechCondition, SpeechEvaluationMetrics> entry : report.metricsByCondition().entrySet()) {
    System.out.println(entry.getKey() + " MRR: " + entry.getValue().mrr());
}
```

> **Constraint:** each query's `SpeechRetrievalOptions.topK()` must be ≥ `SpeechEvaluationOptions.k()`;
> the evaluator throws `IllegalArgumentException` otherwise.

### Metrics

- **Precision@k**: `relevant retrieved in top-k / k`
- **Recall@k**: `relevant retrieved in top-k / total relevant`
- **Hit rate@k**: `1.0` if at least one relevant sample appears in top-k, otherwise `0.0`
- **MRR**: mean reciprocal rank of the first relevant result

### Report properties

- Query results preserve input query order.
- Retrieved sample IDs preserve the ranked order from `SpeechSampleRetriever`.
- Missed relevant sample IDs are sorted deterministically.
- Grouped metrics by `SpeechCondition` and `SpeechTaskType` are included when query metadata can be resolved.

## Protected Speech Benchmark

The benchmark layer wraps `SpeechRetrievalEvaluator` in a two-mode workflow so that
retrieval-quality regressions are caught in CI without ever requiring a licensed dataset.

### Two modes

| Mode | Corpus | Enforced in CI? | Entrypoint |
| --- | --- | --- | --- |
| `PROTECTED` | deterministic generated sine-WAV fixtures | **yes** — pinned baseline gate | `GeneratedSpeechBenchmarkIntegrationTest` |
| `EXPLORATORY` | local real corpus (TORGO-style), stays outside git | **no** | `./gradlew :monada-speech:runSpeechBenchmark` |

`SpeechBenchmarkReport.render()` labels the mode and policy prominently and is fully
deterministic (no timestamps), so its output can be saved and diffed across runs.

### Threshold policy

The protected baseline pins the structural shape (corpus size, query count, `k`) and each
aggregate metric (Precision@k, Recall@k, Hit rate@k, MRR) inside
`GeneratedSpeechBenchmarkIntegrationTest`, compared via `SpeechBenchmarkBaseline` with a
tight tolerance (`1e-9`). This mirrors `EvaluationBaselineRegressionTest`: structural shape
must match exactly, metrics within tolerance. If a legitimate change alters a pinned value,
update the constants in that test **in the same commit** with a rationale.

### Running the protected mode

```bash
./gradlew :monada-speech:test          # includes the protected generated-fixture gate
```

### Running the exploratory mode (local real data)

The exploratory entrypoint imports a local TORGO-style corpus, encodes features, evaluates a
query manifest, and prints a mode-labeled report. It performs no downloads — the corpus must
already exist on disk and is never committed.

```bash
./gradlew :monada-speech:runSpeechBenchmark \
  -Dmonada.speech.benchmark.dir=/path/to/corpus \
  -Dmonada.speech.benchmark.queries=/path/to/queries.tsv
```

Configuration (system property first, then environment variable):

| Setting | System property | Environment variable | Default |
| --- | --- | --- | --- |
| corpus dir (required) | `monada.speech.benchmark.dir` | `MONADA_SPEECH_BENCHMARK_DIR` | — |
| query manifest (required) | `monada.speech.benchmark.queries` | `MONADA_SPEECH_BENCHMARK_QUERIES` | — |
| evaluation `k` | `monada.speech.benchmark.k` | `MONADA_SPEECH_BENCHMARK_K` | `5` |
| encoder dimensions | `monada.speech.benchmark.dims` | `MONADA_SPEECH_BENCHMARK_DIMS` | `64` |

Query manifest format (UTF-8; blank lines and `#` comments ignored). Each query is three
tab-separated fields — `queryId`, `queryWavPath` (absolute or relative to the corpus dir),
and a comma-separated list of relevant sample IDs (the stable IDs produced by
`TorgoDatasetImporter`, e.g. `torgo_m01_session1_words_hello`):

```
q_hello	M01/Session1/words/hello.wav	torgo_m01_session1_words_hello
```

`LocalSpeechBenchmarkTest` exercises the same exploratory path but is gated by
`@EnabledIfEnvironmentVariable(MONADA_SPEECH_BENCHMARK_DIR)` and is **skipped** when no local
corpus is configured — so CI failures come only from the protected generated-fixture
baseline, never from an absent real dataset.

### Follow-up guidance

When a real-data exploratory run reveals a recall gap, do not loosen the protected baseline.
Instead capture the exploratory report, reproduce the regression in a generated fixture if
possible, and open a focused change to the encoder/ranking with updated baseline rationale.

## Future Work

- Integration with monada-api for speech queries
- Advanced acoustic features (MFCC, spectral analysis)
- Hybrid transcript + acoustic ranking
