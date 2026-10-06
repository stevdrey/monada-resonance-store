---
name: monada-java-27-implementation
description: Use when implementing Java 27 code, Gradle modules, APIs, immutable records, and tests.
---

# Monada Java 27 Implementation

## Context

Read root `build.gradle.kts`, `settings.gradle.kts`, related module builds, packages, and tests before changing Java code. Preserve the design language already used by the owning module unless the task explicitly requires an architectural change.

## Constraints

- Java toolchain is Java 27 (`JavaLanguageVersion.of(27)` in the root `build.gradle.kts`).
- Build uses Gradle Kotlin DSL with the versioned wrapper (`./gradlew`); Gradle 9.8.0 is the minimum that supports Java 27.
- Tests use JUnit 5.
- Keep dependencies minimal.
- Preserve module boundaries.
- Prefer the smallest design change that satisfies the requested behavior.

## Style

Prefer records for immutable data, small final classes for behavior, constructor validation, package-private helpers, clear exceptions, and deterministic collections when order matters.

Avoid mutable public fields, hidden global state, Java serialization for persisted formats, and large dependencies for small tasks.

### Static members

Treat `static` as a semantic design choice, not as a convenience modifier.

- Methods in behavioral/domain/service classes should be instance methods by default.
- Do **not** make a method `static` merely because its current implementation does not read instance fields or call instance methods.
- Do **not** make private helper methods `static` only to satisfy an IDE suggestion or because they can technically be invoked without an instance.
- A method should be `static` only when the operation genuinely belongs to the class rather than to an object instance, for example a deliberate utility function, named factory, stateless conversion function, or other class-level operation whose semantics are independent of object identity and lifecycle.
- Utility-style static methods should normally live in an intentionally utility-oriented type or in a type where the class-level API is clearly part of the design.
- Before adding `static`, ask whether the method would still conceptually belong to the class if its implementation later needed instance state. If yes, keep it as an instance method.
- Existing static APIs must not be converted to instance APIs, or vice versa, without a behavioral/design reason required by the task.

Good:

```java
final class ResonanceMatcher {
    private final SimilarityPolicy policy;

    double score(FrequencyVector left, FrequencyVector right) {
        return normalize(policy.similarity(left, right));
    }

    private double normalize(double score) {
        return Math.max(0.0, score);
    }
}
```

Avoid:

```java
final class ResonanceMatcher {
    private final SimilarityPolicy policy;

    double score(FrequencyVector left, FrequencyVector right) {
        return normalize(policy.similarity(left, right));
    }

    private static double normalize(double score) {
        return Math.max(0.0, score);
    }
}
```

The private helper above is part of the behavior of `ResonanceMatcher`; making it static adds no useful semantic information.

### Type names and imports

Use normal Java imports and simple type names in source code.

- Do **not** use fully qualified class names in method return types, parameter types, local variable declarations, fields, generic arguments, casts, record components, or constructor calls when a normal import can express the type unambiguously.
- Add an explicit import and use the simple type name instead.
- Fully qualified names are acceptable only when they are necessary to resolve a real simple-name collision, when Java syntax does not permit an import-based solution for the specific usage, or when an external/generated contract explicitly requires the qualified textual name.
- A hypothetical future naming collision is not a reason to use a fully qualified name.
- Prefer explicit imports over wildcard imports.

Good:

```java
import com.monada.core.FrequencyVector;

FrequencyVector encode(SpeechSample sample) {
    FrequencyVector vector = encoder.encode(sample);
    return vector;
}
```

Avoid:

```java
com.monada.core.FrequencyVector encode(SpeechSample sample) {
    com.monada.core.FrequencyVector vector = encoder.encode(sample);
    return vector;
}
```

If two required types genuinely share the same simple name, import the type used most often and qualify only the ambiguous occurrences that cannot otherwise be distinguished cleanly.

## Java 27 guidance

Java 27 is the project baseline. Use its **finalized** language and standard-library features intentionally, not decoratively.

- Adopt a newer construct only when it solves a concrete problem or clearly improves clarity, safety, maintainability, or performance, and keep the change local and reviewable.
- Do not mass-rewrite working code to showcase new syntax. Do not mix feature refactors into unrelated changes.
- Preview and incubator features (`--enable-preview`, incubator modules) are **excluded** from production code and must not be enabled globally in Gradle. If one looks useful, record it as a follow-up candidate and get separate approval.
- A JDK upgrade is a behavior-risk event: compare protected text/speech evaluation output and storage output before and after, and never update a baseline to make a new JDK pass.
- Persisted vector bytes, manifest semantics, byte order, and framing must not change because of a JDK or language-feature change.
- Locale-sensitive calls (`String.format`, `toLowerCase`, `toUpperCase`) that feed persisted or protected output should pass an explicit `Locale`.

## Implementation Review Checklist

Before considering Java changes complete, verify:

- Every new `static` method has a clear class-level semantic reason; it was not made static merely because it currently has no instance dependencies.
- Private helpers in behavioral classes remain instance methods unless they are genuinely class-level operations.
- Method signatures, fields, locals, generic arguments, casts, and constructor calls use simple type names with imports wherever unambiguous.
- Fully qualified names remain only where a concrete naming collision or external contract justifies them.
- Any Java 27 feature used is finalized (no preview/incubator) and has a stated concrete benefit.
- The code follows the existing module's object model rather than introducing utility-style design accidentally.

## Acceptance

The change compiles, tests are added or updated, public behavior is deterministic, module boundaries remain clean, docs are updated for user-facing behavior, and the implementation review checklist above has been applied.
