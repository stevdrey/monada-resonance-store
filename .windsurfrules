# Windsurf Rules — Monada Resonance Store

## Project Identity

This project is **Monada Resonance Store**.

It is an experimental associative memory engine for Monada Neuron, not a traditional database.

The system stores knowledge as atoms, encodes atoms into frequency/vector representations, and retrieves approximate knowledge by resonance.

## Always Preserve the MVP Scope

Prioritize the current MVP:

```text
remember(content) -> encode -> persist -> resonate(query) -> recall topK atoms
```

Do not introduce major scope expansions unless explicitly requested.

Avoid adding:

- REST API;
- distributed storage;
- database server mode;
- external embedding providers;
- ANN/HNSW indexes;
- complex concurrency;
- UI;
- cloud deployment;
- plugin systems.

## Java Version and Language Features

Use the latest Java version configured by the project.

Prefer modern Java features when they make sense:

- records for immutable domain objects;
- sealed interfaces/classes for closed hierarchies;
- pattern matching for switch when it improves clarity;
- text blocks for readable multiline examples/tests;
- `var` only when the type is obvious from the right-hand side;
- Java NIO for file storage;
- virtual threads only for justified concurrency use cases.

Do not use a new language feature only for novelty. Architecture clarity wins.

## Architecture Boundaries

Respect module boundaries:

```text
monada-core       -> domain model only
monada-encoder    -> encoding content/query into FrequencyVector
monada-storage    -> persistence and disk layout
monada-index      -> similarity/resonance search
monada-learning   -> feedback and reinforcement
monada-api        -> public developer-facing API
```

Rules:

- `monada-core` must not depend on other Monada modules.
- `monada-api` may orchestrate other modules.
- storage classes must not contain scoring logic.
- encoder classes must not know about disk layout.
- index classes must not serialize atoms directly.

## Determinism First

Resonance must be reproducible.

Do not use random vectors for default encoding.

If randomness is required for an experimental encoder, make it explicit, seeded, documented, and tested.

## Testing Rules

Every feature should include tests when practical.

Prefer tests for:

- deterministic encoding;
- vector dimensions;
- vector normalization;
- atom persistence;
- vector persistence;
- topK resonance search;
- end-to-end memory lifecycle.

Avoid tests that depend on unstable random output.

## Storage Rules

For MVP storage:

- prefer append-only files;
- keep formats inspectable;
- separate atom content from frequency vectors;
- do not implement compaction unless requested;
- do not introduce external databases.

## Error Handling

Prefer explicit exceptions with useful messages.

Validate:

- null content;
- blank content;
- invalid vector dimensions;
- missing memory directory;
- corrupted or inconsistent storage files.

## Documentation Rules

When changing architecture, update documentation.

Relevant files:

- `README.md` for product/project documentation;
- `AGENTS.md` for agent-level architecture guidance;
- `.windsurfrules` for coding behavior;
- `.windsurf/skills/*.md` for reusable workflows.

## Commit / PR Guidance

Use small, focused changes.

Good PRs should explain:

- problem;
- solution;
- affected modules;
- tests;
- limitations;
- related issue.

## Current Priority

The most important near-term task is replacing random encoding with a deterministic normalized frequency encoder.