# Book corpus v1: Store persistence and exact readback (Book 3/8)

Issue #165 shows that the whole chunked `book-corpus-v1` is stored in a local Store and can be read back from disk
with exact content. Ranking a transient in-memory list does not count. This is an offline evaluation step only:
- no production bulk-import API;
- no direct atom lookup on the `MonadaMemory` facade;
- no change to the Store format, last-wins or idempotency behaviour;
- no change to protected evaluation baselines.

Code: `monada-evaluation/src/main/java/com/monada/evaluation/book/`. The relevant classes are
`BookStoreReadbackMain`, `BookStoreReadback`, `BookStoreInventory`, `BookChunkFiles`, `ExperimentDirectoryGuard` and
`BookStoreReadbackReport`. Tests live in `monada-evaluation/src/test/java/com/monada/evaluation/book/` and use the
original `book-chunking-fixture-v1` fixture.

## Running it

Prerequisite: the verified normalized body. See [README.md](README.md), "Offline acquisition" and
`--write-normalized`.

```bash
# 1. Clean import into a new, dedicated experiment directory (its parent must exist).
./gradlew :monada-evaluation:runBookStoreReadback --args="seed --input $HOME/monada-corpora/book-corpus-v1/body.txt --work $HOME/monada-corpora/book-store-readback-v1"
# 2. Second identical import: reported as REPEAT.
./gradlew :monada-evaluation:runBookStoreReadback --args="seed --input $HOME/monada-corpora/book-corpus-v1/body.txt --work $HOME/monada-corpora/book-store-readback-v1"
# 3. Read-only verification in a separate JVM/process.
./gradlew :monada-evaluation:runBookStoreReadback --args="verify --work $HOME/monada-corpora/book-store-readback-v1"
# 4. Independent structural audit of the same Store.
./gradlew :monada-storage:runIntegrityAudit -Dmonada.store.dir=$HOME/monada-corpora/book-store-readback-v1/store
```

`--manifest` defaults to `docs/experiments/book-corpus-v1/manifest.properties`.

Exit codes:

| Code | Meaning |
| --- | --- |
| 0 | Readback passed. |
| 1 | Readback failed, or the body is not the verified text. |
| 2 | Usage error, missing file, bad manifest, unsafe work directory, or I/O failure. |

The report is deterministic: it contains no paths, timings or timestamps. The Store itself is not byte-reproducible
across runs because atom `createdAt` is `Instant.now()`. Its id-to-content inventory fingerprint is reproducible.

## Work directory safety (fail closed)

The runner never writes into an arbitrary path. `--work` is accepted only in two cases:

- It is **absent**. Its parent must already exist. Only the directory itself is created, together with the
  `.monada-book-store-experiment` marker and the `.monada-book-store.lock` seed lock.
- It is an **existing directory carrying that marker**. It may contain only the marker, the lock, `store/` and `chunks/`, and
  only directories and ordinary single-linked files may appear below `store/` or `chunks/` (no symbolic
  links, hard-linked files, FIFOs, sockets or devices). Where the file system does not report hard-link counts, an existing
  work directory with files is refused (fail closed); a new one still works.

The runner refuses:
- an unmarked directory, including an empty one, a home directory or a repository;
- unexpected entries such as `.git`;
- symbolic links, hard-linked files or special files;
- input files located inside the work directory.

All of these checks run before anything is written. `seed` verifies the body against the manifest *before* creating
the work directory, so a wrong input leaves no trace. `verify` never creates anything.

Concurrent runs on one work directory are serialized: `seed` holds an exclusive lock on
`.monada-book-store.lock` from its first check to the end of its verification, and `verify` takes a shared lock
when the lock file exists (it never creates it).

Layout:

```
<work>/.monada-book-store-experiment
<work>/.monada-book-store.lock              # seed (exclusive) / verify (shared) lock
<work>/chunks/chunks.jsonl, sidecar.jsonl   # canonical chunks + provenance, outside the Store
<work>/store/                               # the Store (manifest, atoms, vectors, indexes, feedback)
```

## What is checked, and at which access level

| Check | Access level | Counts as readback evidence |
| --- | --- | --- |
| Seeding | Public API: `MonadaMemory.open(store).rememberAll(texts)` | — |
| Chunk files | `BookChunkFiles` re-reads `chunks.jsonl` and `sidecar.jsonl` from disk. It checks: the header's `chunksFileSha256` and `chunkCount`; key, ordinal and `sha256` on every sidecar line against its chunk; `duplicateOf` names the first earlier equal chunk. | Yes (source mapping) |
| Exact inventory | Published lower-level read API: the manifest as parsed by the read-only `StorageIntegrityAuditor` (not `FileManifestStore.load()`, which recreates missing directories), then `FileAtomStore.findAll()` (last-wins). Always uses fresh instances; never `MonadaMemory` and never the seeding instance. An unreadable manifest or atom log is reported as `inventory: UNREADABLE`, next to the audit status, not as a crash. | Yes |
| Structure | Read-only `StorageIntegrityAuditor`, run first. Status is strict `HEALTHY`: no WARNING, ERROR or FATAL finding. If the auditor itself throws on malformed data, the report shows `AUDIT FAILED (...)` and the run fails instead of crashing. | Yes |
| Log termination | The atom segment and `indexes/vector-map.idx` must each end with a line feed. The auditor accepts an unterminated last record, but it marks an interrupted write. A log path that is not a readable regular file also fails this check. | Yes |
| No hidden writes | SHA-256 tree digest of `store/` before and after verification. | Yes |
| Self-recall@1 | Public API: `resonate(chunkText).topK(1)` on a reopened instance of a **throwaway copy** of the Store, because opening `MonadaMemory` may recreate missing directories or segment files. Runs only when the inventory is complete and the audit is `HEALTHY`; otherwise, or when the copy cannot be opened (for example a non-default encoding profile), the report says `SKIPPED` and the readback result is unaffected. | **No.** Exploratory ranking only |

The chunk-to-atom mapping uses the content-derived id `KnowledgeAtom.text(text).id()`. Chunk keys and provenance stay
in the external sidecar because `FileAtomStore` does not persist atom metadata (ADR 0004). See also
[CHUNKING.md](CHUNKING.md), "Repeated content and Store IDs".

A readback **passes** when all of these hold:
- every unique chunk text reads back byte for byte (`exact readback = expected unique atoms`);
- every chunk, repeats included, resolves to its exact text;
- the Store holds no unexpected atoms;
- the audit is `HEALTHY`;
- both line-oriented logs end with a line feed;
- verification wrote nothing.

When it fails, the report lists the SHA-256 of every missing or altered unique chunk under **failed hashes**.

### Import kinds

| Kind | Meaning |
| --- | --- |
| `CLEAN` | The Store was empty or absent; every unique chunk was appended. |
| `REPEAT` | The Store already held exactly this chunk set. `rememberAll` appended nothing, and the Store bytes must be unchanged (otherwise the run fails). No space is reclaimed, and none is promised. |
| `RESUMED` | The Store held a strict subset and was `HEALTHY` (an import interrupted between atoms); only the missing chunks were appended. |
| `FOREIGN` | The Store held atoms that are not chunks of this set (an unknown id, or an expected id holding other content), or the work directory was seeded from a different chunk set. Nothing is written, chunk files included (they stay the Store's provenance), and the run fails. |
| `DAMAGED` | The existing Store is not `HEALTHY` (for example an orphan vector left by an import interrupted between the vector and atom appends), the auditor itself failed on it, or its atom log or vector index ends without a line feed (a record whose terminator was lost). Resuming would duplicate vector index entries or concatenate onto that record, so nothing is appended and the run fails; seed a new work directory. |
| `INCOMPATIBLE` | The existing Store was written with another manifest version or encoding profile than the default options the runner uses. Nothing is opened or appended and the run fails; seed a new work directory. |

## Results (book-corpus-v1, policy `book-chunking-v1`)

Run on 2026-10-19, Linux, against the verified normalized body
(`b44b4992…944c`, 151 095 bytes).

| Metric | Clean import | Second identical import | Separate-process `verify` |
| --- | --- | --- | --- |
| Import kind | `CLEAN` | `REPEAT` | — |
| Attempted chunks | 87 | 87 | 87 |
| Duplicate-content chunks | 0 | 0 | 0 |
| Accepted new unique atoms | 87 | 0 | — |
| Atom log records (before / after) | 0 / 87 | 87 / 87 | 87 |
| Exact readback (unique) | 87 / 87 | 87 / 87 | 87 / 87 |
| Failed hashes | none | none | none |
| Inventory fingerprint | `dd11b6ba…03d7` | `dd11b6ba…03d7` | `dd11b6ba…03d7` |
| Integrity audit | `HEALTHY` (0 findings) | `HEALTHY` | `HEALTHY` |
| Store bytes unchanged by the run | no (append) | yes | yes |
| Self-recall@1 (exploratory) | 87 / 87 | 87 / 87 | 87 / 87 |

`runIntegrityAudit` on the same Store reports `Status: HEALTHY` with 87 atom records, 87 active unique atoms, 87
vector entries, and no duplicate or orphan entries.

Policy v1 produces no repeated chunk for this book. Duplicate handling is covered by the fixture tests instead: the
fixture has 10 chunks, of which 9 are unique. In those tests, the repeat resolves to the same atom and is counted
once.

Interpretation:
- The self-recall figure is **exploratory**. Querying with a chunk's own full text is the easiest possible query, so
  it says nothing about retrieval quality on real questions. That is the subject of later Book issues.
- No protected baseline (`run`, `runExpanded`, `runProfileComparison`) is affected, because only code under
  `com.monada.evaluation.book` changed.
