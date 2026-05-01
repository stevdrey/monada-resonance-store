# Monada Resonance Store

> An experimental associative knowledge storage engine for **Monada Neuron**, focused on approximate knowledge retrieval through resonance-based frequency representations.

## 1. Product Vision

Monada Resonance Store is not intended to be a traditional relational database, document database, graph database, or vector database clone.

Its purpose is to provide a **memory layer for intelligent modular systems**, where knowledge is stored as small independent units called **Knowledge Atoms**. Each atom is transformed into a high-dimensional frequency representation, and queries are also transformed into the same representational space. Retrieval happens by **resonance**, meaning the system returns knowledge that is semantically, structurally, or contextually close to the query.

In simple terms:

```text
Traditional database:
Find records where field equals value.

Monada Resonance Store:
Find knowledge that resonates with this idea, question, or context.
```

## 2. Why This Project Exists

Monada Neuron needs a memory system that behaves more like associative recall than exact record lookup.

A conventional database is excellent when the question is precise:

```sql
SELECT * FROM users WHERE country = 'CR';
```

But Monada Neuron needs to answer questions like:

```text
What knowledge do we have that can help explain graph/document databases?
```

or:

```text
Retrieve memories related to approximate reasoning, frequency representation, and knowledge resonance.
```

This project explores a storage model where retrieval is based on activation, similarity, and approximation.

## 3. Product Owner Perspective

### Target User

The first target user is a developer or researcher experimenting with:

- intelligent modular systems;
- knowledge stores;
- approximate memory retrieval;
- hyperdimensional computing concepts;
- semantic search;
- custom AI memory layers;
- Java-based experimental infrastructure.

### Core Value Proposition

Monada Resonance Store allows an intelligent system to:

1. remember knowledge as small reusable atoms;
2. encode knowledge into frequency-like representations;
3. retrieve approximate knowledge by resonance;
4. rank recalled atoms by activation score;
5. eventually improve recall through feedback and reinforcement.

### Non-Goals for the MVP

The MVP will **not** try to be:

- a PostgreSQL replacement;
- an ArangoDB replacement;
- an OrientDB replacement;
- a general-purpose vector database;
- a full AI reasoning engine;
- a distributed database;
- a production-grade storage engine.

The MVP is a focused experimental memory engine.

## 4. MVP Definition

### MVP Goal

Build a local embedded Java library capable of:

```text
remember(text) -> store a Knowledge Atom
resonate(query) -> retrieve the most relevant atoms
feedback(atom, query, signal) -> adjust future ranking
```

The first version should prove that a set of stored knowledge atoms can be retrieved by approximate resonance.

### MVP Features

#### 1. Knowledge Atom Storage

The system must store small pieces of knowledge:

```json
{
  "id": "ka_001",
  "type": "CONCEPT",
  "content": "ArangoDB is a multi-model database with document, graph and search capabilities.",
  "metadata": {},
  "createdAt": "2026-04-24T10:00:00Z",
  "weight": 1.0
}
```

#### 2. Frequency Encoding

Each atom must be encoded into a frequency/vector representation:

```text
content -> FrequencyEncoder -> FrequencyVector
```

Initial implementation may use a simple deterministic encoder. Later versions can support embeddings, hypervectors, or custom frequency encoders.

#### 3. Resonance Search

A query must be encoded into the same vector space and compared against stored atoms:

```text
query -> query vector -> compare with atom vectors -> top K results
```

Initial implementation can use linear scan.

#### 4. Ranking

Results must include a resonance score:

```json
{
  "atomId": "ka_002",
  "score": 0.91,
  "activation": "STRONG"
}
```

#### 5. Feedback Log

The system must allow feedback to be stored:

```text
POSITIVE feedback strengthens future recall.
NEGATIVE feedback weakens future recall.
```

### MVP Acceptance Criteria

The MVP is considered successful when:

- a developer can create a local memory store;
- a developer can store at least 100 knowledge atoms;
- a developer can query the store using natural text;
- the system returns top K atoms with resonance scores;
- feedback can be persisted and applied to ranking;
- the storage format is simple, inspectable, and documented;
- no external database is required.

## 5. Project Manager Perspective

### Initial Milestones

#### Milestone 1 — Conceptual Documentation

Status: In progress

Deliverables:

- project README;
- MVP definition;
- architecture overview;
- storage model proposal;
- initial glossary.

#### Milestone 2 — Core Domain Model

Deliverables:

- `KnowledgeAtom`;
- `FrequencyVector`;
- `ResonanceResult`;
- `AtomType`;
- basic Java module structure.

#### Milestone 3 — File-Based Storage

Deliverables:

- append-only `atoms.log`;
- binary or JSON vector storage;
- atom index;
- vector index;
- manifest file.

#### Milestone 4 — Basic Encoder and Search

Deliverables:

- simple deterministic frequency encoder;
- cosine similarity;
- linear scan resonance search;
- top K retrieval.

#### Milestone 5 — Feedback and Ranking

Deliverables:

- feedback log;
- positive/negative feedback model;
- ranking adjustment based on atom weight and feedback.

#### Milestone 6 — Developer API

Deliverables:

- `MonadaMemory.open(path)`;
- `remember(text)`;
- `resonate(query)`;
- `topK(k)`;
- `threshold(score)`;
- `feedback(...)`.

#### Milestone 7 — Evaluation Dataset

Deliverables:

- sample knowledge dataset;
- sample queries;
- expected results;
- basic Precision@K measurement.

## 6. Architect Perspective

### High-Level Architecture

```text
Monada Resonance Store
│
├── monada-core
│   ├── KnowledgeAtom
│   ├── FrequencyVector
│   ├── ResonanceResult
│   └── AtomType
│
├── monada-encoder
│   ├── FrequencyEncoder
│   ├── SimpleFrequencyEncoder
│   ├── EmbeddingFrequencyEncoder      future
│   └── HyperVectorFrequencyEncoder    future
│
├── monada-storage
│   ├── AtomStore
│   ├── FileAtomStore
│   ├── FrequencyStore
│   ├── ManifestStore
│   └── FeedbackStore
│
├── monada-index
│   ├── ResonanceIndex
│   ├── LinearScanResonanceIndex
│   └── ApproximateResonanceIndex      future
│
├── monada-learning
│   ├── FeedbackProcessor
│   └── ResonanceReinforcer
│
└── monada-api
    ├── MonadaMemory
    ├── MonadaQuery
    └── MonadaRecall
```

### Core Runtime Flow

#### Remember Flow

```text
remember(content)
    ↓
Create KnowledgeAtom
    ↓
Encode content into FrequencyVector
    ↓
Append atom to atoms.log
    ↓
Append vector to frequencies file
    ↓
Update atom index
    ↓
Update vector index
```

#### Resonance Flow

```text
resonate(query)
    ↓
Encode query into FrequencyVector
    ↓
Search nearest atom vectors
    ↓
Calculate resonance score
    ↓
Apply feedback/ranking adjustments
    ↓
Read selected atoms
    ↓
Return MonadaRecall
```

## 7. Proposed Disk Layout

The first storage implementation should be local, simple, and inspectable.

```text
monada-memory/
│
├── manifest.json
│
├── atoms/
│   └── segment-000001.log
│
├── vectors/
│   └── segment-000001.f32
│
├── indexes/
│   ├── atom-offsets.idx
│   └── vector-map.idx
│
└── feedback/
    └── feedback-000001.log
```

### `manifest.json`

Stores metadata about the memory store:

```json
{
  "storeName": "monada-memory",
  "version": 1,
  "vectorType": "DENSE_FLOAT32",
  "dimensions": 384,
  "atomCount": 0,
  "createdAt": "2026-04-24T10:00:00Z"
}
```

### `atoms/segment-000001.log`

Append-only JSON lines file:

```json
{"id":"ka_001","type":"CONCEPT","content":"OrientDB is a multi-model database that combines graph and document models.","weight":1.0}
{"id":"ka_002","type":"CONCEPT","content":"ArangoDB is a multi-model database with document, graph and search capabilities.","weight":1.0}
```

### `vectors/segment-000001.f32`

Binary file containing fixed-size float vectors.

Example with 4 dimensions for illustration:

```text
ka_001 -> [0.90, 0.85, 0.20, 0.10]
ka_002 -> [0.88, 0.82, 0.15, 0.75]
ka_003 -> [0.10, 0.20, 0.95, 0.05]
```

If dimensions = 4:

```text
4 floats * 4 bytes = 16 bytes per vector
```

### `indexes/atom-offsets.idx`

Maps atom IDs to physical offsets in the atom log:

```text
ka_001 -> offset=0, length=155
ka_002 -> offset=156, length=160
```

### `indexes/vector-map.idx`

Maps vector positions to atom IDs:

```text
0 -> ka_001
1 -> ka_002
2 -> ka_003
```

### `feedback/feedback-000001.log`

Append-only feedback events:

```json
{"query":"graph document database","atomId":"ka_001","feedback":"POSITIVE","delta":0.05}
{"query":"graph document database","atomId":"ka_003","feedback":"NEGATIVE","delta":-0.10}
```

## 8. Initial Java API Design

```java
var memory = MonadaMemory.open("./monada-memory");

memory.remember("""
    OrientDB is a multi-model database that combines graph and document models.
""");

memory.remember("""
    ArangoDB is a multi-model database with document, graph and search capabilities.
""");

var recall = memory.resonate("""
    database with graph and document model
""")
.topK(5)
.threshold(0.70)
.execute();

for (var result : recall.results()) {
    System.out.println(result.score());
    System.out.println(result.atom().content());
}
```

Expected result:

```text
0.94 - ArangoDB is a multi-model database with document, graph and search capabilities.
0.91 - OrientDB is a multi-model database that combines graph and document models.
```

## 9. Suggested Technology Stack

Initial stack:

- Java 26
- Gradle Kotlin DSL
- JUnit 5
- Jackson for JSON serialization
- Java NIO for file storage
- No external database for MVP

Possible future additions:

- RocksDB for storage;
- HNSW or LSH for approximate nearest-neighbor search;
- ONNX Runtime or external embedding providers;
- custom Hyperdimensional Computing encoder;
- CLI interface;
- REST API.

## 10. Metrics and Evaluation

The project should be evaluated using measurable retrieval quality.

Initial metrics:

- Precision@K;
- Recall@K;
- Mean Reciprocal Rank;
- average resonance score;
- feedback improvement over time.

Example evaluation query:

```text
Query:
"database that combines graph and document storage"

Expected relevant atoms:
- OrientDB atom
- ArangoDB atom
```

## 10.1 Running the Evaluation Harness

The `monada-evaluation` module contains a small benchmark dataset and a runner that
calculates **Precision@K** using a `MonadaMemory` opened at the configured storage
path. To evaluate against a fresh memory state, use a new or empty directory (for
example, a temporary directory) for each run; reusing a non-empty directory may reuse
previously stored atoms and vectors and affect the results.

Run all evaluation tests:

```bash
./gradlew :monada-evaluation:test
```

Print a human-readable evaluation report to standard output:

```bash
./gradlew :monada-evaluation:run -q
```

The report includes per-query results and aggregate averages for `K = 1, 3, 5`. The
default dataset lives in
`com.monada.evaluation.datasets.DefaultDatabasesDataset` and can be replaced by any
custom `EvaluationDataset` when invoking `EvaluationRunner.run(dataset, path)`.

This harness is intentionally minimal. It exists to provide a reproducible baseline
so future encoder or index changes can be compared objectively.

**Protected baseline and deterministic ranking.** The `DefaultDatabasesDataset` is now
treated as a regression baseline: `EvaluationBaselineRegressionTest` asserts exact
baseline values for `Hit@1`, `Recall@3`, `Recall@5`, and `Mean Reciprocal Rank`.
Resonance results are ordered deterministically by score descending and then by atom
id ascending, so top-K output is stable across runs and fresh memory directories even
when scores tie.

## 10.2 Feedback-Aware Ranking

Monada Resonance Store supports a first iteration of **feedback-aware ranking**. Clients can record positive or negative feedback against an atom id for a specific query; future queries that match that exact text re-rank the base resonance results using the aggregated feedback delta.

```java
memory.feedback("sql relational transactions database",
                atom.id(),
                FeedbackSignal.POSITIVE); // default delta = +0.05

memory.feedback("sql relational transactions database",
                unrelated.id(),
                FeedbackSignal.NEGATIVE, -0.2); // explicit delta
```

The ranking formula applied by `FeedbackAwareResonanceIndex` is:

```text
adjustedScore(atom, query) = baseScore(atom, query) + Σ delta(event)
    over all events where event.query == query and event.atomId == atom.id
```

Key properties:

- **Deterministic ordering**: results are sorted by `adjustedScore DESC`, then by `atomId ASC`.
- **Exact query matching (v1)**: only events with a string-equal `query` field influence ranking.
- **Threshold-safe promotion**: positive feedback can surface an atom that would otherwise sit just below the caller threshold, because the decorator pulls an expanded candidate pool from the base index and then applies the caller threshold to the adjusted score.
- **Auditable**: every event is appended to `feedback/feedback-000001.log` as one JSON object per line, including the `createdAt` timestamp.

To run the feedback-aware regression test, which asserts that feedback never degrades the protected baseline metrics (`Hit@1`, `Recall@3`, `Recall@5`, `MRR`):

```bash
./gradlew :monada-evaluation:test --tests com.monada.evaluation.FeedbackAwareEvaluationTest
```

**Note on Recall@K**: when a query has more expected labels than `K`, the maximum
achievable `Recall@K` is `K / |expected|`. For example, a query with two expected
labels can never exceed `Recall@1 = 0.5`. This is the standard IR definition; small
values at low `K` do not necessarily indicate an encoder regression.

## 11. Glossary

### Knowledge Atom

The smallest unit of stored knowledge.

### Frequency Vector

A high-dimensional representation of a Knowledge Atom or query.

### Resonance

The similarity or activation strength between a query vector and an atom vector.

### Recall

The result of a resonance query.

### Activation Score

A numeric score that indicates how strongly an atom resonates with a query.

### Feedback

A signal that strengthens or weakens future recall behavior.

### Clean-up Memory

A future component that reduces noise and stabilizes activated knowledge.

## 12. Roadmap

### v0.1 — Local Memory Prototype

- File-based atom storage;
- simple frequency encoder;
- linear scan search;
- top K results;
- basic Java API.

### v0.2 — Feedback-Aware Recall

- feedback log;
- atom weights;
- adjusted ranking.

### v0.3 — Better Encoders

- configurable encoders;
- simple embedding encoder;
- experimental hypervector encoder.

### v0.4 — Evaluation Harness

- sample dataset;
- query set;
- Precision@K reports.

### v0.5 — Scalable Resonance Index

- approximate nearest-neighbor search;
- faster retrieval for larger memories.

### v1.0 — Stable Experimental API

- documented storage format;
- stable Java API;
- CLI;
- test coverage;
- benchmark results.

## 13. Design Principles

1. Start small and measurable.
2. Prefer inspectable storage before optimized storage.
3. Keep the API simple.
4. Separate exact storage from resonance retrieval.
5. Treat frequency representation as an index, not as the only source of truth.
6. Make feedback explicit and auditable.
7. Avoid claiming deterministic truth from approximate recall.
8. Optimize only after retrieval quality is measurable.

## 14. Current Status

This project is currently in the conceptual and MVP definition stage.

Next recommended step:

```text
Create the initial Java/Gradle project structure and implement:
- KnowledgeAtom
- FrequencyVector
- SimpleFrequencyEncoder
- FileAtomStore
- LinearScanResonanceIndex
- MonadaMemory API
```
