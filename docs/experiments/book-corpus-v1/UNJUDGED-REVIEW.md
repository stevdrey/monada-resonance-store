# Book corpus v1: human review of unjudged DEV hits (`book-queries-v2`)

[RECALL.md](RECALL.md) (#167) found that **256 of the 270** answerable query/top-10 hit occurrences are `U`
(unjudged). `U` means *unknown*, not *false positive*. Issue #177 has a human adjudicate a bounded, predeclared
sample of those hits. It freezes the result as `book-queries-v2`, an append-only extension of
`book-queries-v1`.

**Status: label coverage only.**
- No ranking, encoder, index, chunking policy, Store format or default changed.
- `book-queries-v1`, its DEV/HOLDOUT split and `book-recall-v1-report.txt` are byte-for-byte unchanged.
- Only DEV pairs are selected and labelled. HOLDOUT hits are counted, but never selected, labelled or used to choose
  anything.
- v2 metrics would use a different judgment coverage than v1. They are **not** comparable, and a v2 number is never
  a retrieval improvement.

| File | Purpose |
| --- | --- |
| `monada-evaluation/src/main/resources/com/monada/evaluation/book/book-queries-v2/manifest.properties` | v1 pins, provenance, selection rule and seed, review status and sign-off, counts. |
| `…/book-queries-v2/selection.psv` | Selection receipt (**not for the reviewer**: it holds rank, score and hub count). |
| `…/book-queries-v2/review.psv` | Human decisions: byte span, SHA-256, grade and note. No book text. |
| [`book-unjudged-review-v2-report.txt`](book-unjudged-review-v2-report.txt) | Coverage report: unjudged occurrences under v1 and v2, selection coverage, review outcome. |
| `monada-evaluation/src/main/java/com/monada/evaluation/book/BookUnjudgedSelection.java`, `BookReviewWorksheet.java`, `BookQueryDatasetV2.java`, `BookUnjudgedReview*.java` | Selection, worksheet and apply, v2 loader and validation, report and CLI. |

## Provenance and bias (`POST_HOC_RETRIEVAL_SAMPLED_DEV`)

The pairs come from a published run of the system being evaluated. Two consequences follow:
- Even with a score-blind review, the v2 labels are **not** an independent, prospective gold standard. They
  concentrate on chunks this ranking already returns.
- HOLDOUT results were already visible in v1's published report, so HOLDOUT is left untouched.

A genuinely independent held-out evaluation needs new queries that are reviewed prospectively, in another Issue.

## Selection (predeclared, before any new label)

Rule `dev-unjudged-top1-top3-hub-seeded-v1`, seed `book-queries-v2|unjudged-review-v1|`, target 60.

**Input.** The input is only the per-query trace of `book-recall-v1-report.txt`, at SHA-256 `86501f76…a2ffd2`.
- Every hit's split, category and v1 label must agree with `book-queries-v1`.
- A pair is a (query, canonical chunk key). For an atom shared by several chunks with the same text, the canonical
  key is the first one.

**Eligible pairs.** A pair is eligible when it is DEV and `U`: 229 occurrences, which are also 229 distinct pairs,
because the trace ranks an atom at most once per query.

**Strata.** Applied in order; a pair is never chosen twice, and ties go to query id, then chunk key:

| Stratum | Rule | Selected |
| --- | --- | --- |
| `TOP1` | Every eligible rank-1 pair | 22 |
| `TOP3` | Per DEV query, its best remaining eligible pair at rank 2–3 (covers every category, including NO_ANSWER near misses) | 24 |
| `HUB` | For the 4 chunks with the most DEV `U` occurrences, one remaining pair each, by minimum `sha256(seed + query + "\|" + chunk)` | 4 |
| `SAMPLE` | Fill to 60 from the rest, by ascending `sha256(seed + query + "\|" + chunk)` | 10 |

**Receipt.** Pair ids are `UJ-` plus 8 hex characters of a content hash, so they reveal neither stratum nor rank.
The receipt is re-derived and compared byte for byte by `BookUnjudgedSelectionTest` and by:

```bash
./gradlew :monada-evaluation:runBookUnjudgedReview --args="select"
```

## Human review (score-blind)

1. Generate the local worksheet. It holds book text, so it **must stay outside Git**: the command refuses any path
   inside a Git work tree and never overwrites an existing file.

   ```bash
   ./gradlew :monada-evaluation:runBookUnjudgedReview --args="worksheet --input $HOME/monada-corpora/book-corpus-v1/body.txt --out $HOME/monada-corpora/book-corpus-v1/book-queries-v2-review.md"
   ```

   For each pair, the sheet shows only:
   - the neutral pair id;
   - the query;
   - whether v1 declares the query answerable;
   - the verified chunk text.

   It shows no rank, score, hub frequency, stratum, chunk key, byte offsets or proposed label, and the order is a
   seeded shuffle. Do not open `selection.psv` or the coverage report until the review is done.

   **Protocol deviation in the committed review.** The worksheet behind the committed `review.psv` also printed
   each pair's chunk key and byte range. It showed no rank, score, stratum or label, but a repeated key could
   reveal a recurring (hub) chunk, and an offset reveals the story position. The renderer no longer prints either.
   Whether to re-review the 60 pairs with a blinded worksheet is the reviewer's decision.
2. For each pair, fill in `decision`:

   | Decision | Grade | Effect |
   | --- | --- | --- |
   | `EVIDENCE` | 2 = directly answers, 1 = partial | Added as a judgment |
   | `CONFOUNDER` | 0 = judged non-relevant | Added as a judgment |
   | `UNRESOLVED` | none (give a reason) | The pair stays unjudged; no fake grade-0 row is written |
   | `NO_ANSWER_CONTRADICTED` | the grade the chunk would get | Only for a v1 NO_ANSWER query; recorded as a finding (see below) |

   Also fill in `quote`, the exact passage the decision rests on, and a short `note`. A blank decision keeps the pair
   pending.
3. Apply the decisions:

   ```bash
   ./gradlew :monada-evaluation:runBookUnjudgedReview --args="apply --input $HOME/monada-corpora/book-corpus-v1/body.txt --worksheet $HOME/monada-corpora/book-corpus-v1/book-queries-v2-review.md --dataset monada-evaluation/src/main/resources/com/monada/evaluation/book/book-queries-v2"
   ```

   `apply` does the following:
   - locates each quote once in its chunk (whitespace-insensitive);
   - records the byte span and its SHA-256 against the verified body;
   - writes `review.psv`;
   - updates only `review.sha256` and `counts.*`.

   Nothing is written unless the whole dataset validates. `apply` never sets the review status, and it refuses
   to change the decisions of a `REVIEWED` dataset: reset the status and clear the sign-off first, so a sign-off
   never covers labels that nobody approved.
4. **Sign-off is manual and human.** The reviewer edits `judgment.reviewStatus=REVIEWED`, `judgment.reviewer`,
   `judgment.approvedBy` and `judgment.reviewedAt` (ISO date). The loader rejects `REVIEWED` in these cases:
   - any selected pair is still pending;
   - the reviewer or approver is missing, or equals the drafter (`selection.draftedBy=claude`);
   - the date is not ISO.

   An AI-authored claim of approval is not a sign-off. A partially reviewed v2 stays `PENDING_HUMAN_REVIEW`.
5. Regenerate the report:

   ```bash
   ./gradlew :monada-evaluation:runBookUnjudgedReview --args="report --out docs/experiments/book-corpus-v1/book-unjudged-review-v2-report.txt"
   ```

### NO_ANSWER findings

`book-queries-v2` never silently reclassifies a v1 NO_ANSWER query:
- `EVIDENCE` on a NO_ANSWER query is rejected.
- `NO_ANSWER_CONTRADICTED` is kept as a finding with its span. It never becomes a judgment, and the v1 case stays
  NO_ANSWER.
- Any finding blocks `REVIEWED` until the reviewer sets an explicit, versioned `reclassification.policy`, which
  replaces `NONE`. Even then, any reclassification belongs in a later dataset version.

## Validation (`BookQueryDatasetV2`)

`BookQueryDatasetV2` loads v1 through the unchanged `BookQueryDataset.load()` and pins the v1 manifest, query,
judgment and chunk-map digests. It then rejects:
- a drifted v1 manifest or v1 files, or a changed v1 judgment count;
- a selection or review digest that does not match;
- another baseline report, rule, seed or target;
- a provenance other than `POST_HOC_RETRIEVAL_SAMPLED_DEV`;
- a HOLDOUT pair;
- a pair that v1 already judges, which would duplicate or contradict a v1 label;
- an unknown chunk, a changed category, or a span outside its chunk;
- an invalid grade for its decision, a grade or span on `UNRESOLVED`, or an unknown decision;
- a review of an unselected pair, or a pair reviewed twice;
- `EVIDENCE` on NO_ANSWER, or `NO_ANSWER_CONTRADICTED` on an answerable query;
- stale counts, and any of the sign-off defects above.

The v2 cases are the 32 v1 cases with their v1 judgments first and unchanged, followed by the approved DEV
additions.

Opt-in check that every reviewed span hashes correctly against the real body:

```bash
MONADA_BOOK_CORPUS_V1_BODY=$HOME/monada-corpora/book-corpus-v1/body.txt ./gradlew :monada-evaluation:test --tests '*BookQueryDatasetV2RealCorpusTest'
```

## Follow-up (not in this Issue)

A separate Issue should re-run the read-only metrics of the **unchanged** ranking on v2 labels. It must report v1
and v2 side by side as different judgment coverage, without candidate optimization, HOLDOUT-derived tuning or any
improvement claim.
