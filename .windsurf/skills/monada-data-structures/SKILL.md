---
name: monada-data-structures
description: Use when adding compact vectors, binary layouts, indexes, caches, or data structures.
---

# Monada Data Structures

## Context

Read `docs/architecture.md`, `monada-core`, `monada-storage`, `monada-index`, and related tests.

## Principles

- Keep the MVP inspectable.
- Add compact structures only after a measurable need.
- Separate logical ids from physical offsets.
- Make formats versioned and recoverable.
- Preserve deterministic ranking.

## Acceptance

Formats are documented, metadata is sufficient, edge cases are tested, and ranking order remains stable.
