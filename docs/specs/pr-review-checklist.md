# PR Review Checklist

Use this checklist when validating a PR against its Issue or Spec Context.

## Goal Alignment

- [ ] The PR solves the stated Issue goal.
- [ ] Scope matches the non-goals.
- [ ] Unrelated work is avoided.

## Module Boundaries

- [ ] Code changes are in the module that owns the behavior.
- [ ] Dependency direction remains clean.
- [ ] Evaluation-only code stays out of production modules.
- [ ] Speech-specific behavior stays in `monada-speech` unless scoped otherwise.

## Determinism

- [ ] Ranking tie order is stable.
- [ ] Encoders remain deterministic unless explicitly experimental.
- [ ] Tests avoid randomness and local filesystem leftovers.

## Storage Compatibility

- [ ] Persisted format changes are documented.
- [ ] Manifest metadata is updated when semantics change.
- [ ] Existing stores have an intentional compatibility path.

## Evaluation

For retrieval-quality changes:

- [ ] Baseline output is included.
- [ ] Candidate output is included.
- [ ] Per-query top-K output is included.
- [ ] Aggregate metrics are included.
- [ ] Regressions are explained.

## Tests

- [ ] New behavior has focused tests.
- [ ] Edge cases are covered.
- [ ] Relevant Gradle command output is included.

## Documentation

- [ ] README or docs are updated for user-facing behavior.
- [ ] ADR is added or updated for durable decisions.
- [ ] Skills/rules are updated if agent workflow changes.

## Merge Recommendation

State one of:

- Safe to merge.
- Safe after minor changes.
- Needs changes before merge.
- Not aligned with the Issue.

Include the smallest actionable fixes when changes are needed.
