---
name: monada-agent-task-workflow
description: Use when preparing Issues, PR plans, and reviews for Monada Resonance Store.
---

# Monada Agent Task Workflow

## Before Work

1. Read `AGENTS.md`.
2. Read `docs/vision.md`, `docs/architecture.md`, and `docs/roadmap.md`.
3. Inspect current `main` and affected modules.
4. Use `docs/specs/spec-context-template.md` for new Issues.
5. Use `docs/specs/pr-review-checklist.md` for PR review.

## Scope Estimation Before Implementation

Before editing files, list likely affected paths (including tests, docs, config, fixtures and generated output) and estimate the number per PR. Prefer <=30 files; **40 is the hard limit**. If >40 are expected, stop and define a dependency-ordered sequence of independent child Issues and PRs. Each child Issue must use Spec Context with its own allowed files, non-goals, acceptance criteria and verification; create these Issues when authorized, otherwise request approval for the decomposition. Keep each intermediate merge buildable and independently reviewable. Child PRs close only their own Issue and reference the parent; close the parent only after all children have merged and passed validation. See `AGENTS.md` and `docs/verification/pr-size-guard.md`.

Before publishing, run `node .agents/scripts/pr-size-preflight.mjs`. Local Codex/Claude Code Bash hooks can block oversized publication commands; they **cannot** guarantee early plan accuracy or intercept every publication mechanism. GitHub's required `PR file count` status check is the definitive merge gate.

## Issue Shape

Include background, current state, goal, non-goals, affected modules, boundaries, acceptance criteria, verification commands, and docs updates.

## Review Shape

Check goal alignment, scope, module boundaries, tests, evaluation output, compatibility, determinism, docs, and merge safety.
