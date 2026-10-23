# Book corpus v1: on-disk growth, memory footprint and repeat-import behaviour (Book 7/8)

This experiment shows where disk space and process memory go as real book chunks accumulate in a Store (issue
#169). It also tests what reopening a Store, importing the same chunks again, importing an overlapping set, and
importing duplicated content in one call do to the files. It builds on the persisted Store from #165
([STORE-READBACK.md](STORE-READBACK.md)) and reuses the data-size ladder, scratch contract and host metadata of #168
([LATENCY.md](LATENCY.md)). It does not time anything.

**Status: exploratory.** The structural checks are hard: any failure is listed and makes the command exit 1. The
sizes are exact for this corpus and this Store format. The memory numbers come from one machine and are informational
only: they define no threshold, gate or SLA.

| File | Purpose |
| --- | --- |
| [`book-footprint-v1-report.txt`](book-footprint-v1-report.txt) | Machine-readable `key=value` report: deterministic counts, sizes and checks first, then measured sizes and ratios, host, and memory. |
| [`book-footprint-v1-summary.md`](book-footprint-v1-summary.md) | Human summary: one row per Store phase, and one memory row per phase. |
| `monada-evaluation/src/main/java/com/monada/evaluation/book/BookFootprint*.java`, `BookMemorySampler.java` | Snapshot, memory sampler, experiment, report, CLI. |

## Running it (offline, no network)

The command needs the verified normalized body (see [README.md](README.md), "Offline acquisition"). `--scratch` has
the same contract as in `runBookLatency`: it must not exist yet and must be outside any Git work tree. The run creates
it with a marker, writes every Store below it, and deletes it afterwards (pass `--keep yes` to keep it). No real user
memory root is ever opened: every Store path is a new directory below `--scratch`.

```bash
./gradlew :monada-evaluation:runBookFootprint -q --args="--input $HOME/monada-corpora/book-corpus-v1/body.txt --scratch $HOME/monada-corpora/book-footprint-scratch --out docs/experiments/book-corpus-v1/book-footprint-v1-report.txt --summary docs/experiments/book-corpus-v1/book-footprint-v1-summary.md"
```

The run takes about 7 s on the host below. Options: `--ladder` (default `25%,50%,100%,x4,x16`), `--keep`,
`--manifest`. `--out` and `--summary` follow the `runBookLatency` rules: written atomically, never inside
`--scratch`, never naming an input, and never replacing a frozen artifact in `docs/experiments/book-corpus-v1/` or the
`book-queries` resources, except this experiment's own report (`--out` only) and summary (`--summary` only).

Exit codes:
- `0`: the report was written and every check passed.
- `1`: the body is not the verified corpus, or a structural check failed. On a check failure the report is still
  written and the failing checks are printed on standard error.
- `2`: a usage error, a missing file, an unsafe `--scratch` or output path, or an I/O failure (including a scratch
  directory that cannot be removed).

## What is measured

The body is verified against [`manifest.properties`](manifest.properties) and chunked with `book-chunking-v1`
(87 chunks, none with duplicate content). Scales come from the #168 ladder: `25%`, `50%` and `100%` are REAL
(evenly spaced frozen chunks, unmodified); `x4` and `x16` are DERIVED (replica-tagged copies, real length and
vocabulary, not real-book shape).

**Phases per scale**, on one Store of its own:

1. `EMPTY`: the Store does not exist.
2. `FRESH_IMPORT`: `MonadaMemory.open(store).rememberAll(texts)`.
3. `REOPEN`: a new instance answers one recall (the first chunk's text, top 1), so the vectors really load.
4. `REPEAT_IMPORT`: a new instance calls `rememberAll` with the same texts.

**Content experiments**, over all 87 chunks, each on its own Stores:

- `OVERLAP`: import the `50%` scale, then all chunks into the same Store.
- `INTRA_BATCH_DUPLICATES`: one Store gets every chunk once, another gets every chunk twice in one `rememberAll`
  call. Their statistics and file sizes are compared.

**Each snapshot** is read from disk only. It runs the read-only `StorageIntegrityAuditor` and records the status,
severity counts and logical counts (physical atom records, unique atoms, history duplicates, vector index entries,
unique ids, duplicate offsets, atoms with and without a vector, orphan vectors, feedback records). It records the size
of every file, attributed to its role (`manifest.json`, atom segment, vector segment, `indexes/vector-map.idx`,
feedback segment); any other file is listed by its relative path, so no byte goes unaccounted for. It also records a
tree digest: a phase is `unchanged` when its digest equals the previous phase's. Taking a snapshot never creates or
changes anything; it fails if the Store changes while it is read.

**Derived values** are named for what they are. They are not compression ratios:

| Name | Definition |
| --- | --- |
| `storeBytesPerUniqueAtom` | total Store bytes ÷ unique atoms |
| `storeBytesPerUniqueTextByte` | total Store bytes ÷ UTF-8 bytes of the distinct chunk texts held |
| `storeBytesPerUniqueTextCodePoint` | total Store bytes ÷ Unicode code points of those texts ("bytes per source character") |
| `atomBytesPerUniqueTextByte` | atom-segment bytes ÷ UTF-8 bytes of the distinct texts |
| `vectorShareOfTotal` | vector-segment bytes ÷ total Store bytes |
| `appendAmplification` | bytes the phase added to the Store ÷ UTF-8 bytes of the texts that were new to it (`n/a` when none were new) |

### Hard checks

Every populated phase must pass: audit `HEALTHY`; unique atoms, physical atom records, vector index entries, unique
vector ids and atoms with a vector all equal to the expected distinct texts; zero history duplicates, duplicate
offsets, orphan vectors and feedback records; vector segment bytes = entries × frame bytes; no unattributed file. In
addition:

- after `FRESH_IMPORT`, every selected text (and, at REAL scales, every selected sidecar chunk key) resolves to an atom
  with exactly its text, read from disk through `BookStoreInventory`;
- `REOPEN` and `REPEAT_IMPORT` leave the Store byte-for-byte unchanged, and `REPEAT_IMPORT` adds no atom;
- `OVERLAP` appends exactly the missing atoms: as many atom records as new texts, and exactly one vector frame each;
- `INTRA_BATCH_DUPLICATES` produces the same statistics, manifest, vector, index and feedback bytes as a single import;
- the body and the manifest have the same SHA-256 after the run as before.

The committed run passed **204 of 204 checks**.

### Determinism

Everything before `== Measured ==` is byte-identical across runs; two back-to-back runs were compared (668 lines).
Atom-segment bytes, totals and the ratios built on them are in the measured section because every atom record holds
its creation `Instant`. Its text form has 0, 3, 6 or 9 fraction digits, so a record can vary by up to 9 bytes; the two
runs differed by 3 bytes at most per Store. The report contains no book text: only counts, sizes, digests and
statuses.

### Memory

Three families are recorded under separate keys and never treated as interchangeable:

- **Heap** (`MemoryMXBean`): used and committed at the end of the phase; the sum of the heap pools' peaks since the
  phase started (pools are reset first; a sum of per-pool peaks is an upper bound, not a simultaneous reading); used
  after an advisory `System.gc()`.
- **Allocation** (`com.sun.management.ThreadMXBean`): bytes the measuring thread allocated during the phase.
  `UNSUPPORTED (<reason>)` where the JVM does not provide it.
- **RSS** (`/proc/self/status`): `VmRSS` now and `VmHWM`, the process-lifetime peak (never reset, so not per phase).
  `UNKNOWN` where the file does not exist (non-Linux hosts).

A **retained-heap estimate** per scale is the heap used after an advisory GC while one reopened, queried instance is
held, minus the same after it is dropped. It is labelled `APPROXIMATE`: it depends on the GC honouring the request.

## Results (one host, 2026-10-23)

**Host:** AMD Ryzen 7 7730U (16 logical processors), Linux 7.2.11 (Fedora 44, x86_64), Azul Zulu OpenJDK 27+35, G1
GC, default max heap 3832 MiB, scratch on btrfs on NVMe, Gradle `JavaExec` default JVM flags.

### Disk

At the `100%` scale (87 chunks, 150 885 bytes of text, 144 390 code points), the Store takes 257 177–257 180 bytes:

| File | Bytes | Share | Per atom |
| --- | --: | --: | --: |
| atom segment | 208 150–208 153 | 80.9% | ≈ 2 393 |
| vector segment | 44 544 | 17.3% | 512 (128 dims × 4 B, fixed) |
| vector index | 3 717 | 1.4% | ≈ 43 |
| manifest | 766 | 0.3% | constant |
| feedback segment | 0 | 0% | none written |

Across the ladder, total bytes grow linearly with atoms: 63.6 KB (21), 128.4 KB (43), 257.2 KB (87), 1.03 MB (348),
4.13 MB (1392). The ratio stays at **≈ 1.70 Store bytes per byte of text** (1.713 at 21 chunks, 1.704 at 87, 1.698 at
1392) and ≈ 1.78 bytes per code point at 87 chunks. The atom segment holds the text itself at ≈ 1.38 bytes per text
byte (JSON escaping plus id, type and timestamp), and the vector costs a fixed 512 bytes regardless of chunk length.
Nothing is compressed or deduplicated beyond exact content identity.

### Reopen and repeated imports

- `REOPEN` wrote nothing at any scale, including after a recall that loads every vector.
- `REPEAT_IMPORT` wrote nothing at any scale: no record appended, no byte changed. No space is reclaimed either; none
  was expected and none is claimed.
- `OVERLAP` appended exactly the 44 missing atoms (44 records, 22 528 vector bytes, 128 816 bytes in total). Its
  append amplification, 1.703, equals the fresh-import ratio: appending to a Store costs the same per byte as creating
  it.
- `INTRA_BATCH_DUPLICATES`: 174 texts in one call produced the same 87 atoms, the same statistics and the same
  vector, index, manifest and feedback bytes as 87 texts. Duplicates merge by content identity; they are not stored
  twice.

### Memory (this host only)

- **Retained heap** of one reopened, queried instance grows about linearly: 0.08 MiB (21 atoms), 0.31 MiB (87),
  1.24 MiB (348), 4.96 MiB (1392), so about 3.6 KB per atom, about twice the chunk text's UTF-8 size.
- **Allocation** while importing is mostly transient: 13.6 MiB for 87 chunks and 216 MiB for 1392, about 160 KB per
  chunk. Heap used after GC stayed at 3.3–8.9 MiB for the whole run.
- **RSS** rose from about 83 MiB to 184 MiB over the run. About 83 MiB was already resident after the first, smallest
  import (JVM, loaded classes, heap), and the rise follows committed heap (36 → 108 MiB) more than Store size. RSS is a process measure and is not comparable with the heap figures.
- **Disk vs heap limits, by extrapolation only:** at ≈ 2.96 KB on disk and ≈ 3.6 KB retained heap per atom of this
  shape, 1 GiB of disk holds roughly 360 000 such chunks, and the default 3.8 GiB heap would be exhausted by the
  in-memory scan around one million. These figures are arithmetic on the measured slopes, not measurements; the
  largest Store measured has 1392 atoms, and DERIVED scales are not real-book shape.

### Caveats

- One laptop-class host; GC timing, heap sizing and RSS depend on the JVM, GC and flags. The advisory GC may be
  ignored.
- The allocation counter covers the measuring thread only.
- `VmHWM` is the process-lifetime peak, so it never falls and it includes JVM startup.
- Atom-segment sizes depend on the creation timestamp text; the report keeps them out of the deterministic section.

## Non-goals respected

- No compaction, deletion, concurrent writers, caching, or chunk provenance stored inside atoms. Chunk keys stay in
  the external sidecar.
- No change to `MonadaMemory`, `monada-api`, `monada-storage`, `monada-core`, `monada-speech`, the Store or manifest
  formats, or the exact-query defaults. No existing class was changed; the only other change is the new
  `runBookFootprint` Gradle task.
- Heap, allocation and RSS are never presented as interchangeable, and repeat imports are not assumed to reclaim
  space.
- No real user memory root is touched. Inputs are only read, and their digests are checked.
- Retrieval relevance (#167), ranking feedback, factual correction and execution outcomes stay distinct: this
  experiment only measures sizes and memory of feedback-free Stores built with the unchanged default API.
