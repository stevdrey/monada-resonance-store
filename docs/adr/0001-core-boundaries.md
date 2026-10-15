# ADR 0001: Core Boundaries

## Status

Accepted

## Context

Monada Resonance Store is evolving through small phases. Without explicit module boundaries, agents may place behavior in the most convenient module instead of the module that owns the concept.

## Decision

Keep the core split by responsibility:

- `monada-core` owns stable domain primitives.
- `monada-encoder` owns content-to-vector conversion.
- `monada-storage` owns persistence and compatibility.
- `monada-index` owns search and ranking.
- `monada-learning` owns feedback processing and query-key strategies.
- `monada-api` owns developer-facing orchestration.
- `monada-evaluation` owns datasets, metrics, and reports.
- `monada-speech` owns speech-specific storage, import, acoustic features, and acoustic retrieval.

## Consequences

- Production modules remain easier to reason about.
- Evaluation code can depend on production modules, but production modules should not depend on evaluation.
- Speech can evolve without forcing changes into the text memory API.
- New features must state their affected modules in Spec Context.

## Amendment: Shared Store Codec in Core (Issue #134)

`monada-core` may hold one narrow, format-agnostic byte codec, `com.monada.core.io.ChunkedFloatReader`, shared by the vector stores of `monada-storage` and `monada-speech`. It decodes big-endian floats in bulk chunks and nothing else.

Why core: it is the only module both stores depend on, and `monada-speech` must not depend on `monada-storage`. A separate module would add a published artifact for a single class.

Limits:

- core holds no file layout, framing, paths, offsets, validation or error messages; those stay in the owning store;
- `com.monada.core.io` is exported by the automatic module but is not a supported consumer API (see [library-consumption](../specs/library-consumption.md));
- adding any other I/O code to core requires a new amendment to this ADR.

## Amendment: No-Copy Vector Read in Core (Issue #135)

`FrequencyVector.read(ChunkedFloatReader, DataInput, int dimensions)` is the second and last I/O-facing entry point
in core. It decodes `dimensions` floats through the shared reader straight into a new vector's backing array, so the
stores skip a defensive copy (see [ADR 0005](0005-frequency-vector-final-class.md)). The limits above still apply.
The method takes a positioned `DataInput` and a dimension count only. Framing, headers, offsets, validation and error
messages stay in the owning store.

## Review Checklist

A PR respects this ADR when:

- code lands in the module that owns the behavior;
- dependency direction stays clear;
- public API changes are intentional;
- evaluation-only behavior does not leak into production code;
- no I/O code is added to `monada-core` beyond the amendments above without updating this ADR.
