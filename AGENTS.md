# AGENTS.md

## Purpose

This file is the primary repository guide for AI coding agents working on **Monada Resonance Store**.

The project is a focused associative-memory subsystem for **Monada Neuron**. It should stay local-first, deterministic, inspectable, and evaluation-driven.

## Required Context

Before changing code or creating an Issue, inspect the current `main` branch and read:

1. `README.md`
2. `docs/vision.md`
3. `docs/architecture.md`
4. `docs/roadmap.md`
5. `docs/specs/spec-context-template.md`
6. related module code and tests
7. recent evaluation output when retrieval quality is involved

Use `docs/specs/pr-review-checklist.md` when reviewing PRs.

## Devin Context Layout

- `AGENTS.md` is the root agent guide.
- `.agents/skills/<skill-name>/SKILL.md` contains the canonical Devin-compatible skills.
- `.windsurf/skills/<skill-name>/SKILL.md` mirrors the same skills for Devin Desktop and Windsurf-derived local scanning.
- `.windsurf/rules/*.md` contains local IDE/Cascade-compatible rules.
- `docs/adr/*.md` records architecture decisions.
- `docs/specs/*.md` contains task and review templates.

The previous root-level `skills/` directory has been replaced. When updating a project skill, edit the canonical `.agents/skills/` copy and then mirror the same change under `.windsurf/skills/`.

## Module Boundaries

| Module | Responsibility |
| --- | --- |
| `monada-core` | Domain primitives and shared concepts. |
| `monada-encoder` | Text normalization, lexical resources, deterministic encoding. |
| `monada-storage` | Manifest, append-only logs, vector files, indexes, feedback logs. |
| `monada-index` | Similarity search, deterministic ranking, index implementations. |
| `monada-learning` | Feedback processing and query-key strategies. |
| `monada-api` | Developer-facing API and options. |
| `monada-evaluation` | Metrics, datasets, A/B reports, diagnostics. |
| `monada-speech` | Speech samples, TORGO import, acoustic vectors, speech retrieval. |

## PR Size Planning and Enforcement

Before implementing **any** Issue, inventory likely touched paths in the current `main` branch and **estimate the total files** that each PR will change (including application code, tests, documentation, configuration, fixtures, generated files, additions, deletions and renames). Record the estimated count and file groups in the plan or Spec Context. Target **30 files or fewer** per PR. The hard maximum is **40**: 31–40 is allowed but generates a warning; 41+ cannot be merged.

If the plan is likely to exceed 40 files:

1. **Stop before coding**. Propose a dependency-ordered decomposition with each child Issue's own Spec Context, permitted paths, non-goals, acceptance criteria and tests; create child Issues only if the task authorizes it and the agent has an authorized GitHub capability.
2. Give each child a separate, coherent, independently testable PR and keep `main` buildable at every merge. Make blockers and prerequisite branches explicit; avoid circular dependencies or hidden work in large parent PRs.
3. Child PR descriptions must use `Refs #PARENT` and `Closes #CHILD`, **never** `Closes #PARENT`. Keep the parent open until all required children are merged, verified and explicitly accepted, then close it.
4. Do **not** remove essential tests/docs, introduce broken intermediate states, or move implementation into generated files to evade the limit.

During implementation and **before any push or PR creation**, run `git fetch origin main` (if needed) and `node .agents/scripts/pr-size-preflight.mjs`. The Claude Code and Codex `PreToolUse` Bash hooks run the same local script where supported. Hooks may not intercept other tools or API-based publication: **the GitHub Actions job `PR file count` and its required branch ruleset are authoritative**. See [`docs/verification/pr-size-guard.md`](docs/verification/pr-size-guard.md).

## Operating Principles

- Measure before optimizing.
- Keep changes small and reviewable.
- Preserve deterministic behavior and stable tie ordering.
- Keep public defaults conservative and backward compatible.
- Put experimental behavior behind explicit options.
- Add or update tests with meaningful behavior changes.
- Keep original user-visible content separate from encoded/searchable representations.
- Keep diagnostics informative and side-effect free.

## Storage Compatibility

When persisted vector or ranking semantics change, document:

1. metadata that identifies the new behavior;
2. compatibility for existing stores;
3. whether old vectors remain comparable with new queries;
4. the chosen path: reuse, rebuild, or reject;
5. tests proving that path.

## Evaluation Expectations

For retrieval-quality changes, include baseline and candidate output, per-query top-K results, protected metrics, and regression analysis.

Useful commands:

```bash
./gradlew test
./gradlew :monada-evaluation:test
./gradlew :monada-evaluation:run -q
./gradlew :monada-evaluation:runExpanded -q
./gradlew :monada-evaluation:runProfileComparison -q
./gradlew :monada-speech:test
```

## Issue Workflow

New Issues should use Spec Context:

- background;
- current state;
- goal;
- non-goals;
- affected modules;
- implementation boundaries;
- acceptance criteria;
- verification commands;
- expected documentation updates.

## PR Review Workflow

PR review should verify alignment with the Issue goal, scope control, module boundaries, tests, evaluation output, compatibility, determinism, documentation, and merge safety.
