# Spec Context Template

Use this template when creating Issues for implementation agents.

```markdown
# <Phase / Task Title>

## Background

Explain why this task exists and how it fits the current project direction.

## Current State

Describe what already exists in `main`. Include relevant modules, classes, tests, docs, and known limitations.

## Goal

State the concrete outcome expected from this task.

## Non-Goals

List what must stay out of scope. Be explicit about features, dependencies, API changes, or optimizations that should not be included.

## Affected Modules

- `monada-core`: <yes/no and why>
- `monada-encoder`: <yes/no and why>
- `monada-storage`: <yes/no and why>
- `monada-index`: <yes/no and why>
- `monada-learning`: <yes/no and why>
- `monada-api`: <yes/no and why>
- `monada-evaluation`: <yes/no and why>
- `monada-speech`: <yes/no and why>

## Implementation Boundaries

Define the allowed design space, compatibility expectations, and constraints.

## Acceptance Criteria

- [ ] Criterion 1
- [ ] Criterion 2
- [ ] Criterion 3

## Verification Commands

```bash
./gradlew test
./gradlew :monada-evaluation:test
```

Add module-specific commands as needed.

## Evaluation Requirements

For retrieval changes, include baseline and candidate output with per-query top-K results.

## Documentation Updates

List README, docs, ADR, skill, rule, or module README updates expected from the task.

## Risks / Edge Cases

Call out compatibility, ranking, storage, deterministic ordering, or dataset risks.
```
