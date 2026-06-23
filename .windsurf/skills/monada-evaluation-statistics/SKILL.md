---
name: monada-evaluation-statistics
description: Use when adding or interpreting datasets, metrics, diagnostics, A/B reports, or regression checks.
---

# Monada Evaluation and Statistics

## Context

Read `AGENTS.md`, `docs/architecture.md`, `docs/roadmap.md`, and `monada-evaluation`.

## Principles

- Measure before optimizing.
- Separate protected baselines from exploratory datasets.
- Report per-query results, not only averages.
- Keep original query text visible.
- Do not hide regressions behind aggregate improvements.

## Required Report Shape

For retrieval changes, include baseline, candidate, commands, dataset, aggregate metrics, per-query top-K results, improved queries, regressed queries, and merge recommendation.

## Metrics

Use Precision@K, Recall@K, Hit@K, Mean Reciprocal Rank, and score diagnostics carefully.

## Acceptance

Reports are reproducible, diagnostics explain changed behavior, and protected baseline changes have a clear rationale.
