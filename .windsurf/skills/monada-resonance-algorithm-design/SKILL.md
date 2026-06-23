---
name: monada-resonance-algorithm-design
description: Use when changing encoding, lexical resources, query keys, scoring, ranking, or recall behavior.
---

# Monada Resonance Algorithm Design

## Context

Read `AGENTS.md`, `docs/architecture.md`, `monada-encoder`, `monada-index`, `monada-learning`, `monada-api`, and `monada-evaluation`.

## Principles

- Measure before optimizing.
- Keep defaults deterministic and conservative.
- Keep original query and content visible in reports.
- Keep lexical resources explicit.
- Do not change persisted vector semantics without compatibility metadata.

## Workflow

1. Identify the query or ranking gap.
2. Classify the gap.
3. Choose the smallest intervention.
4. Add diagnostics when behavior is hard to explain.
5. Validate with per-query before and after output.

## Acceptance

Tests cover the behavior, ordering remains deterministic, compatibility is addressed, and evaluation output explains the impact.
