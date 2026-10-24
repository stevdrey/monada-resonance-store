# Book corpus v1 — frozen input contract

`book-corpus-v1` pins exactly one real, moderately long, public-domain text for controlled book
experiments (issue #163, Book 1/8). It approves **sourcing only**: no chunking, ingestion, ranking
or summarization happens here, and no runtime module reads this corpus yet.

The book bytes are **not stored in Git**. Each user downloads the pinned edition once, by hand, and
verifies it offline against the checksums in [`manifest.properties`](manifest.properties).

| File | Purpose |
| --- | --- |
| [`manifest.properties`](manifest.properties) | Machine-readable contract: source, revision, checksums, encoding, rights status. |
| [`RIGHTS.md`](RIGHTS.md) | Rights assessment: book text vs. Project Gutenberg license/trademark, jurisdictions. |
| [`VerifyBookCorpus.java`](VerifyBookCorpus.java) | Offline verifier, JDK only (source launcher), no network classes. |
| [`CHUNKING.md`](CHUNKING.md) | Chunking policy v1 and provenance sidecar format (issue #164, Book 2/8). |
| [`STORE-READBACK.md`](STORE-READBACK.md) | Persist the chunks, reopen the Store and verify exact readback (issue #165, Book 3/8). |
| [`QUERIES.md`](QUERIES.md) | Judged query set `book-queries-v1` with frozen spans, chunk keys and dev/holdout split (issue #166, Book 4/8). |
| [`RECALL.md`](RECALL.md) | Exploratory recall quality against the judged evidence, failure taxonomy and versioned report (issue #167, Book 5/8). |
| [`UNJUDGED-REVIEW.md`](UNJUDGED-REVIEW.md) | Score-blind human review of predeclared unjudged DEV hits, frozen as the append-only `book-queries-v2` (issue #177). |
| [`LATENCY.md`](LATENCY.md) | Ingest, reopen and recall latency at a controlled data-size ladder, in process and in fresh JVMs, with result-ID parity (issue #168, Book 6/8). |
| [`FOOTPRINT.md`](FOOTPRINT.md) | On-disk growth per file, memory footprint (heap, allocation, RSS) and reopen/repeat/overlap/duplicate import behaviour with hard structural checks (issue #169, Book 7/8). |
| [`FEEDBACK.md`](FEEDBACK.md) | Explicit DEV-oracle feedback A/B on isolated Stores with HOLDOUT and confusable-probe contamination checks, plus one explicitly remembered note persisted across reopen (issue #170, Book 8/8). |

## Pinned edition

| Field | Value |
| --- | --- |
| Work | *Alice's Adventures in Wonderland*, Lewis Carroll (1865) |
| Source | Project Gutenberg eBook #11, Plain Text UTF-8 |
| URI | `https://www.gutenberg.org/cache/epub/11/pg11.txt` (`/ebooks/11.txt.utf-8` redirects here) |
| Revision | release 2008-06-27, "Most recently updated: June 26, 2025" (file header) |
| HTTP `Last-Modified` | 2026-10-01T07:31:55Z, retrieved 2026-10-18 |
| Raw bytes | 174 311 bytes, SHA-256 `01b38ea4c710a84bc18d0bd41271a5a1a92b94e97b2812f4dece97d4a694725e` |
| Raw form | UTF-8, no BOM, CRLF, 3 762 lines |
| Normalized body | 151 095 bytes, 3 380 lines, SHA-256 `b44b499274ef98e0bc12c190ee08d9c63480b7c96946101a540a0cb24b3a944c` |

Raw and normalized digests were computed by `VerifyBookCorpus.java --print-digests` and
cross-checked independently with Python `hashlib`.

## Offline acquisition

Do this once, manually, outside CI and outside tests. Keep the file **outside the repository**
(for example `~/monada-corpora/book-corpus-v1/pg11.txt`) so it is never committed.

1. Open `https://www.gutenberg.org/ebooks/11` in a browser and choose **Plain Text UTF-8**, or run a
   single manual download:

   ```bash
   curl -fsSL -o ~/monada-corpora/book-corpus-v1/pg11.txt https://www.gutenberg.org/cache/epub/11/pg11.txt
   ```

   Project Gutenberg forbids automated/robot downloading from its main site; one interactive
   download per user is fine. Do not add this command to scripts, Gradle tasks or CI.
2. Verify it (next section). If it matches, keep the file read-only and reuse it; never re-download
   to "refresh" it.
3. If the upstream file has changed since 2026-10-18 the verifier rejects it (see
   [Re-pinning](#re-pinning)). Obtain the pinned bytes from a colleague's verified copy or your own
   archive instead; the SHA-256 proves they are identical.

## Verification

Run from the repository root. Exit `0` = matches, `1` = bytes invalid or different from the pinned
values, `2` = usage error, missing input, bad manifest, `--write-normalized` naming the input, or a
failed output write. Diagnostics go to stderr.

```bash
java docs/experiments/book-corpus-v1/VerifyBookCorpus.java ~/monada-corpora/book-corpus-v1/pg11.txt
```

Write the normalized body (only after every check passes, via a temporary file and an atomic move,
so an interrupted run never leaves a truncated body behind):

```bash
java docs/experiments/book-corpus-v1/VerifyBookCorpus.java --write-normalized ~/monada-corpora/book-corpus-v1/body.txt ~/monada-corpora/book-corpus-v1/pg11.txt
```

Equivalent check with standard tools only:

```bash
echo "01b38ea4c710a84bc18d0bd41271a5a1a92b94e97b2812f4dece97d4a694725e  $HOME/monada-corpora/book-corpus-v1/pg11.txt" | sha256sum -c -
```

Tests never touch the network. `BookCorpusV1ContractTest` (in `monada-evaluation`) runs the
verifier against a tiny original fixture; the real book is checked only when you opt in:

```bash
MONADA_BOOK_CORPUS_V1_FILE=$HOME/monada-corpora/book-corpus-v1/pg11.txt ./gradlew :monada-evaluation:test --tests '*BookCorpusV1*'
```

Without the variable that single test is skipped (JUnit assumption), so CI stays offline.

Diagnostics are actionable:

- missing input → path plus a pointer to "Offline acquisition";
- raw mismatch → expected vs. actual size, SHA-256, line count and line ends, plus whether the normalized body still
  matches (*upstream boilerplate drift*) or differs too (*different edition or corrupted
  download*). Both cases fail;
- invalid UTF-8 (byte offset), BOM, lone CR, missing/duplicate/out-of-order markers, non-NFC text.
- bad manifest (exit 2): missing keys, malformed values or escapes, a `normalize.policy` other than
  `v1`, or a declared `*.encoding`/`*.bom`/`*.eol`/`normalized.unicodeForm` value policy v1 cannot
  guarantee;
- `--write-normalized` pointing at the input file (exit 2), so the pinned raw bytes are never overwritten.

## Normalization policy v1

Deterministic; the verifier implements it and the manifest pins its output. Besides size and
SHA-256, the verifier also enforces `raw.lineCount` and `raw.eol` whenever a manifest declares them.

1. Decode strict UTF-8; malformed or unmappable bytes fail with their byte offset.
2. A leading BOM (U+FEFF) fails; the pinned edition has none.
3. Convert CRLF to LF. Any remaining CR (a lone CR) fails.
4. Keep only the lines strictly between the single line starting with
   `*** START OF THE PROJECT GUTENBERG EBOOK` and the single line starting with
   `*** END OF THE PROJECT GUTENBERG EBOOK`. This removes the Project Gutenberg header, license and
   footer, including every use of the trademark.
5. Drop leading and trailing blank lines; every kept line ends with exactly one LF.
6. No Unicode rewriting: the body must already be NFC, otherwise it fails.
7. Output is UTF-8 without BOM.

Original user-visible text (the raw file) stays separate from the normalized representation; later
experiments derive chunks from the normalized body only.

## Re-pinning

`book-corpus-v1` is immutable. Never edit its checksums to accept a newer upstream file: the
verifier deliberately rejects later upstream changes even when only the boilerplate moved.

To adopt a new edition, open an Issue and create `docs/experiments/book-corpus-v2/`. Start its
manifest with only `corpus`, `corpusVersion` and the `normalize.*` keys, no `raw.*` or `normalized.*`
values, then run:

```bash
java docs/experiments/book-corpus-v1/VerifyBookCorpus.java --manifest docs/experiments/book-corpus-v2/manifest.properties --print-digests <new-file>
```

It prints every value to pin (`raw.sizeBytes`, `raw.sha256`, `raw.lineCount`, `raw.eol`,
`normalized.*`) and exits `0` without comparing anything that is not pinned yet. Cross-check the
digests with an independent tool, redo the rights assessment, and state whether experiment results
remain comparable with v1.

## Non-goals respected

- No scraper, crawler, website fetcher or downloader in code, Gradle or CI.
- No book text in Git: `BookCorpusV1ContractTest.gitTracksNoBookBytes` lists Git-tracked files.
  It fails if this directory or the fixture directory tracks anything besides its known files, if
  any of them exceeds 64 KiB, or if any tracked file anywhere has the pinned raw or normalized
  SHA-256. Untracked local files are ignored.
- No chunking, storage ingestion, ranking or summarization; no production module changes.
