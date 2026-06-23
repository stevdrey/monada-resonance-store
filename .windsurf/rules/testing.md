# Testing Rule

Use this rule when changing behavior.

## General

- Add focused tests for new behavior.
- Use temporary directories for storage tests.
- Keep tests deterministic.
- Avoid relying on local files outside test fixtures.

## Retrieval Changes

Include evaluation output when ranking, encoding, feedback, query keys, aliases, or lexical resources change.

Useful commands:

- `./gradlew test`
- `./gradlew :monada-evaluation:test`
- `./gradlew :monada-evaluation:runExpanded -q`
- `./gradlew :monada-evaluation:runProfileComparison -q`
- `./gradlew :monada-speech:test`
