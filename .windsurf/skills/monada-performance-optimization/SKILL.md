---
name: monada-performance-optimization
description: Use when optimizing scan, top-K selection, allocation, indexing, caching, or I/O after a measurable baseline exists.
---

# Monada Performance Optimization

## Context

Read `AGENTS.md`, `docs/architecture.md`, current search, encoder, storage, and evaluation code.

## Principles

- Do not optimize without a baseline.
- Keep retrieval quality visible.
- Preserve deterministic ordering.
- Keep linear scan available as the correctness baseline.
- Prefer local algorithmic improvements before new dependencies.

## Baseline Required

Capture dataset size, atom count, vector dimensions, query count, top-K, runtime or operation count, and quality metrics.

## Acceptance

Before and after measurements are included, quality is unchanged or regressions are accepted explicitly, and tests cover edge cases.
