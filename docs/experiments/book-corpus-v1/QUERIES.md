# Book corpus v1 — judged query set `book-queries-v1` (Book 4/8)

Issue #166 adds hand-judged questions with gold relevance labels over the frozen `book-corpus-v1` normalized body.
They make long-document retrieval quality measurable in Book 5/8. Some questions have no answer in the source.
This step is evaluation data only:
- no retrieval, ranking or Store run produced or edited any label;
- no production module, persisted format or protected baseline changes.

Data: `monada-evaluation/src/main/resources/com/monada/evaluation/book/book-queries-v1/`. Validation (test sources):
`BookQueryDataset`, `BookQueryDatasetTest`, `BookQueryDatasetRejectionTest` and the opt-in
`BookQueryDatasetRealCorpusTest` in `monada-evaluation/src/test/java/com/monada/evaluation/book/`.

## Files

| File | Content |
| --- | --- |
| `manifest.properties` | Identity, corpus and chunk binding, SHA-256 of every label file, protocol, reviewer, split rule and digest, expected counts. |
| `queries.psv` | `id\|split\|category\|answerable\|text\|rationale`, one row per case. |
| `judgments.psv` | `queryId\|chunkKey\|byteStart\|byteEnd\|spanSha256\|grade\|role\|note`, one row per judged chunk. |
| `chunk-map.jsonl` | The unmodified `sidecar.jsonl` of the `book-chunking-v1` run over the verified body (keys, spans and SHA-256 values only; no text). |

None of these files contains book text. A judgment points to the verified normalized body through a UTF-8 byte span
`[byteStart, byteEnd)` and the SHA-256 of those bytes. It also names the `book-chunking-v1` chunk key that contains the span.
CI resolves every key and span offline through `chunk-map.jsonl`. With the opt-in test, the real body recomputes every span digest.

## Labels

| Role | Grade | Meaning |
| --- | --- | --- |
| `EVIDENCE` | 2 | The span directly answers the question. |
| `EVIDENCE` | 1 | Partial or supporting evidence; insufficient alone. |
| `CONFOUNDER` | 0 | Judged non-relevant although it shares words, characters or scene with the question. |

A chunk is judged at most once per case. Chunks that are not listed are unjudged, not implicitly non-relevant. Book 5/8
must report unjudged hits as such.

| Category | Cases | What it tests |
| --- | --- | --- |
| `LITERAL` | 5 | The answer is stated once, in words close to the question. |
| `PARAPHRASE` | 6 | The answer is present, but the question deliberately avoids its key words (e.g. "smile" for grin). |
| `NARRATIVE_TEMPORAL` | 4 | The answer depends on story order ("right after", "just before"). |
| `MULTI_EVIDENCE` | 5 | The answer is spread over two or more chunks, usually in different chapters. |
| `CONFOUNDER` | 4 | Correct evidence plus at least one plausible but wrong chunk (same word or character, different scene). |
| `AMBIGUOUS` | 3 | Several defensible readings, all judged. |
| `NO_ANSWER` | 5 | Plausible, but the corpus does not contain the answer. Confounders are optional near misses; any evidence is rejected. |

32 cases and 55 judgments in total. The acceptance thresholds hold: 5 no-answer cases, 6 paraphrases, and
9 multi-evidence or distractor cases.

Rejected at load (`BookQueryDatasetRejectionTest`):
- **contradictory labels:** the same chunk twice in one case (for example as both evidence and confounder), evidence
  with grade 0, a confounder with a positive grade, or an answerable flag that contradicts `NO_ANSWER`;
- **empty labels:** an answerable case without evidence or without grade-2 evidence, a multi-evidence or ambiguous
  case with a single chunk, a confounder case without a confounder, or blank text, rationale or note;
- **orphan labels:** an unknown query id or chunk key, or a span that is empty, outside its chunk or outside the
  source;
- **schema and digest drift:** extra columns (for example a score), unknown enum values, duplicate ids, wrong
  counts, a mismatching file digest, a chunk map for another source, policy or corpus manifest, a hand-edited
  split, or labels declared as edited after a retrieval run.

## Authoring protocol

1. **Blinded:** the cases were written by reading the verified normalized body (`body.txt`) with a text viewer.
   No query was run against a Store, index, encoder or ranking, so labels contain no retrieval output and no
   case was chosen because current lexical encoding finds or misses it. `judgment.retrievalRunsBeforeFreeze=0`,
   and the tests reject score-like columns or terms.
2. Questions and rationales are original wording; evidence is referenced by span and digest only.
3. A scratch script turned line ranges into byte spans, chunk keys and SHA-256 values. It reads only `body.txt`
   and the sidecar. It failed whenever a span did not fit inside exactly one chunk.
4. **Draft and review:** `judgment.draftedBy` records who drafted the cases. A human reviewer then reads each
   case against the review sheet and records `judgment.reviewer`, `judgment.reviewedAt` and
   `judgment.reviewStatus=REVIEWED`.
   - The review sheet is local only, because it quotes the evidence text: `book-queries-v1-review.md`, kept next to
     `body.txt`.
   - `BookQueryDatasetTest.labelsAreHumanReviewed` fails until this sign-off is recorded.
5. Freeze: the manifest pins the SHA-256 of `queries.psv`, `judgments.psv` and `chunk-map.jsonl`.

## Dev / holdout partition

The split is fixed by rule before any retrieval and cannot be edited by hand:
1. Per category, rank case ids by `SHA-256("book-queries-v1|" + id)`.
2. The last ⌊n/3⌋ ids are `HOLDOUT`; the rest are `DEV`.

This gives 24 `DEV` and 8 `HOLDOUT` cases. Every category has dev cases, and every category with three or more cases has held-out cases. `split.digest`
is the SHA-256 of the sorted `id:SPLIT` lines. The loader recomputes both the rule and the digest.

Use of the split in Book 5/8 and later:
- **`DEV`:** inspect, tune and debug freely.
- **`HOLDOUT`:** report only, never tune on it.

Labels are never edited after seeing results. A label that turns out to be wrong is recorded as a finding;
the fix goes into a new `book-queries-v2` with its own manifest, split and digests. That version states whether its
results remain comparable with v1.

## Running

```bash
./gradlew :monada-evaluation:test --tests '*BookQueryDataset*'
```

Real corpus (opt-in, never in CI), after `VerifyBookCorpus.java --write-normalized` (see [README.md](README.md)):

```bash
MONADA_BOOK_CORPUS_V1_BODY=$HOME/monada-corpora/book-corpus-v1/body.txt ./gradlew :monada-evaluation:test --tests '*BookQueryDatasetRealCorpus*'
```

It regenerates the chunk map with `book-chunking-v1`. It requires byte-identical output and the pinned
`chunks.jsonl` digest, and checks all 55 span digests against the body.

## Non-goals respected

- No labels generated or filtered by the system under evaluation; no retrieval metrics are reported here (Book 5/8).
- No answer-generation or hallucination judging; these are retrieval relevance labels only.
- No change to `KnowledgeAtom`, `MonadaMemory`, the Store format, `book-corpus-v1/manifest.properties` or the
  chunking policy; no book text in Git.
