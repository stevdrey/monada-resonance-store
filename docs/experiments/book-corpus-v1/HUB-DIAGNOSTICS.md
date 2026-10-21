# Book corpus v1: hub-concentration diagnostics (evaluation only)

[RECALL.md](RECALL.md) (#167) observed that chunk `s009/c0001` ranks first for 6 of the 32 `book-queries-v1`
queries, two of them NO_ANSWER. Issue #176 measures that concentration and the properties that might go with it,
without changing anything that ranks.

**Status: diagnostic.**
- Nothing here is an optimization.
- No encoder, index, lexical resource, chunking policy or default changed.
- Scores are uncalibrated similarities, not relevance.
- Correlations below are not causes.
- HOLDOUT queries are counted, but never used to choose controls, features or a next step.

| File | Purpose |
| --- | --- |
| [`book-hub-diagnostics-v1-report.txt`](book-hub-diagnostics-v1-report.txt) | Versioned report: parity, concentration by DEV/HOLDOUT/ALL, top-1/3/10 frequency table, per-query references, predeclared controls, chunk metrics, per-bucket score decomposition, what was not measured. |
| `monada-evaluation/src/main/java/com/monada/evaluation/book/BookHub*.java` | Counting, probe, report renderer, CLI. |

## Running it (offline, no network)

The work directory is the one measured by `runBookRecall` (see [STORE-READBACK.md](STORE-READBACK.md)):

```bash
./gradlew :monada-evaluation:runBookHubDiagnostics --args="--work $HOME/monada-corpora/book-store-readback-v1 --baseline docs/experiments/book-corpus-v1/book-recall-v1-report.txt --out docs/experiments/book-corpus-v1/book-hub-diagnostics-v1-report.txt"
```

Opt-in check that a fresh run reproduces the hub (6/32, NO_ANSWER `NOA-01` and `NOA-02`) and the committed
report byte for byte:

```bash
MONADA_BOOK_STORE_READBACK_V1_WORK=$HOME/monada-corpora/book-store-readback-v1 ./gradlew :monada-evaluation:test --tests '*BookHubDiagnosticsRealCorpusTest'
```

Exit codes:
- `0`: report produced.
- `1`: one of these failures:
  - the inputs are not the judged corpus;
  - the recall result no longer reproduces `--baseline` byte for byte ("pinned input changed");
  - the chunk files or the Store changed between the evaluation and the probe;
  - score parity failed;
  - reading the inputs failed unexpectedly ("diagnostic failed").
- `2`: usage error (including `--out` inside the work directory or naming the `--baseline` file through any
  alias), missing file, unsafe work directory or I/O failure.

The committed report was produced on OpenJDK 27 (Zulu 27.28+101, build 27+35).

## How the evidence is bound

1. **Frozen input.** The probe re-runs the existing `BookRecallEvaluator` and requires
   `BookRecallReport.render(result)` to equal the committed
   [`book-recall-v1-report.txt`](book-recall-v1-report.txt) byte for byte (SHA-256 `86501f76…a2ffd2`). The
   evaluator still enforces everything it already enforces:
   - corpus, chunk-file and dataset hashes;
   - zero feedback;
   - readback;
   - two identical runs on a throwaway Store copy.

   The user's Store is never written. Its tree digest is taken before the evaluation and compared again after
   the probe has copied the vectors. The chunk files the probe reads must carry the same hashes as the evaluated
   ones, so every number is bound to the baseline's inputs.
2. **Parity.** The probe reads the stored vectors from a throwaway copy and scores every query against all 87 atoms
   with a verbatim copy of the production cosine. Before anything else is reported, the recomputed top-10 must equal
   production exactly: the same atoms, the same order including atom-id ties, and bit-identical scores (tolerance
   `0.0`). Result: 320/320 hits identical.

   Re-encoding each chunk's text with the production encoder also gives its stored vector exactly (87/87). The
   recomputation is only used to explain scores; production remains the ranking truth.
3. **Controls, fixed before reading scores.** These rules use DEV only:

   | Control | Rule | Chunk |
   | --- | --- | --- |
   | HUB | Most DEV top-1 appearances (at least one, else absent) | `s009/c0001` |
   | RUNNER_UP | Next chunk in the same DEV order | `s009/c0006` |
   | TOP1_EVIDENCE | Judged evidence ranked #1 for a DEV query | `s004/c0004` (CON-04) |
   | JUDGED_EVIDENCE | Grade-2 evidence judged for the most DEV queries | `s006/c0005` |

## Observations

All numbers come from [`book-hub-diagnostics-v1-report.txt`](book-hub-diagnostics-v1-report.txt).

- **Concentration.** `s009/c0001` is top-1 for 6/32 queries (ALL), 6/24 on DEV and 0/8 on HOLDOUT. The six
  queries are AMB-02, AMB-03, LIT-03, LIT-05, NOA-01 and NOA-02, all labelled `U`.
  - The chunk appears in 18/32 top-10 lists.
  - The next most frequent top-1 chunks reach 2/32.
  - HOLDOUT has no single most frequent top-1: its 8 top-1 positions go to 8 different atoms.
  - The 32 top-1 positions are spread over 23 distinct atoms.
- **Length is not extreme.** The hub ranks #25 of 87 atoms by UTF-8 bytes, #26 by original tokens and #40 by
  distinct tokens. Two evidence controls of similar or larger length rarely rank first:
  - `s004/c0004` ranks #2 by original tokens.
  - `s006/c0005` ranks #3 by original tokens.
- **Norm cannot explain it.** Every stored vector is L2-normalized (norm range 0.999999942–1.000000067 in the report).
- **Lexical enrichment adds nothing to chunks.** No chunk has an expansion token (87/87 have 0), and no query
  expansion token appears among the listed top contributing buckets.
- **Not the most central vector.** The hub's cosine with the mean stored vector ranks #26 of 87. The runner-up and
  the judged-evidence control are more central (#14 and #13).
- **One bucket carries most of the hub's score.** In all six hub top-1 queries, bucket 73 is the largest
  contribution: from 0.234 of 0.295 (AMB-02) to 0.427 of 0.427 (NOA-02).
  - Two different normalized tokens, `alice` and `is`, hash to bucket 73.
  - The hub contains 19 occurrences of these two tokens and no other token in that bucket (`m=19 o=0`).
  - The controls contain 5–6 of them (`m=5`/`m=6`).
  - Among the six, AMB-03 reaches that bucket through `is` alone.

  Outside the six, NOA-04 (hub rank 3) also reaches bucket 73 through `is` alone.
- **Function-like tokens survive normalization.** Tokens such as `is`, `what`, `s`, `it`, `at` and `that` appear as
  query tokens and as top contributing buckets, so the stop-word list does not remove them.
- **The hub's DEV profile.** Median rank 5.5 and mean score 0.228 over the 24 DEV queries, against 15.0 and 0.189
  for the runner-up. For queries on unrelated topics (PAR-03..06, MUL-04) it sits at ranks 34–78.

## Plausible hypotheses (not verified)

- **H1 (bucket collision).** With 128 hashed dimensions, `alice` (the protagonist's name, present in many queries)
  and `is` share one signed bucket. A chunk dense in both gets a large component there, and a large cosine with any
  query that contains either token.
- **H2 (function tokens).** Tokens that the stop-word list keeps (`is`, `what`, `it`, `s`) add query-independent
  overlap that favours chunks dense in them.
- **H3 (bucket-level only).** Collisions in other buckets (`o > 0`) mean a bucket contribution cannot be split
  faithfully between tokens. H1 and H2 are inferred from bucket membership and counts, not from a per-token
  attribution.

## Unverified causes and what was not measured

- No intervention was run. Whether changing the dimensionality, stop words or anything else would move the hub off
  rank 1 is **unknown**.
- Per-token shares of a bucket are **not measured** (see H3). Corpus-level token weighting (IDF) does not exist in
  the production encoder, so there is nothing to inspect.
- The `U` hub hits are **not adjudicated**. A `U` label is not evidence of irrelevance; human adjudication of
  high-impact `U` hits remains a separate follow-up.
- HOLDOUT score decompositions were **not computed**, by design.

## Stop / go for a future ranking experiment

- **Go:** a separate Issue may run a DEV-only A/B on a throwaway rebuilt Store, with candidate behaviour behind an
  explicit option. Candidates suggested by H1/H2: more hash dimensions, or a stop-word variant for `is`-like
  tokens. Each candidate must report:
  - baseline vs candidate top-K for every DEV query;
  - the protected metrics;
  - this hub's top-1 count.

  HOLDOUT is touched once, only after a candidate is frozen.
- **Stop:** nothing in this diagnostic justifies a production default change. No intervention was tested, and
  persisted vector semantics would change (a dimension or stop-word change requires a rebuild; see the storage
  compatibility rules in `AGENTS.md`).

## Non-goals respected

- No change to `LinearScanResonanceIndex`, `FrequencyEncoder`, lexical resources, `MonadaMemory`, thresholds,
  query-key strategies, chunking or defaults.
- The following are unchanged:
  - `book-corpus-v1`;
  - `book-queries-v1`;
  - the 24 DEV / 8 HOLDOUT split;
  - `book-recall-v1-report.txt`.
- No book text is committed. The report names only chunk keys, counts, scores and tokens of the committed query
  text.
