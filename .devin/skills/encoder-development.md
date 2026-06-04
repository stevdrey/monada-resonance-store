# Skill: Encoder Development

Use this skill when implementing or modifying `FrequencyEncoder` implementations.

## Goal

Create deterministic, testable encoders that transform content and queries into `FrequencyVector` values suitable for resonance search.

## Core Requirements

A default encoder must be:

- deterministic;
- stable across repeated runs;
- safe for blank input;
- dimensionally consistent;
- normalized for non-empty vectors;
- easy to test.

## Recommended MVP Strategy

Use token hashing:

```text
content -> lowercase -> tokenize -> stable hash -> bucket accumulation -> normalize
```

Example conceptual flow:

```text
"Graph document database"
  -> ["graph", "document", "database"]
  -> bucket indexes
  -> accumulated vector
  -> normalized vector
```

## Avoid

- `Random` in default encoder logic;
- non-seeded randomness;
- network calls;
- external embedding providers for MVP;
- hidden mutable state;
- changing dimensions dynamically.

## Java Guidance

Prefer:

- `record` for configuration objects;
- clear validation of dimensions;
- `Locale.ROOT` for lowercase operations;
- private normalization helper;
- small pure functions.

Example normalization expectation:

```text
norm(vector) ~= 1.0 for non-empty input
```

## Testing Checklist

Add tests for:

- same input produces same vector;
- different input produces a different vector;
- blank input does not throw;
- vector length matches configured dimensions;
- non-empty vector is normalized;
- token order behavior is documented and tested if relevant.

## Architectural Rule

The encoder must not know about:

- atom storage;
- vector files;
- indexes;
- MonadaMemory API orchestration.

It should only transform input content into a `FrequencyVector`.
