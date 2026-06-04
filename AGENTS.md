# AGENTS.md

## Purpose

This file provides context and working rules for AI coding agents assisting with **Monada Resonance Store**.

Agents should treat this project as an experimental storage and retrieval engine for **Monada Neuron**, focused on associative memory, approximate recall, and resonance-based knowledge retrieval.

This is **not** a traditional relational database, document database, graph database, or generic vector database clone.

## Product Definition

Monada Resonance Store is an embedded Java-based memory engine where:

1. knowledge is stored as small units called `KnowledgeAtom`;
2. each atom is encoded into a `FrequencyVector`;
3. queries are encoded into the same representational space;
4. retrieval happens by resonance/similarity;
5. future versions can reinforce or weaken recall based on feedback.

Core idea:

```text
remember(content) -> encode -> persist -> resonate(query) -> recall topK atoms
```

## Architectural Intent

The project should remain modular and easy to reason about.

Current conceptual modules:

```text
monada-core       -> domain model and shared abstractions
monada-encoder    -> text/content to FrequencyVector conversion
monada-storage    -> local persistence of atoms, vectors, manifest and feedback
monada-index      -> resonance search and similarity indexing
monada-learning   -> feedback, reinforcement and future ranking adjustment
monada-api        -> developer-facing API
```

Agents should keep boundaries clean. Avoid leaking file-storage details into the API module or resonance scoring details into storage classes.

## MVP Boundaries

The MVP should remain focused on:

- local embedded usage;
- no external database dependency;
- file-based inspectable storage;
- deterministic encoding;
- linear scan resonance search;
- top K recall;
- tests proving the full lifecycle.

Do not introduce distributed systems, HTTP APIs, external embedding providers, ANN indexes, concurrency-heavy designs, or advanced AI features unless the task explicitly asks for them.

## Java Guidelines

Use modern Java features when they improve clarity, safety, or maintainability.

Preferred features:

- `record` for immutable data carriers;
- `sealed interface` / `sealed class` for closed domain hierarchies;
- pattern matching for `switch` when useful;
- `var` for obvious local variables only;
- text blocks for readable multiline examples/tests;
- `Optional` only for return values where absence is meaningful;
- `Path`, `Files`, `FileChannel`, and Java NIO for storage work;
- immutable collections where appropriate;
- virtual threads only when concurrency becomes relevant and justified.

Avoid using modern features just to look modern. Prefer readable domain code.

## Code Style

- Prefer small classes with single responsibility.
- Avoid premature abstraction.
- Keep APIs simple and explicit.
- Validate constructor arguments and public method inputs.
- Avoid returning mutable internal arrays directly unless intentionally documented.
- Avoid hidden global state.
- Prefer deterministic behavior over cleverness.

## Storage Design Principles

The first storage layer should be inspectable before being optimized.

Recommended disk layout:

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

Storage rules:

- prefer append-only logs for the first implementation;
- persist original content separately from vectors;
- treat `FrequencyVector` as an index representation, not the only source of truth;
- make file formats simple and documented;
- keep future compaction out of the MVP unless explicitly requested.

## Encoder Rules

Encoders must be deterministic unless explicitly marked experimental.

The same input should produce the same vector across runs.

Do not use `Random` for production encoding behavior.

Initial acceptable encoder strategy:

```text
content -> normalize -> tokenize -> stable hash -> vector bucket accumulation -> normalize vector
```

Important properties:

- stable output;
- fixed dimensions;
- safe handling of null/blank content;
- normalized non-empty vectors;
- tests for repeatability.

## Resonance / Search Rules

For v0.1, use linear scan.

Ranking should be simple and transparent:

```text
score = similarity(queryVector, atomVector)
```

Prefer cosine similarity for dense vectors.

Results should include:

- atom id;
- original content;
- resonance score;
- optional activation label in later versions.

## Testing Expectations

Every meaningful implementation should include tests.

Prioritize:

- deterministic encoder tests;
- vector dimension tests;
- vector normalization tests;
- storage append/read tests;
- full lifecycle integration test:

```text
open memory -> remember atoms -> resonate query -> get expected top result
```

Avoid tests that depend on randomness.

## Documentation Expectations

When adding a new architectural concept, update one of:

- `README.md`;
- `AGENTS.md`;
- `.windsurfrules`;
- `.windsurf/skills/*`.

Document why a design decision exists, not only what was changed.

## Preferred Development Approach

1. Keep changes small and reviewable.
2. Make behavior deterministic first.
3. Add tests before optimizing.
4. Optimize only after measuring retrieval quality.
5. Avoid scope creep.

## Current Strategic Priority

The next critical architectural milestone is improving the encoder from random vectors to deterministic normalized frequency vectors.

Without deterministic encoding, resonance is structurally functional but not meaningful.

## Feedback-Aware Ranking (v1)

Feedback ranking is available as an explicit decorator around the base resonance
index. Keep these invariants when evolving it:

- `LinearScanResonanceIndex` stays responsible for raw resonance only.
- `FeedbackAwareResonanceIndex` applies `adjusted = base + Σ delta` for events
  whose `query` string matches exactly.
- Ordering remains `adjusted DESC, atom id ASC` to preserve determinism.
- Feedback events are persisted append-only in `feedback/feedback-000001.log`
  as one JSON object per line; the log must stay inspectable.
- Feedback events may include a derived `queryKey`. Default behavior uses the
  exact query text as the key; non-exact strategies must be explicitly enabled
  through `MonadaMemoryOptions` and remain deterministic.
- The protected baseline in `EvaluationBaselineRegressionTest` and the
  feedback-aware assertions in `FeedbackAwareEvaluationTest` must not be weakened
  without an intentional baseline update and a rationale in the same commit.

## A/B Evaluation Harness (Fase E)

The project now supports multi-profile A/B evaluation via `EvaluationProfileRunner`.

Key invariants:

- Each profile runs in its own isolated subdirectory under the base path to avoid
  cross-profile contamination.
- The first profile in the list is the baseline; all others are compared against it
  using `EvaluationComparator`.
- Three standard profiles are provided as constants on `EvaluationProfile`:
  `RAW`, `LEXICAL_ENRICHED`, and `LEXICAL_ENRICHED_WITH_FEEDBACK`.
- `LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY` is exploratory and uses
  deterministic lexical feedback query keys to show whether feedback
  generalization improves, maintains, or degrades ranking.
- `RAW` uses `NoOpTextNormalizer` (no stop-word removal, no synonyms, no plurals)
  and disables feedback-aware ranking. It represents the encoder's raw capability.
- `LEXICAL_ENRICHED_WITH_FEEDBACK` seeds one deterministic positive feedback event
  per query (query text → lexicographically smallest expected atom) before measuring.
  This is intentional and deterministic; it should never be random.
- `MonadaMemory.open(path)` default behavior is unchanged: it still uses
  `LexicalEnrichmentPipeline` and enables feedback-aware ranking, equivalent to
  `MonadaMemoryOptions.defaults()`.
- `MonadaMemoryOptions` is the evaluation-oriented configuration API. The feedback
  flag controls whether `FeedbackAwareResonanceIndex` wraps the base index at query
  time; `false` skips the wrapper entirely (the raw `ResonanceIndex` is used directly).
- `RetrievalFailureClassifier` classifies non-perfect queries by failure type. These
  classifications are heuristic and informative — they guide the next improvement
  decision (more aliases, better encoder, feedback, etc.), but are not regression guards.
- The report rendered by `EvaluationProfileComparison.render()` is exploratory output.
  Do not turn its metric values into protected regression assertions unless explicitly
  asked.
- Run the comparison from the command line:
  ```
  ./gradlew :monada-evaluation:runProfileComparison -q
  ```
