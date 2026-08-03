# Monada Resonance Store

> Experimental associative memory engine for **Monada Neuron**, focused on approximate recall through deterministic, inspectable resonance representations.

Monada Resonance Store is not a relational database clone, document database clone, graph database clone, or generic vector database clone. Its purpose is to provide a local embedded memory layer where project knowledge can be encoded, persisted, ranked, evaluated, and improved through measurable feedback.

## Documentation Map

| File | Purpose |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | Operating instructions for AI coding agents. |
| [`docs/vision.md`](docs/vision.md) | Product vision, goals, non-goals, and design principles. |
| [`docs/architecture.md`](docs/architecture.md) | Module boundaries, runtime flows, storage layout, and speech extension architecture. |
| [`docs/roadmap.md`](docs/roadmap.md) | Current roadmap and next safe project phases. |
| [`docs/glossary.md`](docs/glossary.md) | Shared project vocabulary. |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records. |
| [`docs/specs/`](docs/specs/) | Spec Context template and PR review checklist. |
| [`.agents/skills/`](.agents/skills/) | Canonical Devin-compatible Agent Skills. |
| [`.windsurf/skills/`](.windsurf/skills/) | Mirror of project skills for Devin Desktop / Windsurf discovery. |
| [`.windsurf/rules/`](.windsurf/rules/) | Local IDE/Cascade-compatible project rules. |

## Core Flow

```text
remember(content) -> encode -> persist -> resonate(query) -> recall topK results
```

The main domain record is `KnowledgeAtom`. Text content is encoded into `FrequencyVector` values, stored locally, and retrieved by similarity. Feedback can adjust future ranking through deterministic, append-only events.

## Module Map

| Module | Responsibility |
| --- | --- |
| `monada-core` | Domain primitives: atoms, vectors, result models, signals, and shared concepts. |
| `monada-encoder` | Deterministic text normalization, lexical enrichment, and vector encoding. |
| `monada-storage` | Local persistence: manifest, atom log, vector files, indexes, feedback logs. |
| `monada-index` | Resonance search, similarity ranking, and index implementations. |
| `monada-learning` | Feedback processing, query-key strategies, and ranking reinforcement support. |
| `monada-api` | Developer-facing API and options such as `MonadaMemory`. |
| `monada-evaluation` | Datasets, reports, metrics, A/B comparison, diagnostics, and regression tests. |
| `monada-speech` | Speech sample storage, TORGO-style import, acoustic encoding, and speech retrieval experiments. |

## Build and Test

```bash
./gradlew test
./gradlew :monada-evaluation:test
./gradlew :monada-evaluation:run -q
./gradlew :monada-evaluation:runExpanded -q
./gradlew :monada-evaluation:runProfileComparison -q
./gradlew :monada-evaluation:runLatency -q
./gradlew :monada-evaluation:runProjectMemory -q
./gradlew :monada-speech:test
./gradlew :monada-speech:runSpeechImport
./gradlew :monada-speech:runSpeechEvaluation
```

## Text Evaluation Baselines

Text evaluation reports identify their dataset, dataset version, retrieval profile, and mode.
`PROTECTED` reports use a versioned snapshot and are enforced by
`./gradlew :monada-evaluation:test`; `EXPLORATORY` reports expose current gaps but never gate CI.
The protected snapshots are readable `.properties` resources under
`monada-evaluation/src/main/resources/com/monada/evaluation/baselines/` and are listed in that
directory's `index.txt`.

The current protected policy uses exact aggregate values with an explicit floating-point
tolerance. Both improvements and regressions therefore require review. To update a baseline
intentionally:

1. Run the affected evaluation entry point and review aggregate metrics plus every query's top-K
   output.
2. If the corpus, query set, or relevance judgments changed, increment the dataset version, add a
   new versioned snapshot, and update `index.txt`.
3. If only the retrieval behavior changed intentionally, keep the dataset version and update the
   expected values in its snapshot with the rationale in the same commit or PR.
4. Run `./gradlew :monada-evaluation:test` and `./gradlew test` before submitting the change.

Snapshot metrics use `EXACT:<value>` when any drift must be reviewed and `MINIMUM:<value>` when
only regressions below a threshold should fail. Exploratory datasets must not be added to the
protected baseline registry.

## Local Speech Store Evaluation

First, create a persistent store from a local TORGO-style corpus:

```bash
./gradlew :monada-speech:runSpeechImport \
  -Dmonada.speech.import.dir=/path/to/corpus \
  -Dmonada.speech.import.store=/path/to/speech-store
```

Then evaluate that store with an inspectable TSV query file:

```bash
./gradlew :monada-speech:runSpeechEvaluation \
  -Dmonada.speech.evaluation.store=/path/to/speech-store \
  -Dmonada.speech.evaluation.queries=/path/to/queries.tsv
```

The command is exploratory only: it does not download data, modify the store, or affect the
protected speech baseline. See [`monada-speech/README.md`](monada-speech/README.md) for the TSV
format, filters, and all configuration settings.

## Latency Profiling

The `runLatency` task reports recall latency and scan diagnostics for the `ExpandedTechnologyDataset`. It records corpus size, query count, topK, scanned candidates, returned candidates, and best-effort elapsed time per query. Use this baseline to determine whether a bounded top-K or scan optimization is justified; do not optimize the scan path until the diagnostics expose a measurable bottleneck.

```bash
./gradlew :monada-evaluation:runLatency -q
```

## Design Invariants

- Measure before optimizing.
- Keep the storage engine local, embedded, inspectable, and deterministic.
- Preserve module boundaries.
- Keep compatibility metadata explicit when persisted vector semantics change.
- Keep the simple deterministic encoder and linear scan path available as baselines.
- Do not introduce external services, large dependencies, distributed systems, or model-based defaults unless a Spec Context explicitly accepts that scope.
- Retrieval quality changes must include tests, per-query evaluation, and regression analysis.

## Agent Workflow

Read [`AGENTS.md`](AGENTS.md) before making changes. New tasks should use the Spec Context format in [`docs/specs/spec-context-template.md`](docs/specs/spec-context-template.md), and PR reviews should use [`docs/specs/pr-review-checklist.md`](docs/specs/pr-review-checklist.md).

Canonical Devin-compatible skills live in `.agents/skills/<skill-name>/SKILL.md`. The same skills are mirrored under `.windsurf/skills/<skill-name>/SKILL.md` so Devin Desktop and Windsurf-derived local scanning can discover them when `.windsurf/rules` is already active. When updating a skill, edit the canonical `.agents/skills/` copy first and then mirror the same change into `.windsurf/skills/`.
