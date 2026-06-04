# Skill: Architecture Review

Use this skill when modifying or reviewing design decisions in Monada Resonance Store.

## Goal

Ensure changes preserve the project identity:

```text
associative memory + resonance retrieval + Java embedded storage
```

## Review Checklist

### Product Fit

- Does the change support approximate knowledge recall?
- Does it preserve the `remember -> encode -> persist -> resonate -> recall` lifecycle?
- Does it avoid turning the project into a generic database or web service?

### Module Boundaries

Check that responsibilities remain separated:

- `monada-core`: domain model only;
- `monada-encoder`: vector/frequency generation only;
- `monada-storage`: persistence only;
- `monada-index`: similarity search only;
- `monada-learning`: feedback/reinforcement only;
- `monada-api`: orchestration/public API only.

### Simplicity

Prefer the simplest design that proves the concept.

Avoid:

- unnecessary frameworks;
- premature optimization;
- distributed architecture;
- concurrency unless required;
- complex configuration.

### Determinism

Any default behavior affecting recall must be deterministic.

Ask:

- Will the same input generate the same vector?
- Will the same stored atoms produce repeatable results?
- Can this behavior be tested?

### Observability for MVP

For the MVP, favor readable/debuggable behavior:

- inspectable files;
- clear logs only when useful;
- simple scores;
- understandable failure messages.

## Output Expected

When reviewing, summarize:

1. architectural fit;
2. module boundary concerns;
3. MVP scope concerns;
4. testing gaps;
5. recommended next action.
