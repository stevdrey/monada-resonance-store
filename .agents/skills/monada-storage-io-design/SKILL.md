---
name: monada-storage-io-design
description: Use when changing persistence, manifests, logs, vector files, indexes, or compatibility behavior.
---

# Monada Storage and I/O Design

## Context

Read `AGENTS.md`, `docs/architecture.md`, `monada-storage`, `monada-api`, and storage tests.

## Principles

- Keep files inspectable.
- Prefer append-friendly formats.
- Keep manifest metadata explicit.
- Preserve legacy reads unless scope says otherwise.
- Fail clearly on incompatible or incomplete data.
- Do not hide ranking behavior in storage code.

## Compatibility Questions

1. What format changed?
2. What metadata identifies it?
3. Can existing stores be read safely?
4. Should data be reused, rebuilt, or rejected?
5. Which test proves the behavior?

## Acceptance

Docs are updated, compatibility is tested, errors are clear, and no default behavior changes silently.
