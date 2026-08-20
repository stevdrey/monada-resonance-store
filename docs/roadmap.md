# Roadmap

## Roadmap Principle

The project follows a measurement-first path: improve retrieval quality only after the evaluation harness exposes a specific gap.

## Established Foundations

- Core domain records and vectors.
- Local file-based storage.
- Deterministic text encoding and lexical enrichment.
- Deterministic resonance ranking.
- Feedback-aware ranking with query-key strategies.
- Protected baseline evaluation.
- Versioned text evaluation snapshots with explicit protected/exploratory report labels and
  deterministic regression gates.
- Expanded exploratory evaluation.
- A/B profile comparison.
- Evaluation diagnostics.
- Controlled persisted-feedback query-key comparison with explicit transfer and contamination cases.
- Real-world project-memory normalized feedback validation with eight positive transfers, eight
  confusable negative cases, and explicit false-sharing evidence.
- Adversarial normalized feedback-key stress over 24 independent pairs, with all eight expected
  invariances preserved but seven semantically distinct collisions across coordination,
  relation/preposition, and fallback-boundary cases. The evidence signal is
  `NORMALIZED_STRESS_RISK`, so exact-query matching remains the only conservative supported behavior.
- Speech sample storage, TORGO-style import, acoustic feature encoding, and acoustic retrieval.
- Speech retrieval evaluation metrics (Precision@k, Recall@k, Hit rate@k, MRR).
- Protected speech benchmark: a CI-enforced generated-fixture baseline plus a non-enforced
  exploratory local real-data mode, with mode-labeled deterministic reports.
- Paired transcript-only versus acoustic-only speech evaluation over the same generated queries,
  with stable sample-ID alignment and complementary evidence for a controlled fusion experiment.
- Evaluation-only transcript/acoustic score-fusion sweep with exact control arms, a fixed five-point
  global weight sweep, per-query min-max normalization, explicit missing-modality handling, and no
  production ranker. The generated fixture has a stable promising region across all three non-trivial
  profiles and concludes `HYBRID_ROBUSTNESS_STUDY_JUSTIFIED`; this is evidence only for a deeper
  robustness/protected-benchmark phase.
- Fixed-profile hybrid robustness study over deterministic control/dysarthric, task, speaker-relation,
  direct-conflict, and missing-modality cases. The frozen `0.50/0.50` candidate preserves direct
  conflict recoveries but regresses when a relevant candidate lacks one modality, so the evidence
  concludes `HYBRID_CANDIDATE_RISKY`. No protected hybrid baseline, production retrieval path, or
  default behavior is justified from this result.
- Storage compatibility hardening: manifest `0.4` now separates encoding metadata from an explicit
  physical vector profile (`FLOAT32`, big-endian, framing, dimensions, and index version). Current
  and supported legacy layouts are validated before opening, while incompatible stores are rejected
  without automatic migration or byte reinterpretation.
- Storage integrity audit: Phase W introduces `StorageIntegrityAuditor` and CLI `./gradlew :monada-storage:runIntegrityAudit`,
  providing non-destructive, side-effect-free validation across manifests, atom append logs, vector frames,
  indexes, and feedback logs with a deterministic corruption taxonomy and statistics.

## Near-Term Direction

### Phase P — Speech-specific evaluation metrics

Purpose: evaluate acoustic retrieval with metrics and reports that fit speech samples.

Expected scope:

- speech retrieval fixtures;
- top-K acoustic recall metrics;
- deterministic reports;
- metadata-filter diagnostics;
- regression tests.

Non-goals:

- speech recognition integration;
- external model providers;
- changing the default text memory API.

### Phase Q — Real-world text recall corpus and query set

Purpose: create a realistic text evaluation corpus from the project's own documentation and agent-style queries.

Expected scope:

- project-memory atoms derived from README, architecture, roadmap, ADRs, specs, and glossary;
- direct, multi-relevant, paraphrase, confusable, and follow-up-task queries;
- a dedicated evaluation entry point and Gradle task;
- a baseline report showing current recall gaps without protecting metric values.

Non-goals:

- scraping external services or GitHub;
- adding private data or LLM-generated relevance judgments;
- changing retrieval defaults or encoder behavior.

### Candidate — Bounded top-K scan optimization

Improve scan efficiency only after a measurable baseline shows a real bottleneck.

## Long-Term Research Options

- approximate indexes;
- local model encoders;
- quantized vector formats;
- richer acoustic features;
- hybrid text/acoustic ranking;
- compaction and segment lifecycle;
- public speech API integration.

## Decision Rule

A roadmap item is ready when it has a Spec Context with current state, measurable problem, scope, non-goals, affected modules, acceptance criteria, verification commands, and compatibility notes.

Normalized feedback matching must not advance to a supported opt-in or protected contract from the
current evidence. Any future attempt requires a separate design Issue that changes normalization
semantics explicitly and re-runs both project-memory validation and adversarial stress evidence.
