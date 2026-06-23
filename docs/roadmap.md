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
- Expanded exploratory evaluation.
- A/B profile comparison.
- Evaluation diagnostics.
- Speech sample storage, TORGO-style import, acoustic feature encoding, and acoustic retrieval.

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

### Candidate — Hybrid transcript + acoustic diagnostics

Compare transcript-only and acoustic-only retrieval profiles before creating any default hybrid ranking behavior.

### Candidate — Storage compatibility hardening

Improve manifest metadata and rejection behavior for incompatible vector formats.

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
