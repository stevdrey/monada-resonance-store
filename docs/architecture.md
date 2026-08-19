# Architecture

## Overview

Monada Resonance Store is a Gradle multi-module Java 26 project. The architecture separates domain primitives, encoding, storage, ranking, learning/feedback, API orchestration, evaluation, and speech-specific experiments.

## Module Responsibilities

| Module | Responsibility |
| --- | --- |
| `monada-core` | Domain primitives such as `KnowledgeAtom`, `FrequencyVector`, result models, atom types, and feedback signals. |
| `monada-encoder` | Deterministic text-to-vector encoding, lexical resources, aliases, stop words, plural handling, and technical synonyms. |
| `monada-storage` | Local persistence: manifests, atom logs, vector files, index files, and feedback logs. |
| `monada-index` | Similarity search, deterministic ranking, and index implementations such as linear scan. |
| `monada-learning` | Feedback aggregation, query-key strategies, and ranking reinforcement support. |
| `monada-api` | Public developer API and options, including `MonadaMemory`. |
| `monada-evaluation` | Datasets, metrics, reports, A/B comparison, diagnostics, and regression tests. |
| `monada-speech` | Speech sample metadata, TORGO-style import, acoustic features, feature storage, and acoustic retrieval. |

## Text Recall Flow

```text
input text
  -> lexical preprocessing
  -> FrequencyEncoder
  -> FrequencyVector
  -> persisted vector
  -> ResonanceIndex
  -> ranked recall results
```

The original content remains the source returned to callers. Encoded or enriched text is an index representation, not the canonical user-facing value.

## Feedback-Aware Ranking Flow

```text
query
  -> base resonance ranking
  -> derive feedback query key
  -> aggregate matching feedback events
  -> adjusted score
  -> deterministic ordering
```

Default feedback behavior should remain conservative. Broader query-key strategies can exist, but they must be explicit, deterministic, and diagnosable.

## Storage Layout

```text
monada-memory/
├── manifest.json
├── atoms/
│   └── segment-000001.log
├── vectors/
│   └── segment-000001.f32
├── indexes/
│   ├── atom-offsets.idx
│   └── vector-map.idx
└── feedback/
    └── feedback-000001.log
```

Storage must stay readable enough for debugging. Format changes should be paired with manifest metadata, compatibility tests, and clear failure behavior for incompatible stores.

### Physical Vector Format Contract

Manifest `0.4` records a `VectorFormatProfile` separately from its encoding profile. The current
profile is vector format `1`, scalar `FLOAT32`, big-endian byte order, `FIXED_RAW` framing,
manifest dimensions, and vector-index format `1`. A fixed frame therefore contains exactly
`dimensions * 4` bytes. The `vector-map.idx` v1 index is UTF-8 text with
`atomId<TAB>byteOffset` entries.

At open time, schema compatibility, encoding compatibility, physical-format compatibility, and
on-disk structure are checked independently. The runtime validates frame size, full frame
coverage, offsets, index parsing, and length headers where `LEGACY_LENGTH_PREFIXED` is declared.
Unknown versions, scalars, byte orders, framing, or index formats fail fast with a rebuild hint;
the bytes are never silently reinterpreted.

Historical manifests `0.1` through `0.3` have no physical fields. Their only supported inferred
layout is the fixed-frame big-endian `FLOAT32` form historically written by `MonadaMemory`.
Opening them does not upgrade or rewrite their manifests. The no-manifest direct storage API may
still use length-prefixed frames, but it is not a legacy-manifest inference rule.

## Speech Extension

Speech is modeled as a separate extension layer:

```text
SpeechSample metadata
  -> optional transcript KnowledgeAtom
  -> BasicAcousticFeatureEncoder
  -> speech feature vector
  -> SpeechSampleRetriever
```

Speech storage uses a separate layout:

```text
.monada-speech/
├── manifest.json
├── samples/
│   └── speech-samples-000001.jsonl
├── features/
│   └── speech-features-000001.f32
└── indexes/
    └── speech-feature-map.idx
```

Speech work should preserve separation between transcript recall and acoustic recall. Hybrid ranking can be introduced later through explicit evaluation and options.

Speech retrieval quality is guarded by a two-mode benchmark in `com.monada.speech.evaluation`. The `PROTECTED` mode runs over deterministic generated WAV fixtures and pins a baseline that is enforced in CI, mirroring the text `monada-evaluation` regression policy. The `EXPLORATORY` mode runs the same pipeline over a local real corpus that stays outside git and is never enforced in CI; it is reached only through an explicit Gradle entrypoint or an environment-gated test. Reports label their mode prominently so protected and exploratory output are never confused.

## Dependency Direction

Preferred dependency direction:

```text
monada-api
  -> monada-learning
  -> monada-index
  -> monada-storage
  -> monada-encoder
  -> monada-core
```

`monada-evaluation` may depend on production modules for testing and reporting. `monada-speech` may depend on `monada-core` and selected storage patterns, but speech-specific concepts should not leak back into core APIs without explicit scope.

## Compatibility Boundary

Any change that affects persisted vectors must answer:

- Which encoder/options created the stored vector?
- Are stored vectors comparable with newly encoded queries?
- Does the manifest identify the format and semantic version?
- Should the store be reused, rebuilt, or rejected?

Silent semantic drift is treated as an architecture bug.
