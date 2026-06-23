---
name: monada-java-26-implementation
description: Use when implementing Java 26 code, Gradle modules, APIs, immutable records, and tests.
---

# Monada Java 26 Implementation

## Context

Read root `build.gradle.kts`, `settings.gradle.kts`, related module builds, packages, and tests.

## Constraints

- Java toolchain is Java 26.
- Build uses Gradle Kotlin DSL.
- Tests use JUnit 5.
- Keep dependencies minimal.
- Preserve module boundaries.

## Style

Prefer records for immutable data, small final classes for behavior, constructor validation, package-private helpers, clear exceptions, and deterministic collections when order matters.

Avoid mutable public fields, hidden global state, Java serialization for persisted formats, and large dependencies for small tasks.

## Acceptance

The change compiles, tests are added or updated, public behavior is deterministic, module boundaries remain clean, and docs are updated for user-facing behavior.
