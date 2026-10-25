# Book corpus v1 — prospective query set `book-queries-v3` (Phase II 3/10)

Issue #186 (epic #183) fills the 40 slots of the signed [`book-queries-v3` protocol](PROSPECTIVE-V3.md) with new
questions and judged evidence. Status: **`PENDING_HUMAN_REVIEW`**. Every case is a model draft. Until a named human
reviewer reads each case against the body, writes a receipt and signs the manifest, no case is available for
evaluation. `BookQueryDatasetV3.devCases()` throws until then.

- No book text is committed. Evidence is a UTF-8 byte span, its SHA-256 and the containing `book-chunking-v1`
  chunk key, over the unchanged `book-queries-v1` `chunk-map.jsonl`.
- No production module, Store format, default, protected baseline, or v1/v2 file changes.

## Files

Dataset directory: `monada-evaluation/src/main/resources/com/monada/evaluation/book/book-queries-v3/`.

| File | Content |
| --- | --- |
| `book-queries-v3-manifest.properties` | Pins the exact protocol bytes (`protocol.sha256`), the plan, and each label file. Also records authoring, draft provenance, review status, final gate and counts. |
| `book-queries-v3-queries.psv` | 40 rows `slot\|split\|category\|answerable\|text\|rationale\|draftedBy`, in plan order. |
| `book-queries-v3-judgments.psv` | 60 rows in the v1 judgment schema: 48 `EVIDENCE` and 5 `CONFOUNDER` in answerable cases, plus 7 `CONFOUNDER` rows in `NO_ANSWER` cases. |
| `book-queries-v3-review.psv` | Header only. No receipt is written for the reviewer. |
| `.gitattributes` | `-text`, so the bytes stay as pinned. |

The protocol and plan stay in this directory. `BookQueryDatasetV3.load(protocolDir, datasetDir)` reads them from here,
and the manifest pins them by digest.

## Provenance

| Field | Value |
| --- | --- |
| Drafted by | `claude` (every row: `draftedBy=claude`) |
| Drafted / authoring started | 2026-10-24, no earlier than the protocol sign-off (`protocol.reviewedAt=2026-10-24`) |
| Method | `MANUAL_READING_OF_NORMALIZED_BODY_NO_RETRIEVAL`. The verified `body.txt` (SHA-256 `b44b4992…a944c`) was read with `grep`/`sed` only. Spans were located by exact quote in a scratch script outside the repository. |
| Retrieval runs before freeze | `0`. No Store, index, encoder, ranking, recall report or earlier hit list was opened. |
| Duplicates | Every text is new. The validator rejects any repeat of a v1 question or of another v3 question (ignoring case, punctuation and spacing). |
| Review | `review.status=PENDING_HUMAN_REVIEW`, `counts.approved=0`, no reviewer fields |
| Final gate | `finalGate.status=CLOSED` |

The drafter has seen the FINAL_ONLY texts, and so will the reviewer. That is inherent to authoring them. What
keeps them unused is the gate: no ranking or tuning run reads them before a declared final gate.

## Coverage

Categories, counts and splits are exactly the plan's: 4 `LITERAL`, 8 `PARAPHRASE`, 5 `NARRATIVE_TEMPORAL`,
5 `MULTI_EVIDENCE`, 5 `CONFOUNDER`, 5 `AMBIGUOUS` and 8 `NO_ANSWER`, split 28 DEV / 12 FINAL_ONLY.
- `MULTI_EVIDENCE`: 2 to 4 spans in non-adjacent chunks.
- `AMBIGUOUS`: 2 or 3 independently valid answers.
- `CONFOUNDER`: one grade-0 distractor chunk each.
- Chunks that are not listed are **unjudged**, not non-relevant.

### `NO_ANSWER` checks (proposed; the reviewer confirms)

Each premise was checked by reading the relevant scenes and by a case-insensitive scan of the full body. Where a
plausible near-miss exists, it is recorded as a grade-0 `CONFOUNDER`.

| Slot | Premise | Scan terms (no answer found) | Recorded near-miss |
| --- | --- | --- | --- |
| V3-NOA-01 | White Rabbit's first name | `Rabbit’s name`, `his name`, `named`, `Mr.` | brass plate with an initial only |
| V3-NOA-02 | Colour of the Cheshire Cat's fur | `fur`, `striped`, `tabby`, `colour`, `color` | the Cat on the hearth |
| V3-NOA-03 | Alice's home town or city | `London`, `Oxford`, `city`, `town`, `village`, `live` | London in her muddled geography |
| V3-NOA-04 | Name of the Duchess's cook | `cook`, `Cook` | the cook as a witness |
| V3-NOA-05 | The Caterpillar's instrument | `flute`, `fiddle`, `drum`, `piano`, `instrument`, `trumpet`, `harp` | the White Rabbit's trumpet |
| V3-NOA-06 | The March Hare's real name | `Hare’s name`, `Haigha` | none |
| V3-NOA-07 | Prize for winning the Queen's croquet | `prize` | Caucus-race prizes |
| V3-NOA-08 | Alice's family name | `Liddell`, `surname`, `Miss Alice`, `Alice <Capitalised>` | the mistaken name Mary Ann |

If the reviewer cannot confirm that a premise has no answer, the case is `UNRESOLVED`, never force-labelled.

## Human review (the reviewer, in a separate commit)

1. Read every case and its spans against `body.txt`. A local worksheet with the span text and each case's
   `contentSha256` can be generated outside Git (for example
   `~/monada-corpora/book-corpus-v1/book-queries-v3-review.md`). It holds book text, so it is **never committed**.
2. Append one row per case to `book-queries-v3-review.psv`: `slot|APPROVED|<contentSha256>|`, or `UNRESOLVED` /
   `REJECTED` with a note. `contentSha256` is the SHA-256 of the case's query row and its judgment rows, each
   LF-terminated, in file order (`BookQueryDatasetV3.contentDigest`):

   ```bash
   { grep '^V3-LIT-01|' book-queries-v3-queries.psv; grep '^V3-LIT-01|' book-queries-v3-judgments.psv; } | sha256sum
   ```

3. If a case is fixed, its digest changes and it must be read again. The queries and judgments are pinned in
   `BookQueryDatasetV3Test`, so a fix also updates that pin, and the PR must say why.
4. When all 40 are `APPROVED`, set in the manifest:

   ```properties
   review.status=REVIEWED
   review.reviewer=<human name>
   review.approvedBy=<human GitHub login>
   review.reviewedAt=<YYYY-MM-DD>
   ```

   Then update `review.sha256` and `counts.approved=40`. Neither person may be a drafter or a model. The final gate
   stays `CLOSED`.

## Running

```bash
./gradlew :monada-evaluation:test --tests '*BookQueryDatasetV3*' --tests '*BookQueriesFrozenResources*'
MONADA_BOOK_CORPUS_V1_BODY=$HOME/monada-corpora/book-corpus-v1/body.txt ./gradlew :monada-evaluation:test --tests '*BookQueryDatasetV3RealCorpus*'
```

- `BookQueryDatasetV3Test`:
  - loads the committed dataset, so all v1 per-category rules apply;
  - pins the label files;
  - checks the draft provenance and the review-status gates;
  - checks that span verification fails closed on a wrong body;
  - parses the dataset again in a fresh JVM and compares fingerprints.
- `BookQueryDatasetV3RealCorpusTest` (opt-in, no download) verifies the body against the corpus manifest and
  recomputes all 60 span digests. It reads no query text or label, so it runs before sign-off.

## Non-goals respected

- No model, Store or retrieval run, and no question chosen from scored output.
- No v1 query text reused, and no v1/v2 judgment relabelled. All frozen files are byte-identical
  (`BookQueriesFrozenResourcesTest`).
- No human approval is claimed. The dataset stays `PENDING_HUMAN_REVIEW` and blocks official scoring.
