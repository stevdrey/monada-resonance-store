---
name: monada-java-26-implementation
description: Use when implementing or reviewing Java 26 code, Gradle modules, tests, APIs, immutable domain models, and repository-local implementation style for Monada Resonance Store.
---

# Monada Java 26 Implementation

## Purpose

Guide Java implementation work so changes remain idiomatic, small, testable, and consistent with the current Gradle multi-module project.

Use this skill for:

- Java 26 source changes.
- Gradle Kotlin DSL changes.
- domain models and APIs.
- JUnit tests.
- module dependency boundaries.
- implementation reviews.
- refactoring Java code without changing behavior.

## Required context before editing

Inspect:

1. root `build.gradle.kts` for Java toolchain and test configuration.
2. `settings.gradle.kts` for module boundaries.
3. module-specific `build.gradle.kts` files.
4. existing package names and public API style.
5. current tests for the module being changed.

## Project implementation constraints

- Java toolchain is Java 26.
- Build uses Gradle Kotlin DSL.
- Tests use JUnit 5.
- Keep dependencies minimal.
- Avoid introducing frameworks into core modules.
- Keep the library embedded and local-first.
- Preserve module boundaries.

## Java style guidance

Prefer:

- records for immutable data carriers.
- small final classes for behavior.
- constructor validation for invalid states.
- explicit exceptions with actionable messages.
- package-private helpers when not part of the public API.
- deterministic collections when iteration order affects output.
- primitive arrays in hot vector math when justified.

Avoid:

- mutable public fields.
- hidden global state.
- Java serialization for persisted formats.
- reflection-heavy designs.
- broad utility classes with unrelated methods.
- adding dependencies for trivial code.

## Module boundaries

Respect the current architecture:

- `monada-core`: domain primitives such as atoms, vectors, result models, and enum-like core concepts.
- `monada-encoder`: deterministic text-to-vector and lexical preprocessing.
- `monada-storage`: persistence, manifests, logs, vector files, and feedback files.
- `monada-index`: search/ranking/index implementations.
- `monada-learning`: feedback processing and ranking reinforcement logic.
- `monada-api`: developer-facing API and options.
- `monada-evaluation`: datasets, runners, reports, and quality tests.
- `monada-speech`: speech-related experiments, if present.

Do not create cross-module dependencies that invert the architecture. API may depend on lower-level modules; core should not depend on API, storage, evaluation, or speech.

## Test guidance

For new behavior, add focused tests near the changed module.

Test categories:

- domain validation.
- encoder determinism.
- lexical resource behavior.
- ranking tie ordering.
- storage round-trip.
- legacy compatibility.
- evaluation regression.
- edge cases: empty input, unicode, duplicate ids, malformed files, dimension mismatch.

Tests should not depend on execution order or existing local directories.

Use temporary directories for storage tests.

## Public API guidance

When adding public API:

- keep method names simple and domain-specific.
- avoid exposing storage internals.
- preserve existing defaults.
- add options for opt-in behavior.
- document whether a feature is conservative default or experimental opt-in.

## Acceptance checklist

A Java implementation change is acceptable only when:

- it compiles under Java 26 toolchain.
- tests are added or updated.
- public behavior is deterministic.
- module boundaries remain clean.
- exceptions explain invalid states clearly.
- no large dependency is introduced without Issue scope.
- README or docs are updated if user-facing behavior changes.

## Anti-patterns

- Implementing future architecture in the MVP path without need.
- Using static mutable registries for ranking or storage behavior.
- Mixing evaluation-only code into production modules.
- Changing defaults to pass one test.
- Adding clever Java features that reduce readability.
- Hiding compatibility behavior behind implicit reflection or service loading.
