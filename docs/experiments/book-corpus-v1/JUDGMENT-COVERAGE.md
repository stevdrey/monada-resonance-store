# Book corpus v1: v1/v2 judgment coverage on one frozen ranking

[UNJUDGED-REVIEW.md](UNJUDGED-REVIEW.md) (#177) added 60 human-reviewed DEV labels as `book-queries-v2`, but
deliberately recomputed no metric. Issue #184 projects the **same** ranked hits of the frozen
`book-recall-v1-report.txt` onto the v1 labels and onto the v2 labels. Every difference between the two projections
is therefore a difference in **label coverage**, never in retrieval.

**Status: label coverage only.**
- Zero searches. No ranking, score, encoder, index, chunking policy, Store format or default changed.
- `book-queries-v1`, `book-queries-v2`, `book-recall-v1-report.txt` and every other report are byte-for-byte
  unchanged.
- A v2 number is **not** a retrieval improvement, and v2 is **not** independent test evidence (see
  [Limitations](#limitations)).

| File | Purpose |
| --- | --- |
| [`book-judgment-coverage-v2-report.txt`](book-judgment-coverage-v2-report.txt) | Committed report: inputs, unchanged-ranking digest, label transitions, denominators, projected metrics. |
| `monada-evaluation/src/main/java/com/monada/evaluation/book/BookJudgmentCoverage*.java` | Comparator (join and fail-closed checks), report renderer, CLI. |

## Run

```bash
./gradlew :monada-evaluation:runBookJudgmentCoverage -q
./gradlew :monada-evaluation:runBookJudgmentCoverage -q --args="--out docs/experiments/book-corpus-v1/book-judgment-coverage-v2-report.txt"
```

Offline, no book text needed. `--out` refuses every frozen input and alias of one (same rule as
`runBookUnjudgedReview`); the only protected file it may regenerate is the committed coverage report.
`BookJudgmentCoverageMainTest` requires a fresh render to equal the committed report byte for byte. So any JVM
that runs the tests checks that the report is reproduced exactly.

## Inputs and fail-closed checks

The only ranking input is the per-query trace of `book-recall-v1-report.txt` (SHA-256 `86501f76…a2ffd2`). The
comparator refuses to render when any of these checks fails:

| Check | Rejects |
| --- | --- |
| Pinned SHA-256 (in code) of the baseline, v1 `manifest.properties` and v2 `manifest.properties`, `selection.psv`, `review.psv` | Any edited input, including an edit that also refreshes the manifest digests. |
| Trace parse (`BookUnjudgedSelection.parseTrace`) | Unknown query, split/category drift, an atom ranked twice, or a v1 label that differs from `book-queries-v1`. |
| Ranking shape | Ranks that are not contiguous from 1, more than 10 hits, or scores that are not descending. A trace that is not in query-id order. |
| One atom per chunk | `store.atoms` different from `chunks.count`, or repeated-text atoms. Relevance keys are then exactly chunk keys. |
| v1 projection reproduces the baseline | Any per-query `RR`/`P@K`/`R@K`/`firstEvidenceRank` or partition metric line (P/R/Hit/direct, confounder/unjudged hits, MRR) that differs from the frozen report. |
| v2 status | `judgment.reviewStatus` other than `REVIEWED`, or undecided pairs. |
| No overwrite | v2 drops, reorders or changes a v1 judgment; a chunk judged twice. |
| HOLDOUT | Any HOLDOUT judgment that v2 adds. |
| Added judgments | A pair outside `selection.psv` or outside the query's traced hits; a label change other than `U → E1/E2/C0`. |

The `rankingDigest` (SHA-256 of `query|rank|atom|score` rows) identifies the ranking independent of labels.

## Result (committed report)

| | v1 | v2 |
| --- | --- | --- |
| Ranked hits (32 queries) | 320 | 320 (identical) |
| `U` among the 270 answerable top-10 hits | 256 | 205 |
| DEV label changes | — | 59 `U → C0`, 1 `U → E1` (60 occurrences = 60 distinct pairs on 32 chunks, 24 queries) |
| HOLDOUT label changes | — | 0 |
| Relevant chunks, DEV answerable | 34 | 35 (MUL-05: 3 → 4) |

Query denominators do not change: 20 DEV and 7 HOLDOUT answerable queries.

**DEV deltas come from one label.** The single new `E1` (MUL-05, rank 1) moves DEV Hit@K and MRR by +0.0500
(1/20) and R@K by +0.0125 (one query's recall from 0 to 1/4, divided by 20). It also adds one relevant hit to
P@K. The 59 `C0` labels change no known metric: a confounder and an unjudged hit both count as not relevant. They
only narrow the unknown bounds (DEV P@10 upper bound, every `U` counted relevant: 0.7450 with v2 labels). HOLDOUT
metrics are identical under both versions.

Read the deltas as *"with these extra labels the same ranking is known to have one more relevant top-1 hit"*. They
do not say the system retrieves better.

## Definitions

- **Occurrence:** one ranked top-10 hit of one query. **Pair:** a distinct (query, chunk). The trace ranks an atom
  at most once per query, so per query they coincide.
- **Known value:** `U` is counted as not relevant (the value the baseline reports). **Upper bound:** every `U` is
  counted as relevant (P@K, Hit@K, MRR). R@K has no upper bound, because unjudged chunks outside the top-10 leave
  the relevant set unknown.
- `U` is never turned into a negative label: unselected and unresolved hits stay unknown.

## Limitations

- **Score bits.** The frozen trace stores scores as 6-decimal tokens. The report compares those tokens exactly;
  raw `double` bits are not in the baseline and are not recomputed. The opt-in
  `BookJudgmentCoverageRealCorpusTest` (`MONADA_BOOK_STORE_READBACK_V1_WORK=<seeded dir>`, no download) re-runs
  the evaluation on a seeded Store. It checks atom ids, ranks and score tokens against the trace, and raw score
  bits between two fresh evaluations. That test is the only code path here that searches.
- **Post-hoc bias.** v2 labels are `POST_HOC_RETRIEVAL_SAMPLED_DEV`: sampled from this ranking's own hits and drafted
  by a model, then human-verified (see [UNJUDGED-REVIEW.md](UNJUDGED-REVIEW.md)). They concentrate on chunks this
  ranking already returns, so they are not independent test evidence.
- **HOLDOUT** was already exposed by the published v1 report. Keeping it unchanged does not make it fresh
  independent evidence.
- **Repeated text.** The comparator supports only stores where every chunk is its own atom (true for
  `book-corpus-v1`: 87 atoms, 87 chunks). It rejects any other store.

## Compatibility

Evaluation only: there is no persisted vector, ranking or Store change, so existing stores need no reuse, rebuild
or reject decision. The report format is `monada-book-judgment-coverage-report` version 1.
