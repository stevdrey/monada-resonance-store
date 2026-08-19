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

## Persisted Vector Compatibility

New manifests use version `0.4` and describe the physical vector format explicitly: `FLOAT32`,
big-endian byte order, `FIXED_RAW` framing, the declared dimensions, vector format version `1`,
and vector-index format version `1`. The current index is UTF-8 text with one
`atomId<TAB>byteOffset` entry per vector frame.

The runtime rejects unknown or contradictory physical metadata before reading persisted vectors;
it never guesses a new byte interpretation or rebuilds a store automatically. Manifests `0.1`,
`0.2`, and `0.3` remain readable in a documented compatibility mode: their historical
`MonadaMemory` layout is inferred as fixed-frame, big-endian `FLOAT32` with index format `1`.
They are not rewritten on open. The direct `FileFrequencyStore` legacy constructor still supports
length-prefixed vectors without a manifest, but that layout is not inferred for legacy manifests.

| Manifest | Physical metadata | Open behavior |
| --- | --- | --- |
| `0.4` | Required | Validate and use exactly as declared |
| `0.3` | Absent | Infer the historical fixed-frame format |
| `0.1`–`0.2` | Absent | Infer the same format plus existing encoding safeguards |
| Unknown version, partial metadata, or metadata on `0.1`–`0.3` | Any | Reject; explicitly rebuild if conversion is required |

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
./gradlew :monada-evaluation:runFeedbackReplay -q
./gradlew :monada-evaluation:runFeedbackQueryKeyComparison -q
./gradlew :monada-evaluation:runProjectMemoryFeedbackKeyValidation -q
./gradlew :monada-evaluation:runNormalizedQueryKeyStress -q
./gradlew :monada-evaluation:runLatency -q
./gradlew :monada-evaluation:runProjectMemory -q
./gradlew :monada-evaluation:runSpeechModalityComparison -q
./gradlew :monada-evaluation:runSpeechHybridSweep -q
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

## Persisted Feedback Replay Evaluation

The exploratory feedback replay evaluates the `ExpandedTechnologyDataset` with the same
lexical-enriched, exact-query retrieval configuration in three modes: `BASELINE` uses an empty
feedback log, `SEEDED_SYNTHETIC` preserves the profile runner's deterministic one-event-per-query
seeding, and `REPLAYED_PERSISTED` appends explicit fixture events to an isolated store's real JSONL
feedback log before re-running retrieval.

```bash
./gradlew :monada-evaluation:runFeedbackReplay -q
```

The versioned fixture is an inspectable TSV resource with seven fields in this order:

```text
queryText  queryKey  targetLabel  signal  delta  createdAt  expectedScope
```

Fields are tab-separated. Blank lines and lines beginning with `#` are ignored. Repeated rows
represent repeated persisted signals. `expectedScope` is either `MATCHING_EVALUATION_QUERY` or
`UNMATCHED_EVALUATION_QUERY`; the runner validates that declaration against the effective
evaluation query keys before writing any event. The report includes aggregate metric deltas,
per-query top-K results, and an event-by-event key-match audit.

This fixture format belongs only to `monada-evaluation`: targets use stable dataset labels that are
resolved to atom IDs during the run. The production append-only JSONL feedback format, exact-query
default, public API defaults, and protected text baseline remain unchanged.

## Feedback Query-Key Strategy Comparison

The exploratory query-key comparison replays the same deterministic feedback cases through exact,
normalized, and lexically enriched feedback keys. It uses a fresh real JSONL feedback store for
each strategy and reports intended transfer separately from false sharing or contamination of a
non-relevant target.

```bash
./gradlew :monada-evaluation:runFeedbackQueryKeyComparison -q
```

The report includes the seed and evaluation keys, full-corpus target rank and score before/after,
top-K results, per-query metric deltas, aggregate metrics, and a conservative evidence conclusion.
It is exploratory only: even a favorable result does not change the `ExactQueryKeyStrategy`
production default. Any generalized-key collision is reported as risk rather than being hidden by
aggregate recall improvements.

## Project-Memory Normalized Feedback Validation

The project-memory validation extends the controlled query-key comparison to the exploratory
project-memory v2 corpus. It replays one exact control, eight normalization-equivalent transfers,
and eight semantically distinct negative cases through isolated exact and normalized
`FileFeedbackStore` arms.

```bash
./gradlew :monada-evaluation:runProjectMemoryFeedbackKeyValidation -q
```

The versioned TSV fixture covers case, punctuation and token separators, whitespace, configured
stop-word removal, configured plural normalization, and combined normalization. Negative cases
exercise confusable module ownership, text/speech boundaries, storage layout versus compatibility,
project purpose versus non-goals, evaluation diagnostics versus runtime ranking, and Issue versus
PR-review workflow.

Every case reports effective keys, target relevance, full-corpus rank and score movement, top-K,
metric deltas, and transfer or contamination classification. The report concludes
`CONTINUE_NORMALIZED_INVESTIGATION` only when a non-exact normalized transfer improves rank, no
negative key is shared, and aggregate retrieval does not degrade. Unsafe evidence concludes
`KEEP_EXACT_ONLY`; evidence limited to exact control or no useful normalized movement concludes
`INCONCLUSIVE_KEEP_EXACT`.

The current deterministic fixture improves three normalized transfer targets, maintains six
intended cases including the exact control, isolates all eight negatives, and reports `0/8` false
sharing. This is evidence for deeper stress testing only; `ExactQueryKeyStrategy` remains the
production default and no protected baseline changes.

## Normalized Query-Key Adversarial Stress

The adversarial stress suite maps the current deterministic transformation surface of
`NormalizedQueryKeyStrategy`. It evaluates 24 independently replayed query pairs: eight
semantically equivalent invariance cases and sixteen semantically distinct cases covering
stop-word-sensitive coordination and relations, token order, numeric identity, negation,
technical terms, and the blank-normalization fallback boundary.

```bash
./gradlew :monada-evaluation:runNormalizedQueryKeyStress -q
```

Each pair runs against a fresh real `FileFeedbackStore` and reports raw queries, normalized forms,
effective feedback keys, declared relevance, full-corpus target rank and score before/after, top-K
results, and one of four classifications: `SAFE_EQUIVALENT_SHARING`, `SAFE_ISOLATION`,
`COLLISION_WITHOUT_MOVEMENT`, or `CONTAMINATION`. The category matrix reports equivalent key-match,
distinct collision, and contamination rates separately; categories without a relevant denominator
render `N/A` rather than a misleading zero.

The normal evaluation top-K and `full-corpus-rank` come from separate executions. For baseline and
replay, the report compares the normal top-K with the same-sized prefix of the diagnostic
`topK(corpusSize)` ranking. A false prefix-consistency flag warns that the diagnostic rank must not
be interpreted as equivalent to placement in the normal top-K result path.

The versioned TSV fixture uses these eleven fields:

```text
id  category  semanticRelationship  queryA  queryB  relevantTargetsA  relevantTargetsB  feedbackTargetLabel  expectedKeyRelation  delta  createdAt
```

Fields are tab-separated, target sets are comma-separated, and query fields support `\t`, `\n`,
and `\\` escapes. `expectedKeyRelation` is `MATCH`, `DISTINCT`, or `DISCOVER`; `DISCOVER` records
the actual key behavior without assuming a collision in advance.

With the current resources, all `8/8` equivalent pairs share as intended. Semantically distinct
pairs collide in `2/2` coordination cases, `4/4` relation/preposition cases, and `1/2` fallback
boundary cases. All seven collisions cause observable score or rank contamination, including
score-only cases whose full-corpus rank does not change. The decision is therefore
`NORMALIZED_STRESS_RISK`: normalized feedback matching must not become a supported opt-in contract
without a separate design issue. `ExactQueryKeyStrategy` remains the production default, and the
suite changes no normalizer resources, ranking behavior, storage format, or protected baseline.
In `mixed_whitespace_principles`, the baseline prefix is consistent, but replay moves the target
from diagnostic full-corpus rank 36 to 1 while it remains absent from the normal top-5; the report
now exposes that distinction without changing the risk decision.

## Text Encoding Contribution Diagnostics

Text evaluation can optionally explain the weighted lexical representation behind a query and a
bounded set of ranked or missed-expected atoms. Diagnostics are disabled by default, so normal
reports, protected metrics, ranking, and persisted vectors remain unchanged.

Enable the diagnostic on an existing text entry point with an environment variable:

```bash
MONADA_EVALUATION_ENCODING_DIAGNOSTICS=true \
  ./gradlew :monada-evaluation:runProjectMemory -q
```

The standard defaults show three top-ranked atoms, three missed expected atoms, and twenty unique
weighted terms per representation. Override the bounds when a smaller report is preferable:

```bash
MONADA_EVALUATION_ENCODING_DIAGNOSTICS=true \
MONADA_EVALUATION_ENCODING_DIAGNOSTICS_TOP_RESULTS=1 \
MONADA_EVALUATION_ENCODING_DIAGNOSTICS_MISSED_EXPECTED=1 \
MONADA_EVALUATION_ENCODING_DIAGNOSTICS_TERMS=6 \
  ./gradlew :monada-evaluation:runProjectMemory -q
```

The same variables apply to `run`, `runExpanded`, and `runProfileComparison`. Contribution capture
is intentionally not added to `runLatency`, because its second full-corpus diagnostic search would
distort latency measurements. Programmatic callers opt in explicitly:

```java
var diagnosticOptions = new TextEncodingDiagnosticOptions(true, 3, 3, 20);
var report = new EvaluationRunner(diagnosticOptions).run(dataset, memoryPath, memoryOptions);
```

Each term is labeled as `ORIGINAL`, `EXPANSION`, `ALIAS`, or `ALIAS_EXPANSION` and reports its
per-occurrence weight, occurrence count, and total input weight. Candidate blocks include the
observed rank and score plus exact normalized-token overlaps with the query. A missed expected
atom's rank is labeled `full-corpus-rank` because it comes from the independent diagnostic search,
not the normal top-K result. For example, the project-memory report includes:

```text
Encoding Contributions
  Query terms:
    ORIGINAL: commands weight=1.000000 occurrences=1 total=1.000000
    ORIGINAL: evaluation weight=1.000000 occurrences=1 total=1.000000
  Candidate: ka_evaluation_commands [TOP_RANKED, rank=1, score=0.317439]
    Lexical overlap: [commands, evaluation, run]
    Terms:
      ORIGINAL: evaluation weight=1.000000 occurrences=7 total=7.000000
      ORIGINAL: gradlew weight=1.000000 occurrences=7 total=7.000000
      ... omitted terms: 14
  Omitted top-ranked candidates: 4
```

Use overlaps and source weights to decide whether a future alias or lexical-weight experiment is
justified. They describe the encoder inputs, not a direct additive decomposition of cosine score:
hash-bucket collisions, signed hashing, vector normalization, and feedback adjustments can also
affect the observed score.

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

## Paired Speech Modality Comparison

The deterministic paired comparison evaluates the same semantic speech queries through the existing
text resonance path and the existing acoustic retriever, then maps both results to stable speech
sample IDs. It reports per-query and aggregate Precision@k, Recall@k, Hit@k, MRR, first relevant
rank, top-K Jaccard agreement, outcome categories, and condition/task-type groups. Transcript and
acoustic scores remain separate: this phase does not calculate a hybrid score or change either
retrieval implementation.

```bash
./gradlew :monada-evaluation:runSpeechModalityComparison -q
```

The command uses eight generated 16 kHz mono PCM WAV samples and four paired queries. The fixture
contains one transcript-only win, one acoustic-only win, one shared success, and one shared failure.
It is labeled `GENERATED_CI`, so it is distinguishable from any future `LOCAL_EXPLORATORY` corpus
run. Its deterministic evidence concludes `HYBRID_EXPERIMENT_JUSTIFIED`: a future experiment may
evaluate score fusion, but no hybrid ranking is enabled or recommended as a production default.

## Transcript + Acoustic Score-Fusion Sweep

The evaluation-only hybrid sweep reuses the same complete paired rankings and aligns candidates by
stable speech sample ID. Its five fixed global profiles retain exact transcript and acoustic control
arms, then evaluate `0.75/0.25`, `0.50/0.50`, and `0.25/0.75` transcript/acoustic weights. Every
non-trivial profile uses deterministic min-max normalization separately for each query and modality;
constant arms are reported as having no discrimination. Candidates without either required modality
are classified explicitly and excluded from non-trivial fusion rather than receiving an imputed score.

```bash
./gradlew :monada-evaluation:runSpeechHybridSweep -q
```

The report shows raw score ranges, normalization state, missing-modality IDs, top-K results, metrics,
deltas against both controls, recovered and lost cases, ties, and the evidence decision. With the
generated fixture, all three hybrid profiles recover the modality-specific and shared misses without
losing a control hit, so the deterministic conclusion is `HYBRID_ROBUSTNESS_STUDY_JUSTIFIED`. This is
evidence for a future robustness/protected-benchmark phase only: it adds no production hybrid
retriever, default behavior, storage change, or protected hybrid baseline.

## Fixed Hybrid Robustness Study

The robustness study freezes the central candidate inherited from the score-fusion sweep
(`transcript=0.50`, `acoustic=0.50`) and evaluates it without retuning against deterministic
condition, task, speaker-relation, direct-conflict, and missing-modality cases.

```bash
./gradlew :monada-evaluation:runSpeechHybridRobustness -q
```

The report keeps both single-modality controls visible for every query, compares fusion with the
stronger control by Recall@k and MRR, and separately reports grouped regressions. Its generated
fixture covers control/dysarthric, word/sentence/command, same/cross-speaker, strict transcript or
acoustic conflicts, ambiguous cases, and each missing-modality path. Incomplete candidates are
excluded from fusion without an imputed score, exactly as in the sweep.

The current generated evidence concludes `HYBRID_CANDIDATE_RISKY`: fusion preserves the two direct
conflict recoveries, but loses the relevant candidate when its transcript or acoustic feature is
unavailable while the other control remains correct. Aggregate improvements cannot override those
per-case or grouped regressions. This does not change any production behavior and does not justify a
protected hybrid benchmark or public hybrid retrieval path.

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
