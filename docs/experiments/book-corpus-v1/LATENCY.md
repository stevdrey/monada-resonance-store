# Book corpus v1: ingest, reopen and recall latency (Book 6/8)

This experiment measures how long real book text takes to ingest, how long a Store of it takes to open, and how long
the judged book queries take to recall (issue #168). It runs at several controlled data sizes, records host metadata,
and checks result-ID parity. It builds on the persisted Store from #165 and the fixed `book-queries-v1` workload from
#166; quality context comes from #167 ([RECALL.md](RECALL.md)).

**Status: exploratory.** The timings are wall-clock numbers from one machine. They are informational only: they
define no threshold, regression gate or SLA, and they say nothing about other hosts. No OS cold-cache condition is
measured or implied.

| File | Purpose |
| --- | --- |
| [`book-latency-v1-report.txt`](book-latency-v1-report.txt) | Machine-readable `key=value` report: deterministic inputs, configuration, scales and parity first, then the host and every timing series. |
| [`book-latency-v1-summary.md`](book-latency-v1-summary.md) | Human summary: one row per scale. |
| `monada-evaluation/src/main/java/com/monada/evaluation/book/BookLatency*.java` | Ladder, statistics, host metadata, benchmark, fresh-process child, report, CLI. |

## Running it (offline, no network)

The command needs the verified normalized body (see [README.md](README.md), "Offline acquisition"). `--scratch` must
not exist yet, and it must be outside any Git work tree. The run creates it, writes every Store below it, and deletes
it afterwards (pass `--keep yes` to keep it):

```bash
./gradlew :monada-evaluation:runBookLatency -q --args="--input $HOME/monada-corpora/book-corpus-v1/body.txt --scratch $HOME/monada-corpora/book-latency-scratch --out docs/experiments/book-corpus-v1/book-latency-v1-report.txt --summary docs/experiments/book-corpus-v1/book-latency-v1-summary.md"
```

With the defaults, the command takes about 20 s on the host below. Every count is an option: `--ladder`,
`--ingestTrials`, `--ingestWarmups`, `--openTrials`, `--openWarmups`, `--queryPasses`, `--queryWarmupPasses` and
`--processes`. Without `--out` or `--summary`, that output goes to standard output.

Exit codes:
- `0`: the report was written and parity held.
- `1`: the body is not the judged chunk set (foreign corpus), or parity failed. On a parity failure the report is
  still written.
- `2`: a usage error (including a scale that selects no chunk, `--out` and `--summary` naming the same file, or an
  output naming `--input` or `--manifest`), a missing file, an unsafe `--scratch`, or an I/O failure (including a
  child JVM that times out or fails, or standard output that cannot be written).

## What is measured

The chunk set is bound before anything is timed. The body is verified and chunked with `book-chunking-v1`, and its
chunks file and chunk-map digests must equal the ones `book-queries-v1` was judged against. The workload is the 32
`book-queries-v1` query texts in query-id order. The judgments are not used.

**Data-size ladder** (default `25%,50%,100%,x4,x16`):

| Scale | Kind | Chunks | How |
| --- | --- | --: | --- |
| `25%`, `50%` | REAL | 21, 43 | Evenly spaced ordinals: `i` is kept iff `floor((i+1)p/100) > floor(ip/100)`. Every scale spans the whole book. |
| `100%` | REAL | 87 | All frozen chunks, unmodified. |
| `x4`, `x16` | DERIVED | 348, 1392 | All chunks plus `N-1` replicas `"[replica r] " + text`. Each replica gets its own content-derived atom. The text has real vocabulary and length but not real-book shape, and is labelled `DERIVED` everywhere. |

**Phases per scale.** Each phase uses its own Store directory under `--scratch`, in this order:

1. **Parity baseline** (untimed): a dedicated Store is seeded, and every query's top-10 is recorded on a new instance.
2. **Ingest**: 1 warmup trial and 5 measured trials. Each trial opens a new empty directory, timed as
   `ingest.create`, then calls `rememberAll(texts)`, timed as `ingest.rememberAll`. That call is the public API's
   encode and persist. Throughput is reported as chunks/s and MiB/s of chunk text.
3. **Open, in process** (`IN_PROCESS_REPEATED`): 2 warmup opens and 20 measured opens of the last ingested Store.
   Each open is followed by one timed first query on that instance (`firstQuery.IN_PROCESS_NEW_INSTANCE`), so cost
   that the instance defers past `open` is not hidden.
4. **Query, in process** (`IN_PROCESS_WARM`): 2 warmup passes and 10 measured passes over all 32 queries on one
   instance, giving 320 samples. Only the API calls are timed, back to back. Afterwards, in a loop of its own, a
   standalone query-encoding diagnostic (`queryEncode.STANDALONE_DIAGNOSTIC`) runs the same normalizer and encoder
   as the API. It never runs between timed API calls and is never subtracted from them.
5. **Fresh process**: 5 sequential child JVMs (`BookLatencyChild`) open the same Store and run one pass each. Each
   child starts with the parent's allowlisted performance options (heap, stack, GC choice and sizing, JIT tiering,
   processor count) and reports its own max heap, GC and options; the report records those as `host.child_jvm`.
   Diagnostics that write files (flight recordings, `-Xlog`, heap dumps) are never forwarded, so children cannot
   overwrite each other's or the parent's output. The first child is `FRESH_PROCESS_FIRST` and the later ones are
   `FRESH_PROCESS_REPEATED`; the two are reported separately. For each child, the report has `open` with JVM startup
   excluded, the pass's first query, the remaining 31 queries, and `processWall`, which is the whole child as the
   parent sees it, JVM startup included.
6. **Parity after profiling** (untimed): the baseline Store is reopened and queried again.

**Percentiles** are nearest-rank, with no interpolation, so every value is an observed latency. A percentile is only
reported when at least one sample lies above it: p50 needs n ≥ 1, p95 needs n ≥ 20 and p99 needs n ≥ 100. Below that
the report prints `n/a (n=<n> < <min>)`. The report also gives the minimum, maximum, mean and the sample (n-1)
standard deviation.

**JVM arguments in the report** (`host.jvm_args`, `host.child_jvm`) are the same allowlist only; every other
argument is counted (`+N other arguments not recorded`) but its value is never written, because system properties
can carry secrets and the report is committed.

**Excluded from every timing:** chunking, corpus verification, parity passes, directory setup and cleanup, and report
writing. JVM startup is also excluded, except in `processWall`.

**Not measured:** an OS cold cache (see below), storage durability beyond what the API itself does, attribution to
the JIT or GC, concurrent queries, and ANN or caches (none exist).

### Cache conditions

The runner never drops the OS page cache. Dropping it is a global, privileged and destructive operation. The Store
files were written or read moments earlier, so every condition is labelled `WARM_OR_UNKNOWN`. A fresh JVM is a
fresh-process condition, not a cold-cache one: its page cache is shared with the parent. This experiment does not
compare cold and warm runs.

## Result-ID parity (hard check)

Every timed query is compared with the parity baseline of its scale: ids, order and score bits. That covers the open
trials, the warm passes, each child JVM (by SHA-256 over all hits) and the post-profiling pass. Hits must also be
known atoms in score-descending, then atom-id-ascending order. The denominator is one query's top-10 result list: a
child digest counts every query of its pass, and a differing digest would count all of them as mismatches. The
committed run verified **2990 result lists with 0 mismatches**: 2190 compared directly and 800 through 25 child-JVM
digests.

Profiling changed no quality or ranking code. After this branch's changes, `runBookRecall` on the seeded Store
produces a report byte-identical to the frozen [`book-recall-v1-report.txt`](book-recall-v1-report.txt).

## Results (bounded local sample, 2026-10-22)

**Host:** AMD Ryzen 7 7730U (16 logical processors), Linux 7.2.11 (Fedora 44, x86_64), Azul Zulu OpenJDK 27+35, G1
GC, default max heap 3832 MiB. Scratch was on btrfs on NVMe, and the JVM flags were the Gradle `JavaExec` defaults
(no allowlisted performance option; 4 locale and encoding properties not recorded). The child JVMs reported the same
heap and GC. Two runs were made back to back. Their deterministic report sections (everything before `== Host ==`)
are byte-identical. The committed report is run 1.

Medians (p50) in µs unless marked; tails have n = 320 for in-process queries:

| Scale | Kind | Chunks | Ingest p50 ms (run 1 / run 2) | Open in-process p50 | First query on a new instance p50 | Warm query p50 / p95 / p99 (run 1) | Warm query p50 / p95 / p99 (run 2) |
| --- | --- | --: | --: | --: | --: | --: | --: |
| 25% | REAL | 21 | 30.7 / 28.4 | 1189 / 1217 | 1183 / 1119 | 185 / 248 / 303 | 188 / 332 / 393 |
| 50% | REAL | 43 | 32.5 / 34.2 | 847 / 607 | 903 / 775 | 167 / 210 / 246 | 167 / 203 / 269 |
| 100% | REAL | 87 | 62.3 / 64.7 | 599 / 740 | 1272 / 1390 | 215 / 237 / 256 | 230 / 293 / 399 |
| x4 | DERIVED | 348 | 242.7 / 247.4 | 670 / 1360 | 3275 / 6003 | 1011 / 1310 / 1624 | 1051 / 1364 / 1666 |
| x16 | DERIVED | 1392 | 904.9 / 928.8 | 920 / 1038 | 13812 / 15493 | 3078 / 4557 / 5618 | 2882 / 4188 / 4910 |

Fresh process, 100% scale, run 1 / run 2:

| Measure | Value |
| --- | --- |
| Child `open`, first JVM (n = 1) | 68.2 / 70.1 ms |
| Child `open`, repeated JVMs (p50, n = 4) | 61.8 / 65.2 ms |
| First query of the first JVM (n = 1) | 44.5 / 48.8 ms |
| Remaining queries, repeated JVMs (p50 / p95, n = 124) | 1098 / 2276 µs and 1120 / 2699 µs |
| Whole child process, repeated (p50, n = 4) | 194 / 210 ms |

### Observations (this host only)

- Warm in-process recall grows roughly linearly with stored atoms. From 87 to 1392 atoms the p50 rises about
  12–15×, consistent with the exact linear scan; the 21–87-chunk REAL scales are too small to separate from fixed
  per-query overhead. Ingest grows about linearly too: roughly 680–700 chunks/s at 21 chunks (fixed per-call
  overhead) and 1 250–1 530 chunks/s from 43 chunks up.
- `MonadaMemory.open` alone stays under about 1.4 ms in process at every scale. The first query on a new instance
  carries the deferred loading: about 1.3–1.4 ms at 87 chunks and 14–15.5 ms at 1392.
- In a fresh JVM, open and the first query cost tens of milliseconds (class loading and an unwarmed JIT), and later
  queries stay about 5× slower than warm in-process ones for the whole single pass. Run-to-run variation is visible
  in tails and small series (for example the x4 first query on a new instance is 3275 vs 6003 µs, and the 100% warm
  p99 is 256 vs 399 µs). Single samples (n = 1) and n = 4 series are indicative only.

### Caveats

- Everything here comes from one laptop-class host. CPU frequency scaling, background load and btrfs behaviour were
  not controlled. Do not read these numbers as limits or SLAs.
- The DERIVED scales repeat real text behind a prefix, so their score distribution and hub behaviour are not those of
  a 1392-chunk book.
- The report contains no book text: only digests, counts and timings.

## Non-goals respected

- No ANN, vector cache or ranking optimization. No change to `MonadaMemory`, `monada-api`, `monada-index`,
  `monada-storage`, `monada-core`, the Store or manifest formats, or the exact-query defaults. The only change outside
  the new `BookLatency*` classes widens `BookRecallEvaluator.validateHits` to package-private so the benchmark can
  reuse it.
- No cold-cache claim, and no destructive cache drop.
- No hard performance threshold in tests or code. Tests check denominators, isolation, parity and deterministic
  metadata, never a duration.
- Retrieval relevance (#167), ranking feedback, factual correction and execution outcomes stay distinct: this
  experiment only times the unchanged default API on feedback-free Stores.
