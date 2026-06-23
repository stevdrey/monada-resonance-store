# Vision

## Product Purpose

Monada Resonance Store is the memory layer for Monada Neuron. It stores project knowledge as `KnowledgeAtom` entries, converts content into deterministic `FrequencyVector` representations, and retrieves related entries through resonance-style similarity search.

The project exists to explore associative recall for intelligent modular systems. It favors transparent local storage, deterministic behavior, and measurable retrieval quality over opaque infrastructure.

## What This Project Is

- A local embedded Java library.
- A focused memory and retrieval subsystem.
- A place to test resonance-based recall, feedback-aware ranking, lexical enrichment, and speech/acoustic retrieval foundations.
- A research-friendly codebase where ranking changes are evaluated before they become defaults.

## What This Project Is Not

- A PostgreSQL, ArangoDB, OrientDB, Neo4j, Elasticsearch, or vector database replacement.
- A full reasoning engine.
- A distributed database.
- A cloud service.
- A generic wrapper around external embeddings.
- A production-grade storage engine yet.

## Value Proposition

Monada Resonance Store lets an intelligent system:

1. remember content as independent entries;
2. encode text or acoustic signals into comparable vectors;
3. retrieve approximate matches by resonance;
4. rank results with deterministic scoring;
5. adjust future ranking through explicit feedback;
6. measure recall quality before optimizing.

## Design Principles

- Measure before optimizing.
- Keep storage inspectable.
- Keep defaults conservative.
- Keep experiments opt-in.
- Preserve deterministic behavior.
- Keep compatibility metadata explicit.
- Prefer targeted diagnostics over hidden heuristics.

## Strategic Direction

The project is moving from a text-only associative store toward a broader memory substrate that can support both transcript-based and acoustic speech retrieval. Speech features should extend the memory model without contaminating the core text retrieval path.
