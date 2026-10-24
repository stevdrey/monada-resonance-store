# Book corpus v1 — prospective query protocol `book-queries-v3` (Phase II 2/10)

Issue #185 (epic #183) fixes how brand-new book queries will be written, judged, reviewed and split. All of this is
decided **before** any v3 query exists and before anyone looks at a v3 ranking. This step ships the protocol only:
- no query text, answer, evidence span, gold decision, score or book byte is committed;
- the queries themselves are Phase II 3/10;
- no production module, Store format, persisted vector, default or protected baseline changes.

Validation (main sources): `BookQueryProtocolV3` and `BookQueryDatasetV3` in
`monada-evaluation/src/main/java/com/monada/evaluation/book/`. Tests: `BookQueryProtocolV3Test`,
`BookQueryDatasetV3RejectionTest` and `BookQueriesFrozenResourcesTest`.

## Why a new, prospective set

| Set | Why it cannot be pristine selection evidence |
| --- | --- |
| `book-queries-v1` | Its HOLDOUT metrics are already published ([RECALL.md](RECALL.md)) and were read while later work was planned. It stays valid as reported history, but it is not fresh, independent evidence. |
| `book-queries-v2` | Its added pairs were sampled from a published run of the evaluated system (`provenance=POST_HOC_RETRIEVAL_SAMPLED_DEV`, [UNJUDGED-REVIEW.md](UNJUDGED-REVIEW.md)). |

v3 is authored prospectively and score-blind. Its FINAL_ONLY part is never evaluated automatically until a separate,
declared final gate opens it.

## Files

| File | Content |
| --- | --- |
| `book-queries-v3-protocol.properties` | Protocol receipt: identity, the `book-corpus-v1` binding, plan and split digests, fixed counts and minimums, authoring rule, final-gate policy, draft provenance and sign-off status. |
| `book-queries-v3-plan.psv` | `slot\|category\|split`: the 40 precommitted slots. No text. |

The corpus and chunk binding (`corpusManifest.sha256`, `source.*`, `chunking.policy`, `chunkMap.sha256`,
`chunksFile.sha256`) must equal the frozen `book-queries-v1` manifest. v3 judgments therefore use the unchanged v1
`chunk-map.jsonl`; nothing is copied. Both files are pinned byte for byte (`.gitattributes`: `-text`).

## Precommitted plan

Fixed by issue #185: **40 slots, 28 DEV and 12 FINAL_ONLY**. Minimums: at least 8 `NO_ANSWER`, at least 8
`PARAPHRASE`, and at least 8 narrative, multi-evidence or distractor cases (`NARRATIVE_TEMPORAL` + `MULTI_EVIDENCE` +
`CONFOUNDER`). The validator hard-codes these values, so a protocol cannot declare lower ones.

| Category | Slots | DEV | FINAL_ONLY |
| --- | --- | --- | --- |
| `LITERAL` | 4 | 3 | 1 |
| `PARAPHRASE` | 8 | 6 | 2 |
| `NARRATIVE_TEMPORAL` | 5 | 3 | 2 |
| `MULTI_EVIDENCE` | 5 | 3 | 2 |
| `CONFOUNDER` | 5 | 3 | 2 |
| `AMBIGUOUS` | 5 | 4 | 1 |
| `NO_ANSWER` | 8 | 6 | 2 |
| **Total** | **40** | **28** | **12** |

Narrative, multi-evidence and distractor slots total 15. Category meanings and per-category label rules are those
of v1 ([QUERIES.md](QUERIES.md#labels)).

Slot ids are `V3-<PREFIX>-<nn>`, using the v1 prefixes (`LIT`, `PAR`, `NAR`, `MUL`, `CON`, `AMB`, `NOA`).

## Split: seed and explicit assignment

The plan states every assignment explicitly. The validator also recomputes it from the predeclared rule
`per-category-sha256-rank-predeclared-final-only-v1`:
1. Per category, rank slot ids by `SHA-256("book-queries-v3|" + slot)`.
2. The last `counts.<CATEGORY>.FINAL_ONLY` slots are `FINAL_ONLY`; the rest are `DEV`.

`split.digest` is the SHA-256 of the sorted `slot:SPLIT` lines. A hand-edited split, a changed salt or a changed
digest fails loading.

The assignment depends only on slot ids, never on query text, answers or system output. So a split cannot be chosen
after seeing whether a query is easy for the current encoder.

| Split | Use |
| --- | --- |
| `DEV` | Inspect, tune and debug freely, once the dataset is human-reviewed. |
| `FINAL_ONLY` | No automated evaluation, tuning or inspection of system output until the final gate. `BookQueryDatasetV3.finalOnlyCases()` throws unless the dataset is `REVIEWED` and `finalGate.status=OPEN`. |

## Authoring (Phase II 3/10): score-blind

1. Each slot is filled from the verified normalized body (`body.txt`) read with a text viewer. No Store, index,
   encoder, ranking, recall report or previous hit list is consulted (`authoring.protocol` is the v1
   `MANUAL_READING_OF_NORMALIZED_BODY_NO_RETRIEVAL`; `authoring.retrievalRunsBeforeFreeze=0`).
2. A query fills exactly the category and split of its slot. It must not repeat a v1 question (comparison ignores case,
   punctuation and spacing) or another v3 question.
3. Evidence is anchored by UTF-8 byte span, span SHA-256 and the containing `book-chunking-v1` chunk key, as in v1.
   The opt-in real-body test recomputes every span digest.
4. **Unknown is explicit.**
   - A chunk that is not listed is *unjudged*, never implicitly non-relevant, and reports must show it as such.
   - When a reviewer cannot confirm an answer, or confirm that a `NO_ANSWER` case has none, they record the case as
     `UNRESOLVED`. `UNRESOLVED` blocks sign-off; nobody guesses a label.
5. **Draft provenance.**
   - Every query row names `draftedBy`: `NONE` when the human author wrote it directly, otherwise the drafter (for
     example a model).
   - Drafting by a model is allowed only as a proposal: the dataset then needs
     `draft.verification=EACH_DRAFTED_CASE_READ_AGAINST_BODY_AND_APPROVED_BY_REVIEWER` and `draft.draftedAt`.
   - A model never authors gold labels silently.

### Future dataset files (schema; not committed by this issue)

| File | Columns / keys |
| --- | --- |
| `book-queries-v3-manifest.properties` | `dataset=book-queries-v3`, `format=monada-book-queries-prospective-dataset`, `protocol.sha256`, `plan.sha256`, file names and SHA-256 values, `authoring.*`, `judgment.grades` (v1 grades), `review.status`, `review.reviewer`, `review.approvedBy`, `review.reviewedAt`, optional `draft.*`, `finalGate.*`, `counts.queries`, `counts.judgments`, `counts.approved`. |
| `book-queries-v3-queries.psv` | `slot\|split\|category\|answerable\|text\|rationale\|draftedBy` |
| `book-queries-v3-judgments.psv` | `queryId\|chunkKey\|byteStart\|byteEnd\|spanSha256\|grade\|role\|note` (same as v1) |
| `book-queries-v3-review.psv` | `slot\|decision\|contentSha256\|note`; `decision` is `APPROVED`, `UNRESOLVED` or `REJECTED`. |

No key or column may look like retrieval output (`score`, `rank`, `hit`, `recall`, `mrr`, `ndcg`, `precision`).
Unknown columns fail loading.

## Human review and receipts

1. The reviewer reads every case, with its spans, against the body and writes one receipt row per case.
   `contentSha256` is the SHA-256 of the case's query row and its judgment rows (file order, each LF-terminated);
   this is exactly what was reviewed. Editing a case after review invalidates its receipt.
2. `UNRESOLVED` and `REJECTED` receipts need a note. A rejected or unresolved case is replaced or fixed and reviewed
   again; it is never approved silently.
3. `review.status` stays `PENDING_HUMAN_REVIEW`, and **no** case is available for evaluation (`devCases()` throws),
   until all of the following hold:
   - the protocol itself is signed;
   - all 40 slots have a query with an `APPROVED` receipt;
   - `review.reviewer`, `review.approvedBy` and an ISO `review.reviewedAt` are present, and neither person is a drafter
     or a model (names such as `claude`, `codex`, `GPT-5` or `review bot` are rejected);
   - the dates are ordered: protocol signed ≤ `authoring.startedAt` ≤ `review.reviewedAt`.

Only `REVIEWED` produces judgments. A model-only draft can never be presented as reviewed.

### Signing this protocol

Claude drafted this protocol (`protocol.draftedBy=claude`). It stays `protocol.status=PENDING_HUMAN_REVIEW` until the
human reviewer, in a separate commit during PR review, sets:

```properties
protocol.status=REVIEWED
protocol.reviewer=<human name>
protocol.approvedBy=<human GitHub login>
protocol.reviewedAt=<YYYY-MM-DD>
```

The plan file and its digests do not change when the protocol is signed.

## Final gate

`finalGate.policy=separate-declared-final-gate-v1`, `finalGate.initialStatus=CLOSED`. The gate opens only in the
dataset manifest, through a separate, declared step:
- `finalGate.status=OPEN`;
- `finalGate.issue=#<n>`, the issue that declares the final evaluation;
- `finalGate.candidate`, the frozen system candidate under test;
- `finalGate.declaredAt` no earlier than `review.reviewedAt`.

An unreviewed dataset cannot open it. After FINAL_ONLY results are seen, the v3 FINAL_ONLY cases count as exposed,
like v1 HOLDOUT. Any later selection needs a new prospective set.

## Change control

- Once signed, the protocol and the plan are immutable. Any change (counts, categories, split rule, schema, gate)
  is a new protocol version or a new dataset (`book-queries-v4`), with its own digests. That version states whether
  its results remain comparable with v3.
- Labels are never edited after seeing results. A wrong label is recorded as a finding and fixed only in a new
  version, as v1 → v2 did.
- `book-corpus-v1`, `book-queries-v1`, `book-queries-v2` and every committed report stay byte-identical;
  `BookQueriesFrozenResourcesTest` pins their SHA-256 values.

## Running

```bash
./gradlew :monada-evaluation:test --tests '*BookQueryProtocolV3*' --tests '*BookQueryDatasetV3*' --tests '*BookQueriesFrozenResources*'
```

Opt-in real-body validation does not apply yet: this issue commits no spans. Phase II 3/10 adds a real-corpus test
that recomputes every v3 span digest against `MONADA_BOOK_CORPUS_V1_BODY`, as `BookQueryDatasetRealCorpusTest` does
for v1.

## Non-goals respected

- No query, answer, span or human judgment is authored or asserted here (Phase II 3/10).
- No ranked hit, score or report output was used to choose categories, counts, slots or splits.
- No change to v1/v2 schemas or files, the Store API, production relevance or benchmarking defaults.
