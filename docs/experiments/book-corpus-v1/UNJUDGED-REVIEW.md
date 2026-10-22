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

### Label drafting (`judgment.draftedBy=codex`)

The 60 committed decisions were **drafted by Codex**, an AI model, as an AI-assisted proposal on 2026-10-21. The
filled worksheet's preamble records this. The reviewer, Steven Rey Sequeira, states that each of the 60 proposals
was independently reviewed and approved before sign-off. The manifest records this:

| Key | Value |
| --- | --- |
| `judgment.draftedBy` | `codex` |
| `judgment.draftedAt` | `2026-10-21` |
| `judgment.verification` | `EACH_DRAFTED_DECISION_REVIEWED_AND_APPROVED_BY_REVIEWER` |
| `judgment.draftBlinding` | `UNVERIFIED` |

**The drafting step is not shown to be score-blind.** What Codex could see while drafting is unknown; it may have
had repository access, where `selection.psv` holds each pair's rank and score and the coverage report lists the
selected pairs. `review.protocol` describes the worksheet only. `judgment.draftBlinding` records this:
`WORKSHEET_ONLY_VERIFIED`, `UNVERIFIED` or `NOT_BLIND`, required whenever labels were drafted.

The relevance labels therefore rest on that human verification, not on the model's proposal alone. Readers should
weigh them as **model-drafted, human-verified** labels, a further reason they are not an independent prospective
gold standard. A dataset whose labels were drafted by anyone but the reviewer is `REVIEWED` only with a dated draft
and that verification value, and neither the reviewer nor the approver may be a drafter. `judgment.draftedBy=NONE`
means the reviewer decided directly.

Because of the added provenance line, that worksheet no longer passes `apply`'s check for an unedited preamble. The
committed `review.psv` came from it unchanged, and it is not re-applied.

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
   The reviewer chose to keep the signed labels. The manifest records what was actually used:
   `review.protocol=SCORE_BLIND_WORKSHEET_QUERY_CHUNK_TEXT_KEY_AND_BYTE_RANGE`. New worksheets carry a
   `protocol: SCORE_BLIND_WORKSHEET_QUERY_AND_CHUNK_TEXT_ONLY` line, and `apply` copies the protocol of the sheet
   into the manifest whenever the decisions change; a sheet without that line is recorded as the legacy protocol.
2. For each pair, fill in `decision`:

   | Decision | Grade | Effect |
   | --- | --- | --- |
   | `EVIDENCE` | 2 = directly answers, 1 = partial | Added as a judgment |
   | `CONFOUNDER` | 0 = judged non-relevant | Added as a judgment |
   | `UNRESOLVED` | none (give a reason) | The pair stays unjudged; no fake grade-0 row is written |
   | `NO_ANSWER_CONTRADICTED` | the grade the chunk would get | Only for a v1 NO_ANSWER query; recorded as a finding (see below) |

   Also fill in `quote`, the exact passage the decision rests on, and a short `note`. The quote stays in the local
   sheet; the note is committed, so `apply` rejects a note that copies 6 or more consecutive words of the chunk. A blank decision keeps the pair
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
   to change the decisions of a signed dataset: set the status back to `PENDING_HUMAN_REVIEW` and clear the
   reviewer, approver, date, `judgment.draftedAt` and `judgment.verification` first, so neither a sign-off nor a
   draft verification ever covers labels that nobody approved. Until the dataset is `REVIEWED`, applied decisions
   are review progress only: the loader does not merge them into the v2 judgments. The `counts.*` that `apply`
   writes already describe the judgments after sign-off, so the manual switch to `REVIEWED` needs no re-apply.
   `apply` refuses to run unless the current manifest and `review.psv` validate, and it decides whether decisions
   changed by comparing with the `review.psv` on disk.
4. **Sign-off is manual and human.** The reviewer edits `judgment.reviewStatus=REVIEWED`, `judgment.reviewer`,
   `judgment.approvedBy` and `judgment.reviewedAt` (ISO date). The loader rejects `REVIEWED` in these cases:
   - any selected pair is still pending;
   - the reviewer or approver is missing, or equals the drafter (`selection.draftedBy=claude`);
   - the date is not ISO.

   An AI-authored claim of approval is not a sign-off. A partially reviewed v2 stays `PENDING_HUMAN_REVIEW`. If
   anyone other than the reviewer proposed the decisions, record it in `judgment.draftedBy`, `judgment.draftedAt`
   and `judgment.verification` (see [Label drafting](#label-drafting-judgmentdraftedbycodex)).
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
