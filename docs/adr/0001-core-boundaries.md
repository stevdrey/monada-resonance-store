# ADR 0001: Core Boundaries

## Status

Accepted

## Context

Monada Resonance Store is evolving through small phases. Without explicit module boundaries, agents may place behavior in the most convenient module instead of the module that owns the concept.

## Decision

Keep the core split by responsibility:

- `monada-core` owns stable domain primitives.
- `monada-encoder` owns content-to-vector conversion.
- `monada-storage` owns persistence and compatibility.
- `monada-index` owns search and ranking.
- `monada-learning` owns feedback processing and query-key strategies.
- `monada-api` owns developer-facing orchestration.
- `monada-evaluation` owns datasets, metrics, and reports.
- `monada-speech` owns speech-specific storage, import, acoustic features, and acoustic retrieval.

## Consequences

- Production modules remain easier to reason about.
- Evaluation code can depend on production modules, but production modules should not depend on evaluation.
- Speech can evolve without forcing changes into the text memory API.
- New features must state their affected modules in Spec Context.

## Review Checklist

A PR respects this ADR when:

- code lands in the module that owns the behavior;
- dependency direction stays clear;
- public API changes are intentional;
- evaluation-only behavior does not leak into production code.
