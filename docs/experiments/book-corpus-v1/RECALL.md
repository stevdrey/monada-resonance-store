# Book corpus v1: recall quality against judged evidence (Book 5/8)

This experiment measures how well the seeded `book-corpus-v1` Store recalls the evidence judged in
`book-queries-v1` (issue #167). It builds on the persisted Store from #165 and the frozen judgments from #166.

A hit counts as success **only** when the frozen judgments call its chunk evidence for that query. The report also
surfaces:
- wrong evidence (a judged confounder or an unjudged chunk ranked above the evidence);
- missed evidence (a relevant chunk absent from the top-K);
- NO_ANSWER false hits.

**Status: exploratory.** Similarity scores are not calibrated truth. This experiment promotes no threshold,
regression gate or configuration, and it does not change production scoring.

| File | Purpose |
| --- | --- |
| [`book-recall-v1-report.txt`](book-recall-v1-report.txt) | Complete versioned report: inputs, metrics, threshold exploration, failure examples, full per-query trace. |
| `monada-evaluation/src/main/java/com/monada/evaluation/book/BookRecall*.java` | Evaluator, failure taxonomy, report renderer, CLI. |

## Running it (offline, no network)

The work directory must already be seeded and verified as described in [STORE-READBACK.md](STORE-READBACK.md):

```bash
./gradlew :monada-evaluation:runBookRecall --args="--work $HOME/monada-corpora/book-store-readback-v1 --out docs/experiments/book-corpus-v1/book-recall-v1-report.txt"
```

If you leave out `--out`, the report goes to standard output.

Exit codes:
- `0`: the report was produced.
- `1`: one of these failures:
  - the inputs are not the judged corpus;
  - the Store fails readback;
  - a gold reference is invalid;
  - result ordering is unstable.
- `2`: usage error, missing or unseeded work directory, or I/O failure.

The work directory is only read. Queries run on a throwaway copy of the Store, and the Store's tree digest is
compared before and after the run. `--out` must point outside the work directory, compared on real paths so a
symbolic link cannot route the report into it; otherwise the command exits `2` before evaluating.

The opt-in test regenerates the report and requires it to be byte-identical to the committed file:

```bash
MONADA_BOOK_STORE_READBACK_V1_WORK=$HOME/monada-corpora/book-store-readback-v1 ./gradlew :monada-evaluation:test --tests '*BookRecallRealCorpus*'
```

## What is bound before anything is measured

The evaluation fails deterministically, measuring nothing, when any of these checks fails:

1. **Corpus binding.** The work directory's chunk files must be the chunk set the judgments were made against. The
   following must equal the dataset manifest:
   - `corpus` and `corpusVersion`;
   - source size and SHA-256;
   - corpus manifest SHA-256;
   - chunking policy;
   - `chunks.jsonl` SHA-256;
   - sidecar SHA-256 (`chunk-map.jsonl` is that sidecar).

   Any mismatch is reported as `foreign corpus input: …`.
2. **Store integrity.** The #165 readback runs on fresh instances and must pass, with exact readback of every chunk
   and a `HEALTHY` audit. The Store must carry the encoding profile of `MonadaMemoryOptions.defaults()`.
   The feedback log must be absent or empty: the default options rank feedback-aware, so recorded feedback would
   change scores without changing the atom fingerprint. The report header binds `store.vectorsSha256`, a digest of the
   Store files that decide ranking (manifest, vector segment, vector index), so altered or stale vectors cannot
   produce a different report under the same identity. The atom log is left out on purpose: it records wall-clock
   creation times, so any reseeding of the same corpus would otherwise change the identity. Its content is bound by
   the exact readback and `store.fingerprint`.
3. **Gold validity.**
   - Every judged chunk key exists in the chunk set.
   - Every answerable query has evidence, and no NO_ANSWER query has any.
   - The dataset is not empty.

   This is in addition to all the #166 loader checks.
4. **Stable ordering.** Each query runs twice, on two fresh `MonadaMemory` instances. The two runs must be identical.
   Results must be ordered by score descending, then atom id ascending. Every hit must be an atom of the chunk set, with
   a finite score and no repeats.

## Configuration measured

There is exactly one configuration, so nothing was selected:
- `MonadaMemory.resonate(query).topK(10).threshold(-Infinity)` with `MonadaMemoryOptions.defaults()`;
- `SimpleFrequencyEncoder` 1.0, 128 dimensions;
- `LexicalEnrichmentPipeline` and `WeightedTokens`.

The full stored `EncodingProfile` is in the report header. K ∈ {1, 3, 5, 10}.

## Metrics and denominators

- **Relevant** means judged `EVIDENCE` of the query (grade 1 partial or grade 2 direct). Chunks with identical text
  share one content-derived atom, so they are relevance-mapped to the first key of that atom. Policy v1 has no repeated
  chunk on this book, and the fixture tests cover the repeated case.
- **P@K, R@K, Hit@K, MRR** reuse the shared `PrecisionAtK`, `RecallAtK`, `HitAtK` and `ReciprocalRank` functions over
  relevance keys. The denominator is the number of **answerable** queries in the group, printed as `n`.
  - P@K divides by K even when fewer than K hits are returned.
  - `Hit@K(direct)` counts grade-2 evidence only.
- **Hit labels:**
  - `E2`, `E1`: judged evidence of that grade;
  - `C0`: judged confounder;
  - `U`: unjudged for this query.

  `U` hits are **not** assumed non-relevant. Their count at each K is reported next to the metrics.
- **NO_ANSWER queries** have no relevant set, so they are excluded from P/R/Hit/MRR. They are reported as
  **false hits** instead. At the evaluated threshold (−∞), any returned chunk is a false hit.
- **Threshold sensitivity** uses a fixed grid of thresholds from 0.00 to 0.95 in steps of 0.05. For each threshold it
  shows:
  - how many NO_ANSWER queries would still return a hit;
  - how many answerable queries would keep their first retrieved evidence.

  It is exploration only. The grid was fixed before the first run and nothing is selected from it.
- **Partitions:** `DEV`, `HOLDOUT` and `ALL` are reported separately. `HOLDOUT` was not used to choose anything,
  because there was nothing to choose.

## Failure taxonomy

Each answerable query is classified at K=10. The first matching class wins:

| Class | Meaning |
| --- | --- |
| `NO_RELEVANT_RESULT` | No judged evidence in the top-10. |
| `DISTRACTOR` | A judged confounder (grade 0) ranks above the first evidence. |
| `CHUNK_BOUNDARY_GAP` | An unjudged chunk adjacent (ordinal ±1) to an evidence chunk ranks above the first evidence: the passage may straddle a chunk boundary. |
| `WRONG_RANK` | Evidence is retrieved, but below rank 1, behind other unjudged chunks. |
| `MULTI_EVIDENCE_GAP` | Rank 1 is evidence, but some judged evidence chunk is missing from the top-10. |
| `NONE` | Rank 1 is evidence and every evidence chunk is in the top-10. |

NO_ANSWER queries are classified `FALSE_POSITIVE` (something was returned) or `ABSTAINED` (nothing was returned).

The report renders at least three failure examples in full: the first query of each failure class, then further
failures, up to six. Each example shows the query, the expected chunk keys with byte spans and grades, the actual
top-5, and the first evidence rank. Examples contain **no book text and no answer text**. Reviewer judgment notes
are left out of the report entirely, because some of them state the answer; they stay in `judgments.psv`.

## Results (book-corpus-v1, `book-chunking-v1`, `book-queries-v1`)

These results come from a run on 2026-10-21 with OpenJDK 27 on Linux (Fedora 44). The input was the Store seeded in
[STORE-READBACK.md](STORE-READBACK.md): 87 atoms, fingerprint `dd11b6ba…03d7`. Two runs produced byte-identical
reports.

| Partition | n (answerable) | P@1 | Hit@1 | Hit@3 | Hit@5 | Hit@10 | R@10 | MRR | NO_ANSWER false hits |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DEV | 20 | 0.0500 | 0.0500 | 0.2500 | 0.2500 | 0.4500 | 0.2708 | 0.1705 | 4/4 |
| HOLDOUT | 7 | 0.1429 | 0.1429 | 0.2857 | 0.4286 | 0.4286 | 0.3095 | 0.2500 | 1/1 |
| ALL | 27 | 0.0741 | 0.0741 | 0.2593 | 0.2963 | 0.4444 | 0.2809 | 0.1911 | 5/5 |

| Failure class (ALL) | Queries |
| --- | --- |
| `NO_RELEVANT_RESULT` | 15 |
| `WRONG_RANK` | 6 |
| `CHUNK_BOUNDARY_GAP` | 4 |
| `MULTI_EVIDENCE_GAP` | 1 |
| `NONE` | 1 |
| `FALSE_POSITIVE` (NO_ANSWER) | 5 / 5 |

By category (DEV + HOLDOUT):

| Category | Hit@1 | Hit@10 | MRR |
| --- | --- | --- | --- |
| LITERAL | 0.20 | 0.40 | 0.30 |
| PARAPHRASE | 0.00 | 0.17 | 0.02 |
| NARRATIVE_TEMPORAL | 0.00 | 0.50 | 0.19 |
| MULTI_EVIDENCE | 0.00 | 0.80 | 0.25 |
| CONFOUNDER | 0.25 | 0.50 | 0.29 |
| AMBIGUOUS | 0.00 | 0.33 | 0.11 |

### Analysis

- **Rank-1 evidence is rare.** Only 2 of the 27 answerable queries put judged evidence at rank 1: `LIT-02`
  (`NONE`) and `CON-04` (`MULTI_EVIDENCE_GAP`). Self-recall@1 is 87/87 in #165, but that measures a chunk queried
  with its own text. Real questions retrieve evidence in the top-10 for fewer than half of the answerable queries.
- **Paraphrase is the weakest category.** 5 of 6 PARAPHRASE queries find no evidence in the top-10, which is
  consistent with a lexical frequency encoder. This is an observation, not a claim about semantic understanding.
- **Hub chunks.** One chunk, `s009/c0001`, is the top-1 hit for 6 of 32 queries across unrelated topics, and it is
  also the top-1 hit for two NO_ANSWER queries. A chunk that ranks high for everything depresses P@1 everywhere.
  Explaining why needs a separate investigation, for example of token frequency and chunk length.
- **Boundary effects are real but secondary.** 4 queries have a neighbour of the evidence chunk ranked above it, for
  example `CON-03`, where `s009/c0005`, the chunk right after the evidence `s009/c0004`, ranks above it. Whether the
  overlap or size policy should change is a question for a later Book issue. It must be decided on DEV only.
- **Judged confounders rarely win.** Across answerable queries, only 1 judged confounder appears in any top-10
  (`CON-03`, rank 8, below its evidence at rank 6), so no query is classified `DISTRACTOR`. The failures come from unjudged chunks, which are counted as `U` (256 unjudged hits at K=10 over 27 queries).
  Some of them could be relevant passages the judges did not list. Changing that requires `book-queries-v2`, never an
  edit to v1.
- **Abstention is not possible at any useful threshold.** NO_ANSWER top-1 scores range from 0.183 to 0.449. At
  t=0.20, 4/4 DEV false hits remain while only 4/20 DEV evidence hits survive. No grid point separates the two, so no
  threshold is proposed.
- **DEV and HOLDOUT agree** within what 20 and 7 queries can show. HOLDOUT is slightly higher on Hit@1 (1/7, `LIT-02`)
  and MRR, which is noise at this size.

### Caveats

- The results come from one book, one chunking policy, one configuration, and 27 answerable plus 5 NO_ANSWER queries.
  Differences of one query move a partition mean by 0.05 (DEV) or 0.14 (HOLDOUT).
- Scores are cosine similarities of an uncalibrated frequency encoder. The threshold table describes this Store only.
- Results depend on the host only through the JDK's floating-point arithmetic, which is deterministic for this code.
  The committed report was produced on Linux with OpenJDK 27.

## Non-goals respected

- No change to production scoring, `MonadaMemory`, `monada-api`, `monada-index`, `monada-encoder`, `monada-learning`,
  the Store format, the manifests or the exact-query defaults.
- No answers are generated, no LLM is called, no encoder is trained, and nothing is tuned on HOLDOUT.
- No similarity threshold or regression gate is promoted. The protected baselines (`run`, `runExpanded`,
  `runProfileComparison`) are unaffected.
- The labels in `book-queries-v1` are unchanged, and the report contains no book text.

## Follow-ups (not in this issue)

- Investigate hub chunks such as `s009/c0001`: why do they score high for unrelated queries? Diagnosed in
  [HUB-DIAGNOSTICS.md](HUB-DIAGNOSTICS.md) (#176, evaluation only).
- Explore chunk size and overlap on DEV only, then confirm on HOLDOUT once.
- Judge the most frequent `U` hits for `book-queries-v2`, so unjudged hits stop hiding possible relevance.
