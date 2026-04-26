# AGENT.md

This file is a short entrypoint for Windsurf or other coding agents.

For full project context, read:

1. `README.md`
2. `AGENTS.md`
3. `.windsurfrules`
4. `.windsurf/skills/*.md`

## Project Summary

Monada Resonance Store is an experimental embedded Java memory engine for approximate knowledge retrieval.

It stores knowledge as `KnowledgeAtom`, encodes each atom into a `FrequencyVector`, and retrieves relevant atoms using resonance/similarity scoring.

The main lifecycle is:

```text
remember(content) -> encode -> persist -> resonate(query) -> recall topK atoms
```

## Current MVP Focus

Build a local, inspectable, file-based prototype with:

- deterministic encoder;
- atom storage;
- vector storage;
- linear scan resonance index;
- developer API;
- integration tests.

## Most Important Rule

Do not expand the project into a general database, web service, distributed system, or generic vector database unless explicitly requested.

Stay focused on the resonance memory concept.