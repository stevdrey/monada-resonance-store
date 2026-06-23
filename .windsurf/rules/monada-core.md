# Monada Core Rule

Use this rule when changing domain primitives.

## Boundaries

`monada-core` owns stable records and shared concepts such as atoms, vectors, result models, atom types, and feedback signals.

Do not add storage, API orchestration, evaluation datasets, or speech workflows to core.

## Expectations

- Keep domain objects immutable where practical.
- Validate invalid states at construction boundaries.
- Keep behavior deterministic.
- Avoid framework dependencies.
- Avoid exposing mutable internal arrays unless explicitly documented.

## Verification

Run focused module tests and the full test suite when public core behavior changes.
