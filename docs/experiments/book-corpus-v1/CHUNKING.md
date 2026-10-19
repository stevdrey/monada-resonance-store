# Book corpus v1 — chunking policy v1 (Book 2/8)

Issue #164 turns the verified `book-corpus-v1` normalized body into bounded, source-addressable text
chunks plus an external, versioned provenance sidecar. It is an offline evaluation step: no storage
ingestion, ranking or retrieval happens here, and no persisted Store format changes.

Code: `monada-evaluation/src/main/java/com/monada/evaluation/book/` (JDK only, no other module, no
network). Tests: `monada-evaluation/src/test/java/com/monada/evaluation/book/` with the original
fixture `monada-evaluation/src/test/resources/book-chunking-fixture-v1/`.

## Input

Only the **normalized body** written by
`VerifyBookCorpus.java --write-normalized` is accepted (see [README.md](README.md)). The chunker
re-verifies it before reading a single chapter:

- size and SHA-256 equal `normalized.sizeBytes` / `normalized.sha256` in `manifest.properties`;
- strict UTF-8 (the error names the byte offset), no BOM, LF only, ends with LF;
- no control characters other than LF and TAB, Unicode NFC, not empty or whitespace-only.

The Project Gutenberg wrapper is never stripped here; that is fixed by normalization policy v1 and
pinned by the manifest digest. Passing the raw download fails with a pointer to
`--write-normalized`.

## Policy `book-chunking-v1`

| Parameter | Value |
| --- | --- |
| `chapterHeadingPattern` | `CHAPTER [IVXLC]+\.` — must match a whole line, no trimming |
| `maxChunkBytes` | 2048 UTF-8 bytes, overlap included |
| `overlapBytes` | 0 |

The parameters are fixed in `BookChunkingPolicy.V1`. Changing any of them requires a new policy id,
because the id is part of every chunk key and of the sidecar header.

Algorithm (pure function of body bytes and policy; no clock, randomness, locale or hash order):

1. **Sections.** Each line that wholly matches the heading pattern starts a chapter running to the
   next heading. The indented contents list (` CHAPTER I.     Down the Rabbit-Hole`) does not match.
   Text before the first heading is section 0, `FRONT_MATTER`, emitted only if non-blank. A chapter
   is a structural boundary only; nothing is inferred from its content.
2. **Paragraphs.** Maximal runs of non-blank lines, with leading/trailing whitespace excluded.
3. **Packing.** Whole paragraphs are packed greedily while the span from the first paragraph start to
   the last paragraph end fits `maxChunkBytes - overlapBytes`. Chunks never cross sections.
4. **Oversized paragraphs** are split at the last whitespace within the budget; without whitespace,
   at the last code point boundary not followed by a combining mark, never inside a surrogate pair.
   A combining sequence longer than the budget cannot be split that way and is rejected (exit 1).
5. **Overlap** (only when `overlapBytes > 0`): a chunk after the first of its section starts at the
   earliest word start within `overlapBytes` before its core, never before the previous chunk's core
   start (which is itself eligible, so a short previous core may be repeated whole) and never in
   another section.
6. **Invariants**, re-checked after every run: each chunk is exactly a body substring that starts
   and ends with non-whitespace, is non-empty, within its section, in source order (starts never decrease, ends strictly increase) and within
   `maxChunkBytes`; every non-whitespace code point of the body is inside at least one chunk. A
   violation is a bug (`IllegalStateException`), never silently dropped text.

"Whitespace" means `Character.isWhitespace` or `Character.isSpaceChar` (so NBSP counts).

## Outputs

Both files are JSON Lines, UTF-8, LF, fixed field order, minimal escaping (non-ASCII is raw UTF-8),
published as one generation: writers to a directory are serialized by a lock on
`.book-chunking.lock`, the old sidecar is removed before the chunks file is replaced, and each file
goes through a uniquely named temporary file and an atomic move, sidecar last. A present sidecar
therefore always matches the chunks file beside it; chunks without a sidecar mean an interrupted run.

`chunks.jsonl` — one object per chunk:

```json
{"key":"book-corpus-v1/book-chunking-v1/s001/c0000","ordinal":1,"text":"CHAPTER I.\nDown the Rabbit-Hole\n\n..."}
```

`sidecar.jsonl` — a header, then one provenance object per chunk (no text):

| Header field | Meaning |
| --- | --- |
| `format`, `formatVersion` | `monada-book-chunk-sidecar`, `1` |
| `corpus`, `corpusVersion` | from the manifest |
| `sourceBytes`, `sourceSha256` | the verified normalized body |
| `manifestSha256` | SHA-256 of the manifest file the body was verified against |
| `policy` | `id`, `chapterHeadingPattern`, `maxChunkBytes`, `overlapBytes` |
| `frontMatter`, `chapterCount`, `chunkCount` | structure summary |
| `chunksFile`, `chunksFileSha256` | binds the sidecar to the exact chunks file |

| Chunk field | Meaning |
| --- | --- |
| `key` | `<corpus>/<policy>/s<section:03>/c<chunkInSection:04>` — positional and stable |
| `ordinal` | zero-based position among all chunks |
| `sectionOrdinal`, `sectionKind`, `sectionHeading` | 0 / `FRONT_MATTER` / `null`, or k / `CHAPTER` / heading line |
| `chunkInSection` | zero-based position inside the section |
| `byteStart`, `byteEnd` | UTF-8 byte span in the normalized body, end exclusive |
| `codePointStart`, `codePointEnd` | code point span, end exclusive |
| `lineStart`, `lineEnd` | 1-based lines of the first and last character |
| `utf8Bytes`, `sha256` | size and SHA-256 of the chunk text |
| `duplicateOf` | key of the first earlier chunk with identical text, or `null` |

## Repeated content and Store IDs

`KnowledgeAtom.text(...)` derives the atom ID from the exact content
(`UUID.nameUUIDFromBytes(utf8)`), so two chunks with identical text would map to one Store atom.
The chunk key is positional, so they keep distinct, resolvable provenance; `duplicateOf` and the
report's `repeated content` line make the collision explicit. `BookChunkerTest` proves both.

## Storage compatibility

No persisted vector, ranking, atom, manifest or `FileAtomStore` semantics change. Provenance lives
in the external sidecar, not in `KnowledgeAtom.metadata` (which `FileAtomStore` does not persist,
ADR 0004). Existing stores are unaffected; there is nothing to reuse, rebuild or reject.

## Running

```bash
java docs/experiments/book-corpus-v1/VerifyBookCorpus.java --write-normalized ~/monada-corpora/book-corpus-v1/body.txt ~/monada-corpora/book-corpus-v1/pg11.txt
./gradlew :monada-evaluation:runBookChunking --args="--input $HOME/monada-corpora/book-corpus-v1/body.txt --out $HOME/monada-corpora/book-corpus-v1/chunks-v1"
```

`--manifest` defaults to `docs/experiments/book-corpus-v1/manifest.properties`. Exit `0` success,
`1` body is not the verified normalized text, `2` usage error, missing file, bad manifest, an output
path that would overwrite an input, or an I/O failure. Keep outputs outside the repository: they
contain book text.

The report prints chunk count, sections, minimum / lower-median / maximum chunk size in UTF-8
bytes, repeated-content groups, and the source, manifest, chunks and sidecar digests. It contains
no paths or timing, so it is comparable across machines.

## Reproducibility

- Same body SHA-256 + same policy + same manifest bytes ⇒ byte-identical `chunks.jsonl`,
  `sidecar.jsonl` and report. The manifest matters too: its `corpus` is part of every chunk key, and
  `corpusVersion` plus the digest of the whole manifest file (comments included) are in the sidecar
  and report. Compare runs that used the same checked-out manifest.
  `BookChunkingGoldenTest` pins the fixture output byte for byte and runs the command in three
  concurrent child JVMs, requiring identical bytes.
- Real corpus (opt-in, never in CI):

  ```bash
  MONADA_BOOK_CORPUS_V1_BODY=$HOME/monada-corpora/book-corpus-v1/body.txt ./gradlew :monada-evaluation:test --tests '*BookChunkingRealCorpus*'
  ```

  It expects front matter plus 12 chapters and two identical runs.
- To compare machines, run `runBookChunking` on each and compare `sha256sum chunks.jsonl sidecar.jsonl`.

## Non-goals respected

- No general document ETL, PDF/OCR, HTML crawler, tokenizer or model dependency.
- No change to `KnowledgeAtom`, atom ID derivation, `FileAtomStore`, the Store manifest or
  `book-corpus-v1/manifest.properties`.
- Chapter boundaries are structural; no semantic facts are inferred.
