---
name: monada-pr-authoring
description: Create or update evidence-based GitHub pull requests from an Issue and its Spec Context, using the Dokene PR #161 structure and an [Issue-N] title prefix.
---

# Monada Pull Request Authoring

Use when preparing, creating, or updating a pull request. The reference format is https://github.com/stevdrey/dokene/pull/161. Follow this skill even when a different agent wrote the code.

## 1. Read before writing

1. Inspect current `main`, `AGENTS.md`, relevant architecture/ADR, the source Issue, its Spec Context, and affected code/tests.
2. Compare the actual head branch with its base. Inventory changed files, behavior, migrations, docs, and outstanding work. Never describe uncommitted, hypothetical, or unrelated changes as shipped.
3. Map acceptance criteria to verified evidence, and confirm changes respect allowed modules, architectural invariants, and non-goals.
4. Review `docs/specs/pr-review-checklist.md`; for ranking/retrieval changes gather baseline and candidate evaluation results, per-query top-K, protected metrics, and regressions.

## 2. Title convention

MANDATORY when the change implements an identifiable Issue:
`[Issue-{number}] {concise imperative or descriptive summary}`

- Use the actual GitHub Issue number, never the PR number or a placeholder.
- Exactly one prefix at the beginning; do not duplicate it.
- Example: `[Issue-123] Add deterministic retrieval diagnostics`.
- If there are multiple Issues, use the primary Issue and link the others in the body.
- If there is no linked Issue, do not fabricate one or an Issue number. Use a clear descriptive title and explicitly state the missing linkage.

## 3. PR body layout (in English)

Preserve these headings and order from Dokene PR #161. Prefer specific implementation details over boilerplate:

```markdown
## What

<What was delivered and the functional boundary. Mention the source Issue: #123.>

## Why

<Motivation, relevant Spec Context, architectural decisions and relationship to current main.>

## Changes

**<Area or module>**
- <Concrete change with relevant file paths and behavior>
- <Other changed areas, including migrations, docs and tests as applicable>

## How to test / verify

- `<actual command>` — <observed outcome, with meaningful output/evidence>
- <Acceptance-criterion coverage and manual validation where applicable>
- Not run: <command and reason, if applicable>

## Risk & rollout

<Compatibility, persisted data, API changes, feature flags, deployment/rollback and known residual risks. Write 'Not applicable' with rationale only when truly appropriate.>

## Notes for reviewers

<Important invariants, tricky edge cases, decisions, non-goals respected, and focused review questions.>
<Follow-up work explicitly separated from this PR.>

Closes #123
```

- Include `Closes #N` only if the PR fully resolves Issue N and should auto-close it on merge. Otherwise use `Refs #N`.
- Explicitly explain **what**, **why**, **files/modules touched**, **tests**, **risks**, **non-goals respected**, and **follow-up work** as required by Spec Context.
- Use truthful status for each test: passed, failed, blocked or not run. Never invent command output, metrics, CI status, or approval.
- Link detailed verification docs or reports when their size would overwhelm the PR description.
- Keep a concise `What` overview, but provide enough substance in `Changes` for a reviewer to identify behavior and ownership.

## 4. Pre-publication gates

- [ ] Actual title starts with `[Issue-N] ` using the primary Issue number, if one exists.
- [ ] Body follows the six required headings in order.
- [ ] Scope, acceptance criteria, allowed files and non-goals checked against the Issue.
- [ ] Changes verified against actual diff, with no fabricated statements.
- [ ] Executed test commands and results listed; skipped tests explained.
- [ ] Storage compatibility and deterministic behavior addressed where relevant.
- [ ] Evaluation evidence included for retrieval/ranking changes.
- [ ] Known risks, rollout/rollback, reviewer focus and follow-ups documented.
- [ ] Issue closing keyword used only if scope is fully completed.

If the gates fail due to incomplete implementation or unverified critical criteria, prefer a draft PR and state the gaps plainly rather than claim readiness. Do not silently modify code to make the description appear complete.

## 5. Revisions

When new commits or reviewer feedback arrive, re-check the diff and validation evidence, and update the PR description to match current reality. Preserve the title prefix, the six headings, and Issue linkage. Do not overwrite valuable reviewer-provided context without checking it.
