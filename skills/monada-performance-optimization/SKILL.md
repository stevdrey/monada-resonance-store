---
name: monada-performance-optimization
description: Use when optimizing Monada Resonance Store performance, including linear scan, top-K selection, indexing, caching, allocation reduction, I/O cost, or benchmark-driven search/storage improvements.
---

# Monada Performance Optimization

## Purpose

Guide performance work without violating the project rule: measure before optimizing.

Use this skill for:

- optimizing linear scan resonance search.
- replacing full sort with bounded top-K selection.
- reducing allocations in encoding, scoring, or storage reads.
- adding caches.
- adding candidate generation.
- exploring ANN, HNSW, LSH, or other approximate indexes.
- improving file I/O throughput.
- adding benchmarks or performance diagnostics.

## Required context before editing

Inspect:

1. `README.md` non-goals, architecture, and evaluation sections.
2. current search implementation in `monada-index`.
3. current encoding implementation in `monada-encoder`.
4. storage read/write behavior in `monada-storage`.
5. evaluation datasets and regression tests.
6. any existing timing or report output.

## Optimization principles

- Do not optimize without a measurable baseline.
- Retrieval quality must not degrade silently.
- Maintain deterministic ordering.
- Prefer local algorithmic improvements before external dependencies.
- Keep default path simple until dataset size proves it insufficient.
- Separate performance metrics from quality metrics.
- Avoid adding mutable global state.

## Required baseline before performance work

Capture at least:

- dataset size.
- atom count.
- vector dimensions.
- query count.
- top-K value.
- runtime environment if relevant.
- baseline wall-clock time or operation count.
- current quality metrics: Precision@K, Recall@K, Hit@K, MRR.

For micro-level changes, add tests or benchmarks for:

- empty store.
- small store.
- enough atoms to expose the bottleneck.
- tie ordering.

## Safe optimization sequence

1. Avoid unnecessary work.
   - compute only requested top-K.
   - skip invalid candidates early.
   - avoid repeated normalization of identical query text when safe.
2. Reduce allocation.
   - prefer primitive arrays for hot vector math.
   - avoid per-token temporary structures in hot loops when possible.
3. Improve top-K selection.
   - use a bounded heap instead of sorting all candidates when atom count grows.
4. Add indexes only after scan cost is measured.
   - keep `LinearScanResonanceIndex` as the baseline.
   - add new index behind an explicit option.
   - compare quality and speed against baseline.
5. Add external dependencies only after an Issue accepts the trade-off.

## ANN boundary

Approximate nearest-neighbor search is a future optimization, not an MVP replacement.

If adding ANN:

- keep linear scan available as the correctness baseline.
- document recall loss risk.
- compare ANN results against exact scan.
- record index metadata in the manifest.
- handle rebuilds when vectors or encoder options change.
- avoid making ANN the default until quality and compatibility are proven.

## Acceptance checklist

A performance change is acceptable only when:

- baseline and after measurements are included in the PR or Issue.
- quality metrics are unchanged or regressions are explicitly accepted.
- deterministic ordering remains stable.
- new data structures are documented.
- tests cover edge cases and tie behavior.
- no opaque dependency becomes the default without justification.

## Anti-patterns

- Replacing linear scan with ANN before measuring scan limits.
- Claiming faster performance without numbers.
- Improving average speed while breaking small-store behavior.
- Adding caches that change result ordering.
- Combining performance, storage format, and algorithm semantics in one large PR without clear boundaries.
