# Book corpus v1: explicit feedback A/B and an explicit new note (Book 8/8)

This experiment asks two narrow questions about the frozen `book-corpus-v1` Store (issue #170):

1. Does **explicit** ranking feedback, supplied by the host from oracle DEV judgments through the existing
   `MonadaMemory.feedback` API, change later recall? Does it survive a reopen, and does it leak into queries that
   received no feedback?
2. When the host adds a missing fact with `remember`, is it persisted and recoverable after a reopen, next to the old
   content?

It builds on the Store from #165 ([STORE-READBACK.md](STORE-READBACK.md)), the judged `book-queries-v1` from #166
([QUERIES.md](QUERIES.md)) and the recall baseline from #167 ([RECALL.md](RECALL.md)).

**Status: exploratory.** It is a replay of host-supplied feedback, not learning:
- the Store does not detect wrong answers, choose feedback, or add or correct facts by itself;
- feedback stays keyed by the exact query text, under the default `ExactQueryKeyStrategy`;
- nothing is promoted, and the protected baselines, `MonadaMemory`, the Store format and the defaults are unchanged.

Ranking feedback, factual additions and execution outcomes are kept apart:

| Mechanism | What it is here | What it is not |
| --- | --- | --- |
| Ranking feedback | `feedback(query, atomId, POSITIVE/NEGATIVE)`: for this exact query, rank this atom up or down by ±0.05. | A text change, a fact correction, or anything that applies to another query. |
| Explicit note | `remember(text)`: one new atom, added by the host, with provenance in an external sidecar. | A replacement, deletion or new version of an old chunk. Old content stays queryable. |
| Execution outcome | Not used or measured. | — |

| File | Purpose |
| --- | --- |
| [`book-feedback-v1-report.txt`](book-feedback-v1-report.txt) | Versioned, deterministic report: inputs, plan, metrics control → treatment, per-query changes, NO_ANSWER, contamination probes, the note, hard checks, full per-query trace, repeat-run verdict. |
| `monada-evaluation/src/main/java/com/monada/evaluation/book/BookFeedback*.java` | Plan, note, experiment, report and CLI. `BookRecallEvaluator` gained a package-private overload that measures a declared treatment Store. |

## Running it (offline, no network)

The command needs the verified normalized body (see [README.md](README.md), "Offline acquisition"). `--scratch` has
the same contract as in `runBookLatency`: it must not exist yet and must be outside any Git work tree. The run
creates it with a marker, writes every Store below it, and deletes it afterwards (pass `--keep yes` to keep it).

```bash
./gradlew :monada-evaluation:runBookFeedback -q --args="--input $HOME/monada-corpora/book-corpus-v1/body.txt --scratch $HOME/monada-corpora/book-feedback-scratch --out docs/experiments/book-corpus-v1/book-feedback-v1-report.txt"
```

Other options: `--baseline` (default `book-recall-v1-report.txt`, which the control arm must reproduce) and
`--manifest`. `--out` is written atomically. It may not name an input, lie inside `--scratch`, or replace any frozen
artifact in this directory or the `book-queries` resources, except this experiment's own report.

Exit codes:
- `0`: the report was written and every hard check passed, including the repeat run.
- `1`: the body is not the judged corpus, the feedback plan is invalid, or a hard check failed. On a check failure
  the report is still written.
- `2`: a usage error, a missing file, an unsafe `--scratch` or output path, or an I/O failure.

The opt-in test regenerates the report and requires it to be byte-identical to the committed file:

```bash
MONADA_BOOK_CORPUS_V1_BODY=$HOME/monada-corpora/book-corpus-v1/body.txt ./gradlew :monada-evaluation:test --tests '*BookFeedbackRealCorpus*'
```

## Design

### Three isolated arms

Every run seeds three Stores from the same verified chunk set (`book-chunking-v1`, 87 chunks), each in its own guarded
work directory and each with a `CLEAN` import and a passing readback:

| Arm | What happens | Measured how |
| --- | --- | --- |
| `control` | Nothing after seeding. | `BookRecallEvaluator` on throwaway copies. It must reproduce `book-recall-v1-report.txt` **byte for byte**, so it is the frozen baseline. |
| `treatment` | The predeclared plan is applied through `MonadaMemory.feedback` on one instance. | Ranked on that instance, then measured by the evaluator on fresh instances of a copy (after reopen). The two must agree for every query. |
| `note` | `remember(note)` on one instance. | Fresh read-only inventory and audit, then recall on a reopened copy. |

Control is never opened by `MonadaMemory` after seeding. Its Store digest must be unchanged after the treatment and
note phases. The whole experiment runs twice on new Stores (`run-a`, `run-b`), and both renderings must be identical.

### The feedback plan (predeclared)

Rule `book-feedback-v1-dev-oracle` is derived from the frozen `book-queries-v1` **DEV** judgments only. It reads no
retrieval output, no HOLDOUT case and no `book-queries-v2` label:
- `POSITIVE` for every judged `EVIDENCE` chunk (grade 2 or 1);
- `NEGATIVE` for every judged `CONFOUNDER` chunk (grade 0) whose atom is not also evidence of the case;
- one event per (query, atom), with the exact v1 query text and the default deltas (+0.05 / −0.05).

This gives 41 events (34 POSITIVE, 7 NEGATIVE) over 23 DEV cases; `NOA-05` has no judged chunk and gets none. The
report prints every event and the plan digest `4bc2e10d…babfb`.

Before anything is written, the plan is rejected when any event:
- comes from a HOLDOUT case;
- does not carry its case's exact text;
- has a query text equal to a HOLDOUT query or a probe;
- names an atom outside the chunk set;
- duplicates another event;
- has no matching judgment.

On the real Store, a deliberate `feedback` call with an unknown atom id must throw and leave the feedback log
byte-identical. The log is then parsed back and must equal the plan (query, query key, atom, signal, delta; the
wall-clock `createdAt` is ignored).

**Disclosure.** 7 feedback atoms are also judged by some HOLDOUT case. This is not leakage: feedback is keyed by the
DEV query text, and every HOLDOUT query is checked to rank exactly as in control.

### Contamination probes

A query that has no feedback of its own must rank exactly as in control: same atoms, same scores. Any difference is
counted as contamination. Three groups are checked, all hard checks:
- the 8 HOLDOUT cases;
- DEV cases without feedback (`NOA-05`);
- 46 confusable variants, two per DEV feedback case, fixed by rule before any run: `CASE_FOLDED` (the query lower-cased)
  and `REWORDED` (`"In the story, "` + the question).

The tests show that these checks are not vacuous. A sink that also records each event under the lower-cased query,
as a normalizing key would, fails `contamination.confusableProbesUnchanged`. A sink that records feedback under a
HOLDOUT query fails `contamination.holdoutUnchanged`.

### The explicit note

| Field | Value |
| --- | --- |
| `noteId` | `book-note-v1/authorship` |
| Text (original wording, no book text) | "Lewis Carroll was the pen name of Charles Lutwidge Dodgson, the Oxford mathematician who wrote Alice's Adventures in Wonderland, first published in 1865." |
| Source | <https://www.britannica.com/biography/Lewis-Carroll> |
| Verification | `VERIFIED` by Steven Rey Sequeira on 2026-10-23 against the source (recorded in `BookFeedbackNote`, the sidecar and the report). |
| Lifecycle | `APPEND_ONLY`: no replacement, deletion or version resolution; original chunks remain queryable. |

Before seeding, the note is checked against the corpus: its atom id must differ from every chunk's, and no chunk may
contain its text. `remember` returns the content-derived id `ea7cc7a2-…-2ce36394eda7`. The provenance goes to an
external sidecar line, because the Store keeps no atom metadata (ADR 0004). The line is read back and parsed.

After a reopen, fresh read-only instances must show:
- 88 atoms;
- all 87 original chunks exact;
- the note exact;
- a `HEALTHY` audit.

The #165 readback is a chunk-only contract, so it must report exactly one unexpected atom: the note. Two predeclared
probes ask for the note's facts. All 32 judged queries are rerun to show where the new atom intrudes.

## Results (book-corpus-v1, `book-chunking-v1`, `book-queries-v1`)

Run on 2026-10-23, OpenJDK 27, Linux (Fedora 44). Every hard check passed (17/17). Two complete runs in one process
rendered identical bytes, and a second, separate process reproduced the committed report byte for byte.

| Partition | n | P@1 | Hit@3 | Hit@10 | R@10 | MRR | NO_ANSWER false hits | Outcome |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DEV | 20 | 0.0500 → 0.1000 | 0.2500 → 0.5000 | 0.4500 → 0.6500 | 0.2708 → 0.5542 | 0.1705 → 0.3246 | 4/4 → 4/4 | BETTER |
| HOLDOUT | 7 | 0.1429 → 0.1429 | 0.2857 → 0.2857 | 0.4286 → 0.4286 | 0.3095 → 0.3095 | 0.2500 → 0.2500 | 1/1 → 1/1 | SAME |
| ALL | 27 | 0.0741 → 0.1111 | 0.2593 → 0.4444 | 0.4444 → 0.5926 | 0.2809 → 0.4907 | 0.1911 → 0.3052 | 5/5 → 5/5 | BETTER |

Per answerable DEV query:
- 11 improved: AMB-02, AMB-03, CON-03, CON-04, MUL-01, MUL-03, MUL-04, MUL-05, PAR-03, PAR-04, PAR-06;
- 9 unchanged: CON-01, LIT-01, LIT-03, LIT-04, LIT-05, NAR-01, NAR-02, NAR-04, PAR-05;
- **0 regressed.**

Contamination: 8/8 HOLDOUT, 1/1 DEV-without-feedback and 46/46 confusable variants rank exactly as in control.

Note: rank 1 for both note probes. It enters the top-10 of 10 of the 32 judged queries. Three of them are NO_ANSWER
(`NOA-01` #9, `NOA-03` HOLDOUT #9, `NOA-04` #3). Two are answerable queries where it is rank 1 (`AMB-03`, `NAR-04`).
The other 22 queries rank exactly as in control.

### Analysis

- **The DEV gain is label replay, not generalization.** Feedback was given for exactly these query texts, from their
  own oracle labels, so DEV improving is the expected effect of applying it. The test of generalization is everything
  else, and there the effect is zero:
  - HOLDOUT metrics are identical;
  - a lower-cased or lightly reworded DEV question ranks exactly as without feedback.

  Under exact keys, explicit feedback helps **only the query it was given for**.
- **±0.05 is often too small to matter.** 9 of 20 DEV queries did not change although every one received POSITIVE
  feedback on its evidence:
  - for some (LIT-01, LIT-03, LIT-05, NAR-02, NAR-04, PAR-05, CON-01), the evidence stays outside the top-10;
  - for others (LIT-04, NAR-01), it stays at rank 2.

  Rank-1 evidence went from 1 to 2 of 20 DEV queries. Nothing here tunes the delta: that would be tuning on the
  same DEV labels that are being replayed.
- **Feedback cannot produce abstention.** All NO_ANSWER queries still return 10 chunks. `NOA-04`'s judged confounder
  received NEGATIVE feedback; its score dropped from 0.362891 to 0.312891, and it is still rank 1.
- **Persistence holds.** The rankings computed on the instance that recorded the feedback equal the ones measured on
  fresh instances of a reopened copy for all 32 queries. The log is exactly the plan.
- **A new fact is recoverable and does not disturb old content**, but it competes with it:
  - the note is the top hit for its own probes;
  - every original chunk reads back exactly;
  - it also appears in 10 unrelated top-10s, including three NO_ANSWER queries. For those three, the false hits
    already existed, and now one of them is the note.

  Adding content is not free of side effects on ranking. The Store also has no notion that the note "answers" one
  question and not another.
- **Lifecycle limits are real.** `remember` only appends. If the note were wrong, there is no API to replace, retract
  or version it. A wrong note would stay as queryable as the original chunks.

### Caveats

- The results come from one book, one chunking policy, 20 DEV and 7 HOLDOUT answerable queries, one delta and one
  note. One query moves a DEV mean by 0.05.
- DEV labels are both the feedback source and the DEV yardstick, so the DEV numbers measure application of labels,
  not quality. HOLDOUT and the probes carry the evidence about transfer.
- The note was checked by one human reviewer against one cited source. The Store itself verifies nothing.
- Scores are uncalibrated cosine similarities plus summed deltas. The results depend on the host only through the
  JDK's floating-point arithmetic, which is deterministic for this code.

## Storage compatibility

No persisted format or ranking semantics change:
- the treatment uses the existing feedback log format;
- the note is an ordinary atom;
- old Stores are neither read differently nor rewritten.

`BookRecallEvaluator`'s default path still requires a feedback-free Store, and `book-recall-v1-report.txt` is
reproduced byte for byte. The new overload only accepts a Store whose feedback event count the caller declares.

## Non-goals respected

- No autonomous mistake detection, no self-chosen feedback, no fact added without the host, and no claim of learning.
- No text mutation, in-place replacement or deletion of old facts.
- No normalized query keys or other non-default options, no tuning on HOLDOUT, and no change to the protected baselines.
- No change to `monada-api`, `monada-learning`, `monada-index`, `monada-storage`, the Store format or `book-queries-v1/v2`.
- No book text in the report, the sidecar or Git.

## Follow-ups (not in this issue)

- Before any feedback automation (for example in Forge), decide in an Issue how feedback relates to query variants.
  Here it does not transfer at all. A normalized key would need the collision safeguards already measured by
  `runNormalizedQueryKeyStress`.
- Lifecycle API design: how a host would retract, supersede or version an explicitly added note.
