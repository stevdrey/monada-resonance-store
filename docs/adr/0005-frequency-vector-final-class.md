# ADR 0005: FrequencyVector as a Final Class with a No-Copy Store Read

## Status

Accepted (Issue #135)

## Context

`FrequencyVector` was a `record` whose compact constructor copies its `float[]` (`Arrays.copyOf`) and whose
`values()` accessor copies again. The vector stores (`FileFrequencyStore`, `FileSpeechFeatureStore`) decode every
stored vector into a fresh `float[]` with `ChunkedFloatReader`, then call `new FrequencyVector(values)`, which
copies that array a second time. Both stores load every vector on every query (`LinearScanResonanceIndex`,
`SpeechSampleRetriever`), so the extra copy is paid per query.

A record cannot skip its canonical constructor: every other constructor must delegate to it. So a record cannot
offer a no-copy construction path.

Stage 1 of Issue #135 measured the copy with `./gradlew :monada-evaluation:runVectorCopyShare -q`. At N=10,000,
the copy accounted for:

| Store | Dimensions | Share of the copying path's bytes | Bytes per call | Share of p50 |
| --- | --- | --- | --- | --- |
| `FileFrequencyStore` | 128 | 37.4% | 5,280,000 (N × 528) | ~6% |
| `FileSpeechFeatureStore` | 64 | 29.7% | 2,720,000 (N × 272) | ~1% |

Shares are measured against the copying replica, which keeps the pre-#135 materialization, so rerunning the command
after this change still reports the same shares. Production now matches the adopting replica.

These figures come from one machine with a warm page cache, so they are exploratory. The decision rule was: at
least one store reaches ≥ 10% of both p50 and p95 (one noisy percentile cannot trigger it) or ≥ 30% of allocated bytes. Without allocation data, a time share below the
threshold is inconclusive. The frequency store meets the allocation threshold. The time share is modest and noisy.

## Decision

- `FrequencyVector` becomes a `public final class` with the same public API:
  - `FrequencyVector(float[])` still rejects `null` (`NullPointerException`) and empty arrays (same message), and
    still copies its input.
  - `values()` still returns a copy.
  - `dimensions()` and `dotProduct()` are unchanged.
  - `equals`, `hashCode` and `toString` are unchanged: `Arrays.equals`, `Arrays.hashCode`, and the
    `FrequencyVector[...]` format.
- A new store codec entry point, `FrequencyVector.read(ChunkedFloatReader, DataInput, int dimensions)`:
  - allocates the backing array internally;
  - fills it through the final `ChunkedFloatReader`, which keeps no reference to the destination array (the
    `DataInput` only ever sees the reader's scratch `byte[]`);
  - adopts the array without a copy.
  - No caller can obtain a reference to the backing array, so vectors stay immutable.
  - It is meant for the vector stores only and shares the support status of `com.monada.core.io`.
- `FileFrequencyStore.readVector` and `FileSpeechFeatureStore.readVector` call `FrequencyVector.read`. Header
  validation, the `EOFException` → truncated-segment message mapping, ordering and results are unchanged.

## Compatibility

- **Source:** compatible for every existing use in this repository and for consumers that use the constructor,
  accessor, `equals`, `hashCode` or `toString`. There is no record pattern matching on `FrequencyVector` anywhere
  in the repository.
- **Binary:** the superclass changes from `java.lang.Record` to `Object`. Code that relied on record-ness is
  affected: `instanceof Record`, record deconstruction patterns, `Class.isRecord()` and
  `getRecordComponents()`. None exists in the repository. External consumers are on `0.1.0-SNAPSHOT`, so this is
  noted rather than versioned.
- **Serialization:** `FrequencyVector` was not `Serializable` and still is not.
- **Storage (AGENTS.md):**
  1. no new metadata, since this is an in-memory construction change only;
  2. existing stores are read unchanged;
  3. values are bit-identical, so old vectors stay comparable;
  4. path: **reuse**;
  5. proof: both store read-parity tests pass unchanged, and `FrequencyVectorTest` checks bit-identity with
     `ChunkedFloatReader.readFloats` (NaN payloads, chunk boundaries 4,095/4,096/4,097).
- **Modules:** `FrequencyVector` (`com.monada.core`) now names `com.monada.core.io.ChunkedFloatReader` in a
  public signature. A future explicit `module-info` must keep `com.monada.core.io` exported at least to the
  storage and speech modules, for example as a qualified export.

## Consequences

- One fewer `float[dims]` per loaded vector: the stores' `findAll()` allocation drops by about
  N × (16 + 4 × dims) bytes.
- `FrequencyVector` loses record auto-generation. Its members are now hand-written and are pinned by
  `FrequencyVectorTest`.
- The rest of the codebase still copies on construction and on `values()`. Hot paths keep using `dotProduct`,
  which reads the internal arrays directly.

## Review Checklist

A PR respects this ADR when:

- no code path hands the internal `float[]` of a `FrequencyVector` to code outside `monada-core`;
- `FrequencyVector.read` stays the only no-copy construction path, and it receives the array only through the
  final `ChunkedFloatReader`;
- `values()` and the public constructor keep copying defensively.
