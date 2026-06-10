---
name: monada-compression-data-structures
description: Use when adding compact vector encodings, compression, binary layouts, indexes, caches, hash tables, segment maps, or other data structures for Monada Resonance Store.
---

# Monada Compression and Data Structures

## Purpose

Guide changes that affect how Monada represents atoms, vectors, indexes, offsets, segments, caches, or compressed data in memory and on disk.

Use this skill for:

- vector compression or quantization.
- compact binary layouts.
- sparse or dense vector structures.
- offset tables and segment maps.
- postings, inverted structures, candidate sets, caches, or heap-based top-K.
- compression experiments for atoms, vectors, feedback logs, or indexes.

## Required context before editing

Inspect:

1. `README.md` storage layout and non-goals.
2. `monada-core` vector/domain model.
3. `monada-storage` file formats and manifest handling.
4. `monada-index` search path and result ranking.
5. tests covering persistence, loading, deterministic order, and evaluation.

## Design principles

- Keep the MVP local, embedded, and inspectable.
- Prefer simple structures until evaluation or profiling proves a bottleneck.
- Separate logical identifiers from physical offsets.
- Make disk formats versioned and recoverable.
- Keep reads deterministic and independent of platform endianness assumptions.
- Avoid compression that makes debugging impossible without tooling.
- Never trade correctness for compactness without an explicit benchmark and test.

## Compression decision framework

Before adding compression, document:

- target data: atom content, aliases, dense vectors, sparse vectors, offsets, feedback events, or indexes.
- current size estimate.
- expected size reduction.
- CPU overhead.
- read amplification.
- write amplification.
- compatibility impact.
- migration/rebuild plan.
- evaluation impact on ranking quality if vector values change.

## Recommended structures by use case

### Top-K retrieval

Prefer a bounded min-heap when scanning many candidates:

- keep only `k` best scored results.
- tie-break deterministically by atom id.
- sort final output by score descending, then atom id ascending.

### Vector storage

For dense float vectors:

- keep fixed dimensions in the manifest.
- store vector type and encoding metadata.
- validate file length against dimensions and atom count.
- fail clearly on incomplete vectors.

For quantized vectors:

- store quantization method, scale, zero point, and dimension metadata.
- add tests comparing ranking stability against float32 baseline.
- never mix float32 and quantized vectors without explicit typed readers.

### Offset/index files

For atom offsets:

- store atom id, segment id, offset, length, and checksum when useful.
- prefer append-friendly updates.
- validate offsets on load.
- avoid trusting stale indexes without manifest consistency checks.

### Caches

For caches:

- keep them invalidatable.
- never let cache state change logical ranking.
- expose cache behavior through tests or diagnostics when it affects performance.

## Acceptance checklist

A data-structure or compression change is acceptable only when:

- the disk format remains documented.
- the manifest records enough metadata to read or reject the data safely.
- corrupted or truncated files fail predictably.
- deterministic ranking is preserved.
- memory usage and CPU trade-offs are described.
- existing tests pass.
- new tests cover boundary cases, empty stores, and incompatible metadata.

## Anti-patterns

- Introducing RocksDB, Lucene, HNSW, or other heavy structures before the Issue accepts that scope.
- Replacing inspectable logs with opaque blobs without diagnostics.
- Adding compression before measuring size pressure.
- Encoding version-sensitive data without manifest updates.
- Relying on Java object serialization for persistent format compatibility.
