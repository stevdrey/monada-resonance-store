# Monada Resonance Store Agent Skills

This directory contains project-specific Agent Skills for agentic coding tools that support the open `SKILL.md` format.

These skills are not generic tutorials. They encode the current project direction so future implementation agents preserve the main design principle:

> Measure before optimizing, keep the storage engine simple and inspectable, and evolve retrieval quality through explicit evaluation reports.

## Available skills

| Skill | Use when |
| --- | --- |
| [`monada-resonance-algorithm-design`](monada-resonance-algorithm-design/SKILL.md) | Designing or reviewing resonance algorithms, encoders, lexical expansion, ranking decorators, feedback query keys, and recall improvements. |
| [`monada-compression-data-structures`](monada-compression-data-structures/SKILL.md) | Introducing compact vector formats, indexes, compression, or new in-memory/on-disk structures. |
| [`monada-storage-io-design`](monada-storage-io-design/SKILL.md) | Changing append-only logs, manifests, vector files, read paths, write paths, or compatibility/migration behavior. |
| [`monada-performance-optimization`](monada-performance-optimization/SKILL.md) | Optimizing ranking/search/storage performance after evaluation or profiling exposes a measurable bottleneck. |
| [`monada-evaluation-statistics`](monada-evaluation-statistics/SKILL.md) | Adding evaluation datasets, A/B reports, metrics, query diagnostics, regression thresholds, or statistical interpretation. |
| [`monada-java-26-implementation`](monada-java-26-implementation/SKILL.md) | Implementing Java 26 code, Gradle modules, tests, immutable domain models, and repository-local APIs. |
| [`monada-ml-dl-integration`](monada-ml-dl-integration/SKILL.md) | Planning embeddings, ANN, ONNX, ML/DL encoders, vector quantization, or learning-based ranking without breaking MVP constraints. |

## Expected repository context

Agents should inspect the current `main` branch before using any skill. At minimum, read:

- `README.md`
- `settings.gradle.kts`
- root `build.gradle.kts`
- module-specific code related to the task
- recent evaluation output when the task affects retrieval quality

## Skill authoring constraints

- Keep `SKILL.md` files focused and reviewable.
- Prefer deterministic, auditable behavior over opaque heuristics.
- Avoid production default changes unless compatibility and regression impact are explicitly handled.
- Do not introduce external services or heavy dependencies unless an Issue explicitly accepts that scope.
- Every quality change should have an evaluation path: baseline, expected impact, and failure diagnostics.
