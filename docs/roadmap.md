# Roadmap

## Roadmap Principle

The project follows a measurement-first path: improve retrieval quality only after the evaluation harness exposes a specific gap.

## Established Foundations

- Core domain records and vectors.
- Local file-based storage.
- Deterministic text encoding and lexical enrichment.
- Deterministic resonance ranking.
- Feedback-aware ranking with query-key strategies.
- Protected baseline evaluation.
- Versioned text evaluation snapshots with explicit protected/exploratory report labels and
  deterministic regression gates.
- Expanded exploratory evaluation.
- A/B profile comparison.
- Evaluation diagnostics.
- Controlled persisted-feedback query-key comparison with explicit transfer and contamination cases.
- Real-world project-memory normalized feedback validation with eight positive transfers, eight
  confusable negative cases, and explicit false-sharing evidence.
- Adversarial normalized feedback-key stress over 24 independent pairs, with all eight expected
  invariances preserved but seven semantically distinct collisions across coordination,
  relation/preposition, and fallback-boundary cases. The evidence signal is
  `NORMALIZED_STRESS_RISK`, so exact-query matching remains the only conservative supported behavior.
- Speech sample storage, TORGO-style import, acoustic feature encoding, and acoustic retrieval.
- Speech retrieval evaluation metrics (Precision@k, Recall@k, Hit rate@k, MRR).
- Protected speech benchmark: a CI-enforced generated-fixture baseline plus a non-enforced
  exploratory local real-data mode, with mode-labeled deterministic reports.
- Paired transcript-only versus acoustic-only speech evaluation over the same generated queries,
  with stable sample-ID alignment and complementary evidence for a controlled fusion experiment.
- Evaluation-only transcript/acoustic score-fusion sweep with exact control arms, a fixed five-point
  global weight sweep, per-query min-max normalization, explicit missing-modality handling, and no
  production ranker. The generated fixture has a stable promising region across all three non-trivial
  profiles and concludes `HYBRID_ROBUSTNESS_STUDY_JUSTIFIED`; this is evidence only for a deeper
  robustness/protected-benchmark phase.
- Fixed-profile hybrid robustness study over deterministic control/dysarthric, task, speaker-relation,
  direct-conflict, and missing-modality cases. The frozen `0.50/0.50` candidate preserves direct
  conflict recoveries but regresses when a relevant candidate lacks one modality, so the evidence
  concludes `HYBRID_CANDIDATE_RISKY`. No protected hybrid baseline, production retrieval path, or
  default behavior is justified from this result.
- Storage compatibility hardening: manifest `0.4` now separates encoding metadata from an explicit
  physical vector profile (`FLOAT32`, big-endian, framing, dimensions, and index version). Current
  and supported legacy layouts are validated before opening, while incompatible stores are rejected
  without automatic migration or byte reinterpretation.
- Storage integrity audit: Phase W introduces `StorageIntegrityAuditor` and CLI `./gradlew :monada-storage:runIntegrityAudit`,
  providing non-destructive, side-effect-free validation across manifests, atom append logs, vector frames,
  indexes, and feedback logs with a deterministic corruption taxonomy and statistics.
- Scaled linear-scan benchmark and bounded top-K decision gate: Phase V introduces `DeterministicScaleCorpusGenerator`,
  `LatencyScaleSweepRunner`, and CLI `./gradlew :monada-evaluation:runLatencyScaleSweep -q`. Evaluating 100, 1,000, 5,000,
  and 10,000 corpus sizes with a fixed 128-dimensional encoder reports a structural scan fraction of 1.0 across both K=1 and K=5,
  flat encoding duration (~30-120 us), and end-to-end recall growth (~21 ms at N=100 up to ~2.17 s at N=10,000). The baseline
  combines an O(N) candidate scoring scan with complete O(M log M) result sorting, where M <= N; the end-to-end timing does not isolate either
  operation (see the phase breakdown below). The evidence concludes
  `BOUNDED_EXACT_TOP_K_EXPERIMENT_JUSTIFIED`, justifying a follow-up exact bounded top-K optimization experiment without
  changing production scan algorithms or introducing approximate indexing.
- Linear-scan phase breakdown (Issue #119): `ScanPhaseBreakdownRunner` and CLI `./gradlew :monada-evaluation:runScanPhaseBreakdown -q`
  time an evaluation-only instrumented equivalent of the linear scan (parity with `LinearScanResonanceIndex` verified per query).
  At N=10,000 the vector load is ~98.8-98.9% of query time, cosine scoring ~0.5-0.7%, dedup ~0.1%, full sort ~0.3% for K=1 and K=5
  (single-machine, exploratory). The earlier `BOUNDED_EXACT_TOP_K_EXPERIMENT_JUSTIFIED` signal is therefore not supported by phase-level
  evidence: an exact bounded top-K prototype is not justified; the next performance step should target vector loading/materialization
  (for example avoiding a full vector re-read per search) behind an explicit option with its own storage-compatibility analysis.
- Vector-load breakdown and bulk frame read (Issue #130): `VectorLoadBreakdownRunner` and CLI
  `./gradlew :monada-evaluation:runVectorLoadBreakdown -q` split `FileFrequencyStore.findAll()` into index parse, seek, per-float read,
  bulk read, float decode and materialization (parity with `findAll()` verified bit-for-bit). At N=10,000 the previous path made
  1,280,000 Java-level `RandomAccessFile.readFloat` calls per load (API calls, not an exact syscall count), ~99% of the ~0.92 s p50 load (warm page cache, single machine, exploratory), so the
  report concludes `PER_FLOAT_READ_DOMINANT_BULK_READ_JUSTIFIED`. `FileFrequencyStore` now reads each frame's values in bulk chunks
  of up to 16 KiB (one `readFully` call per 128-dimension vector) with big-endian decode, with no format, metadata, result, ordering or error-message change (existing stores reused; bit-identical
  parity tests cover fixed-raw, length-prefixed, undeclared-dimension and corrupt stores). Measured `findAll()` p50 at N=10,000 fell
  from ~0.92 s to ~16 ms and linear-scan `search()` p50 from ~0.95 s to ~24 ms; vector load is now ~63-64% of query time, scoring ~18%,
  full sort ~12% (still below the bounded top-K threshold). No cross-search vector cache was added; retaining vectors across searches needs its own invalidation and consistency analysis.
- Speech feature store bulk read (Issue #132): `FileSpeechFeatureStore` (which `SpeechSampleRetriever` loads in full on every query) now
  reads each vector's values in the same 16 KiB chunks as `FileFrequencyStore` instead of one `readFloat` call per value. Format,
  dimension-header validation, error messages, last-wins ordering and results are unchanged (existing stores reused); a parity test
  against a verbatim copy of the previous per-float path covers raw bit patterns, multi-chunk and mixed dimensions, truncation and
  bad offsets/headers. Generated speech evaluation output is byte-identical. Measured `findAll()` p50 at N=10,000 x 64 dims fell from
  ~462 ms to ~22 ms (warm page cache, single machine, exploratory).
- Shared chunked float reader (Issue #134): the two hand-synced `FrameBuffer` copies from #130 and #132 are replaced by one
  `com.monada.core.io.ChunkedFloatReader` used by `FileFrequencyStore` and `FileSpeechFeatureStore` (vector-sized scratch, at most
  16 KiB, for both). It lives in `monada-core` under a narrow ADR 0001 amendment because `monada-speech` must not depend on
  `monada-storage`; the package is exported but not a supported consumer API (see `docs/specs/library-consumption.md`). Both parity
  tests pass unchanged, generated speech evaluation output is byte-identical and `runVectorLoadBreakdown` differs only in timing.
- Vector copy share and no-copy store read (Issue #135): `VectorCopyShareRunner` and CLI
  `./gradlew :monada-evaluation:runVectorCopyShare -q` time both stores' `findAll()` against two parity-checked replicas that differ
  only in `FrequencyVector`'s defensive copy, and read per-call allocated bytes from `ThreadMXBean`. Shares are relative to the copying replica
  (the pre-#135 path), so they stay reproducible after the change. At N=10,000 the copy was 37.4% of the copying
  `FileFrequencyStore.findAll()` allocation (5,280,000 bytes, exactly N x 528) and 29.7% for `FileSpeechFeatureStore` (N x 272),
  but only ~6% and ~1% of p50 (noisy). That meets the allocation threshold of the decision rule (>= 10% of both p50 and p95, or >= 30% of bytes in
  at least one store; inconclusive when allocation is not measured and no time share qualifies), so the report concludes `COPY_SHARE_MEANINGFUL_REMOVE_COPY`. `FrequencyVector` is now a final class with the
  same public API plus a store-only `FrequencyVector.read(ChunkedFloatReader, DataInput, int)` that decodes into its own array
  without a copy ([ADR 0005](adr/0005-frequency-vector-final-class.md)). Both stores use it. Production `findAll()` allocation at
  N=10,000 fell from 14.10 MB to 8.82 MB (frequency) and from 9.21 MB to 6.49 MB (speech). Formats, results, ordering and error
  messages are unchanged (existing stores reused; both read-parity tests pass unchanged). Latency gains are within run-to-run noise
  (single machine, exploratory).
- Speech feature store windowed read (Issue #136): `SpeechFeatureLoadBreakdownRunner` and CLI
  `./gradlew :monada-evaluation:runSpeechFeatureLoadBreakdown -q` split `FileSpeechFeatureStore.findAll()` into index parse, seek,
  header read, value read, per-vector I/O and materialization at N=100/1,000/5,000/10,000 for 64 and 128 dimensions. They time a
  replica of the pre-#136 per-vector path and a windowed candidate, both parity-checked against `findAll()`. At N=10,000 the replica made 10,000 seeks
  and 20,000 `RandomAccessFile` reads per load; per-vector I/O was ~82-84% of its ~23 ms p50, index parse ~5% and
  materialization ~4-8%. The candidate needed ~14-16% of the replica p50 with 3-5 window reads. Decision rule: per-vector I/O >= 40% of the replica
  p50 and candidate <= 75% of the replica at p50 and p95 for every dimension at N >= 10,000. The report concludes
  `PER_VECTOR_IO_DOMINANT_WINDOWED_READ_JUSTIFIED`. `findAll()` now reads the segment through one reused positional-read
  window of at most 1 MiB (the segment size when smaller, with a 4-byte floor for one header) and decodes frames at their
  indexed offsets. The window only moves forward: contiguous refills keep the unread tail, and a forward jump past the window
  reads at most 8 KiB or the frame. Entries
  whose offset is before the window (out-of-order indexes) and frames larger than the window use the per-vector path. Entries
  are still validated and read in index order, so results, ordering, error messages and error precedence are unchanged.
  `FileSpeechFeatureStoreReadParityTest` passes unchanged and adds shuffled and overlapping offsets, segments and frames larger
  than the window, frames at the window/fallback size boundary, corrupt frames reached by a mid-segment refill, and
  multi-error precedence. The windowed candidate in the report stays a frozen snapshot of the stage-1 proposal. Production `findAll()` p50 at N=10,000 fell from ~22.3 ms to ~3.0 ms (64 dims) and
  from ~22.8 ms to ~3.7 ms (128 dims) (warm page cache, single machine, exploratory). Format, index, API and defaults are unchanged,
  and generated speech evaluation output is byte-identical. Not done here: cross-query caching. Dedup of superseded entries: Issue #137.
- Structural I/O checks for the windowed speech read (follow-up to Issue #136): a package-private `findAll(ReadStats)` counts
  window refills, channel reads, bytes read and reread through the window (reread = at or below the furthest offset already
  read), and per-vector fallbacks with the bytes they read (file-pointer advance; it catches overreads, not call counts). Public
  `findAll()` passes a no-op instance, so API, results, ordering and errors are unchanged and production makes no extra calls.
  `FileSpeechFeatureStoreReadStructureTest` asserts, without timing: an append-ordered segment is read exactly once with refills
  bounded by the window and gap sizes; nearly sorted and fully shuffled indexes reread no window byte; a sparse index reads at
  most one 8 KiB gap read per entry; an exactly window-sized frame is decoded from the window, while one 4 bytes larger uses one
  fallback that reads exactly its frame; a reversed index of N entries uses N-1 fallbacks; and counters are deterministic
  across runs and untouched by the public path. Two efficiency-only mutants that the parity test cannot see (dropping the tail
  carry-over; sending an exactly window-sized frame to the fallback) now fail. Test segments are written by a shared
  `SpeechFeatureSegmentFixture`. Speech evaluation output is byte-identical.
- Superseded speech feature entries (Issue #137): decision **(b)**, `FileSpeechFeatureStore.findAll()` skips index entries
  superseded by a later entry for the same sample ID, like `findBySampleId()` and `validateStoredVectorDimensions()` already did.
  Results stay in the first-seen order of each sample ID with the vector from its last entry, and active entries are still validated
  and read in index order. Valid stores therefore return identical, bit-identical results, and a corrupt active entry fails with the
  same exception and message. Error-behavior change: a corrupt *superseded* entry (bad offset, invalid header, truncated frame) no
  longer fails `findAll()`. Malformed index lines still fail. Measured with a scratch probe (not committed) at N=10,000 x 64 dims,
  k full re-encode rounds in append order: window bytes read went from 2.6/5.2/10.4 MB to 2.6 MB for k=1/2/4. Timing, with
  baseline and candidate JVMs alternated over 3 rounds (warm page cache, single machine, exploratory): k=1 within noise
  (~6-12 ms both), k=4 p50 ~11-21 ms -> ~9-13 ms. Index parsing still grows with k and now dominates the remaining cost.
  Storage compatibility: no new metadata; existing stores are read unchanged and are more lenient only toward corrupt superseded
  entries; vectors stay comparable (bit-identical); path: reuse. Proof: `FileSpeechFeatureStoreReadParityTest` keeps its verbatim
  oracle and adds a documented exception, where superseded-corruption cases are compared with the oracle on a de-duplicated index.
  `FileSpeechFeatureStoreReadStructureTest` asserts that superseded frames are never read, using a new `ReadStats` counter of
  superseded entries skipped (no-op on the public path). `findAll()` and `validateStoredVectorDimensions()` share one active-entry
  helper. Generated speech evaluation output is byte-identical. Not done here: index compaction and a user-facing diagnostic for
  superseded-entry corruption.
- Exact bounded top-K experiment (Issue #120): experimental `ExactBoundedTopKSelector` (bounded min-heap over the deduplicated
  candidates, production comparator score desc / atom id asc) and `ExperimentalBoundedTopKLinearScanIndex` (same scan, threshold and
  keep-max dedup, selector instead of sort+truncate) live in the unstable `com.monada.index.experimental` package and are never
  selected by default; `LinearScanResonanceIndex` is unchanged and pinned by a golden regression test. Seeded property and edge tests
  (ties at the cut, repeated IDs, negative and signed-zero scores, K>N, inclusive boundary thresholds, no matches, insertion-order
  permutations, unbounded K) match the full-sort oracle on IDs and score bits. `BoundedTopKExperimentRunner` and CLI
  `./gradlew :monada-evaluation:runBoundedTopKExperiment -q` reuse the #119 configuration (N=100/1,000/5,000/10,000, K=1/5, seed 42,
  128 dimensions, 2 warmups, 5 repetitions), check parity before timing (selector vs oracle in hash and shuffled order; experimental vs
  production search at -Infinity and at exact-boundary thresholds at the top-K cut and the median), and time the selection phase and
  end-to-end `search()` in separate loops. Two idle-machine runs (AMD Ryzen 7 7730U, Linux 7.2.9, OpenJDK 27, load average <1.8 with no
  other Gradle process, warm page cache; exploratory): 0 parity mismatches over 720 checks per run; the selection working set drops
  from an M-element sort list (up to 10,000) to a K-element heap (the deduplicated map of up to M candidates is still built by both
  paths, so total search memory is not O(K)); selection allocation at N=10,000 drops from ~132 KB to ~0.1-0.2 KB; isolated selection
  p50/p95 at N=10,000 falls from ~3.5-3.7/3.8-4.1 ms to ~0.14-0.16/0.19-0.20 ms (0.04x); end-to-end `search()` p50/p95 at N=10,000
  falls from ~24.3-25.8/26.9-29.7 ms to ~20.2-20.4/21.9-23.7 ms (0.79-0.84x), at N=5,000 0.85x, at N<=1,000 0.89-0.98x. The same-session `runScanPhaseBreakdown` puts the full sort at ~12% of
  query time, so the ~16-21% end-to-end gain exceeds what removing the in-scan sort alone explains; measuring end-to-end in its own loop
  did not shrink it, and the oracle's ~132 KB per-search allocation (GC pressure) is a plausible, unverified contributor. Decision:
  `RETAIN_EXPERIMENT` (selection p50 <= 80% of the oracle and end-to-end p50 not slower within a 2% noise tolerance for every K at
  N=10,000; keep the experimental candidate; no automatic production rollout; adoption needs its own Issue and explicit approval).

## Near-Term Direction

### Phase P — Speech-specific evaluation metrics

Purpose: evaluate acoustic retrieval with metrics and reports that fit speech samples.

Expected scope:

- speech retrieval fixtures;
- top-K acoustic recall metrics;
- deterministic reports;
- metadata-filter diagnostics;
- regression tests.

Non-goals:

- speech recognition integration;
- external model providers;
- changing the default text memory API.

### Phase Q — Real-world text recall corpus and query set

Purpose: create a realistic text evaluation corpus from the project's own documentation and agent-style queries.

Expected scope:

- project-memory atoms derived from README, architecture, roadmap, ADRs, specs, and glossary;
- direct, multi-relevant, paraphrase, confusable, and follow-up-task queries;
- a dedicated evaluation entry point and Gradle task;
- a baseline report showing current recall gaps without protecting metric values.

Non-goals:

- scraping external services or GitHub;
- adding private data or LLM-generated relevance judgments;
- changing retrieval defaults or encoder behavior.

### Candidate — Bounded top-K scan optimization

Improve scan efficiency only after a measurable baseline shows a real bottleneck.

Status: exact bounded selector evaluated in Issue #120 (`RETAIN_EXPERIMENT`, parity proven, idle-machine end-to-end p50
0.79-0.84x at N=10,000, not enabled). Production adoption requires a separate Issue and explicit approval.

### Track — Forge Integration: embedded execution memory (#93–#102)

Purpose: let Monada Forge record and recall agent execution experiences, and let Monada Neuron consume
neutral samples, through an embedded library with traceable evidence and no orchestration in Store.
Contract: [ADR 0004](adr/0004-embedded-execution-memory.md) and
[execution memory contract v1](specs/execution-memory-contract-v1.md) (#93, documentation only).

Expected scope and implementation order:

- #94 consumable Java 27 library publications — depends on #93;
- #95 immutable execution and evidence domain records — depends on #93;
- #96 versioned append-only execution ledger with replay and integrity diagnostics — depends on #93, #95;
- #97 caller-owned `ExecutionMemory` facade with idempotent recording and revisions — depends on #94, #95, #96 (implemented: one scope per instance, ledger-backed history; see contract section 17);
- #98 scoped ledger-derived projections and bounded recall — depends on #97 (implemented: per-scope projection generations, explicit rebuild and prune, recall regression gate; see contract section 18);
- #99 attempt-chain usage and hypothetical `CostToAcceptedOutcome` calculators (`monada-evaluation`) — depends on #95, #97 (implemented: pure `ExecutionSummaryCalculator` with provenance-split usage, decimal hypothetical cost, explicit completeness, effort vs wall-clock and per-dimension gate evidence; see contract section 19);
- #100 evidence-gated pairwise comparison and Pareto diagnostics (`monada-evaluation`) — depends on #99 (implemented: pure `ExecutionComparator` with comparability reasons, caller-defined gate eligibility, explicit `INCONCLUSIVE` dimensions, conservative Pareto groups and no automatic winner on tradeoffs; observational evidence only, not a production default; see contract section 20);
- #101 bounded provenance-preserving sample export — depends on #97, #98, #99, #100 (implemented: read-only `exportSamples` with explicit cutoff, stateless cursor, labels with eligibility reasons, exact refs and canonical JSON digest; cost and Pareto annotations and a read-only command in `monada-evaluation`; see contract section 21);
- #102 reproducible end-to-end Forge experience evaluation and isolation gates — depends on #94, #96–#101 (implemented: versioned synthetic fixture `forge-experience-v1`, `runForgeExperienceEvaluation` with seven hard correctness/isolation gates separated from EXPLORATORY retrieval quality, and a two-project scenario in the isolated library consumers; see the README section "Forge Experience Evaluation").

#### Forge experience evaluation: observed gaps and next experiment (#102)

Result on fixture `forge-experience-v1` (25 experiences, 2 projects, 14 judged queries): all seven hard gates
pass and the decision is `READY_FOR_HOST_ADAPTER_EXPERIMENT`. This is a correctness and isolation result only;
it is not evidence of any routing benefit, and the quality values below are EXPLORATORY (re-run the command for
the current per-query output; they are not frozen as a baseline).

Observed gaps, to be handled by separate bounded Issues if the host experiment needs them:

- **Paraphrase recall is weak.** The paraphrase query (Q02) reaches Recall@3 of only 0.33 and ranks one relevant
  experience at #11, while direct and multi-relevant queries reach Hit@1 = 1. The default exact lexical encoding
  is unchanged and untuned by this track.
- **Recall has no relevance floor.** `recall` fills up to the requested limit, including hits with similarity
  `0.0000`, and the no-relevant query (Q12) still returns lexical neighbours (best similarity about 0.21). A host
  must apply its own threshold; Store adds no default one here.
- **Labels are per execution.** A failed attempt inside an accepted execution is exported and recalled with the
  execution's `SUCCESS` label; a host that wants "what failed" must join the attempt result (`FAILED`) to the hit.
- **Scale.** 25 experiences cannot support statistical claims; scoped linear-scan latency was not part of this gate
  (measured separately up to 1000 experiences per scope by #125 below).

Next bounded consumer experiment (future, outside this repository): a host adapter in Forge that records real
executions into `ExecutionMemory`, applies its own similarity threshold, joins recall hits to exported samples by
execution, and is judged against its own manually reviewed queries with these same gates. No routing, learning or
Neuron adaptation is enabled by this track.

#### Multi-attempt fixture and attempt-level result joins (#121)

Addresses the gap "Labels are per execution" with evidence, not with a semantic change. A separate, manually judged
fixture `forge-multi-attempt-v1` (21 attempts in 11 executions, 2 projects, 10 judged queries) holds retries inside
one execution (failed then repaired, abandoned then accepted, two failures then a repair, an unfinished repair), a
correction that changes an attempt result (`COMPLETED` r1 to `FAILED` r2), independent executions with identical
summaries and texts in one project, and an identical summary in the other project. The command
`./gradlew :monada-evaluation:runMultiAttemptExecutionEvaluation -q` resolves every recall hit to its exact
`ATTEMPT_FINISHED` event, joins it by execution id to the exported sample (latest export and an export at a fixed
cutoff, both paged) and reports four separate dimensions: the raw execution label and acceptance (unchanged), the
attempt result, whether the attempt is the validated accepted attempt, and the reference (event id, revision, ledger
sequence). Six hard gates (`MA1`–`MA6`: exact resolution, attempt-result join, exported attempts, cutoff, scope
isolation, determinism) all pass, decision `ATTEMPT_JOINS_VERIFIED`: no failed or cancelled attempt is reported as
accepted while its execution keeps its `SUCCESS` label, and hits recorded after the cutoff are never joined to an
earlier state.

Not changed: `ExperienceSample`, `execution-sample/1`, labels, acceptance derivation, fixture `forge-experience-v1` and
its seven gates (the `runForgeExperienceEvaluation` report is byte-identical apart from timing). The hits listed by the
new report are lexical neighbours used as join evidence; no retrieval-quality or routing claim is made. A host still
performs this join itself; promoting an attempt-level field into the export would need a separate contract change.

#### Judged recall stress set for paraphrases and confusable tasks (#122)

Characterizes, with evidence and without any semantic change, how the current exact lexical recall misses
paraphrases and surfaces similar-but-irrelevant tasks. Fixture `forge-recall-stress-v1` adds 26 hand-judged queries
over the UNCHANGED `forge-experience-v1` corpus: paraphrase, synonym, negation, relation/preposition, terminology,
no-relevant, duplicate-summary and empty-query categories (3-4 each). Each query lists its relevant experiences and,
separately, the experiences judged *confusable* (shared vocabulary or task, not an answer, for example the approach a
negated query excludes); empty relevant sets are explicit. Judgments were written from the corpus texts before any
recall ran and were reviewed by the repository owner in PR #140 (S06 kept; S10, S16 and S22 revised; S13 aligned with
S07); changing one now requires a new fixture version. The 14 #102 queries are read
from their own fixture and reported as the `BASELINE` group.

`./gradlew :monada-evaluation:runForgeRecallStress -q` reports per query every rank and similarity, relevant positions,
misses outside the top 5, a reason (`RESOLVED`, `PARTIAL_RECALL`, `OUTRANKED`, `NOT_IN_TOP_K`, `ZERO_SIMILARITY_ONLY`,
`NOT_RETRIEVED`, `EMPTY_AS_EXPECTED` for blank queries, `NO_HITS` for non-blank queries without a hit,
`FALSE_NEIGHBOURS_RETURNED`; a zero-similarity fill hit never makes a query `RESOLVED`) and a category per hit (`RELEVANT`,
`JUDGED_CONFUSABLE`, `SAME_EXECUTION`, `SAME_TASK`, `LEXICAL_NEIGHBOUR`, `ZERO_SIMILARITY`), plus Hit@K, Recall@K and
MRR per category with zero-relevant queries listed and excluded from every denominator. Four hard checks decide the
exit code (`RS1` scoped CURRENT recall, `RS2` blank queries return nothing, `RS3` two fresh stores rank identically,
`RS4` the #102 evaluation passes and its rankings equal this runner's; both run the same code, so a regression against
`main` shows only in a before/after report diff); metrics are EXPLORATORY, have no threshold and are not a protected
baseline.

Observed on 2026-10-15 after the owner's relevance review in PR #140 (all hard checks pass; ALL_STRESS: 19 scored,
7 excluded; Hit@1 0.58, Recall@5 0.80, MRR 0.71 against BASELINE Hit@1 1.00, Recall@5 0.97, MRR 1.00):

- **Synonyms and terminology are the weakest groups.** Synonyms (Hit@1 0.33, MRR 0.44): S06 ("resend undelivered callbacks") retrieves no webhook
  attempt within 50 hits; S04 reaches its relevant attempts only as zero-similarity fill (`ZERO_SIMILARITY_ONLY`).
- **Terminology** (Hit@1 0.00, MRR 0.29): abbreviations and jargon ("NPE", "idempotent consumer") rank lexical
  neighbours first, and S16 ("randomized spread to retry delays" for jitter) retrieves no relevant attempt at all.
- **Negation is invisible to lexical recall**: in S07 and S09 the excluded approach ranks #1 (`JUDGED_CONFUSABLE`); the
  same offset attempt also leads the cursor-paging query S13.
- **No relevance floor**: every no-relevant query returns confusable neighbours, up to similarity 0.40 (S18, key
  rotation vs key placement) — above the similarity of several relevant hits elsewhere (S01 tops out at 0.18), so a
  single host threshold cannot separate them on this corpus.
- Duplicate summaries and relation/preposition variants resolve at Hit@1 1.00; punctuation-only and stopword-only
  queries return no hit (`NO_HITS`: observed, not promised by any contract).

These are 26 queries over 25 experiences: they localize failure modes for Next Steps 5/10 and 6/10, not statistical
claims. Not changed: encoders, `summary-text-v1`, query keys, thresholds, `forge-experience-v1` and its seven gates
(the `runForgeExperienceEvaluation` report is byte-identical apart from timing).

#### Host-owned relevance threshold sweep and abstention (#123)

Measures, without recommending anything, what a host-side relevance floor would cost on the #122 judged set.
`./gradlew :monada-evaluation:runForgeRecallThresholdSweep -q` takes the fixed #122 rankings and judgments and applies
each threshold of a declared grid AFTER recall, as a host would: keep a hit when `similarity >= threshold` (inclusive,
exact comparison, ties at the threshold kept or dropped together), then read the top 5 in unchanged rank order. The
grid is `0.00` (current behaviour, zero-similarity fill included) and nine candidates `0.05`–`0.45`, declared from the
#122 observations (relevant paraphrase hits about 0.06–0.18, no-relevant neighbours up to 0.21/0.25/0.33/0.40) and
never derived from the sweep's output. Per threshold and per category it reports precision among kept hits, recall
among judged positives, top-K coverage, Hit@1 and abstention for queries with relevant experiences; separately, for
the four no-relevant content queries (Q12, S17–S19), false returns, false hits and abstention (never in a positive
denominator). Empty queries (blank, plus the punctuation-only and stopword-only probes S25 and S26) return nothing at
every threshold and enter no denominator, so they cannot inflate no-relevant abstention. Costs per candidate list every lost relevant hit (fill marked) and the
wrong matches kept by neighbour category. Three hard checks decide the exit code (`TS1` the #122 checks pass, `TS2`
`0.00` equals the unfiltered top 5 and every threshold keeps exactly min(5, hits at or above it) in rank order, `TS3`
each higher threshold keeps a prefix of the lower one); every metric is
EXPLORATORY.

Observed on 2026-10-15 (all checks pass; 31 scored queries, 66 judged positives, 4 no-relevant content queries):

| threshold | precision | recall | coverage | scored abstain | no-relevant returning | false hits |
| --- | --- | --- | --- | --- | --- | --- |
| 0.00 | 0.36 | 0.85 | 0.94 | 0.00 | 4/4 | 20 |
| 0.05 | 0.42 | 0.80 | 0.90 | 0.00 | 4/4 | 17 |
| 0.10 | 0.60 | 0.76 | 0.87 | 0.10 | 4/4 | 9 |
| 0.20 | 0.80 | 0.68 | 0.77 | 0.23 | 4/4 | 7 |
| 0.25 | 0.86 | 0.65 | 0.71 | 0.29 | 2/4 | 2 |
| 0.40 | 0.90 | 0.56 | 0.68 | 0.32 | 0/4 | 0 |

- `0.05` is the highest declared threshold that loses no positive-similarity relevant hit (it drops only zero-similarity
  fill, including the fill-only relevant hits of S04), yet all four no-relevant content queries still return 17 false hits.
- `0.40` is the lowest at which every no-relevant query abstains (S18 at 0.3953 is the last), but it loses 19 relevant
  hits and 8 scored queries lose every relevant hit; paraphrase and synonym groups abstain entirely, and the four
  judged confusables ranked #1 by negation queries S07–S09 and the cursor-paging query S13 (0.49–0.74) still pass.
- No declared threshold separates the two sides on this corpus. The operating point is **undecided and host-owned**:
  it depends on the host's cost of a wrong match versus a lost relevant match, and 25 synthetic experiences with one
  encoder are no cross-dataset evidence. No threshold is recommended, added as an option or made a default.

Not changed: `ExecutionMemory.recall`, `MonadaMemory`, projections, the default threshold, fixtures and judgments, and
the `runForgeExperienceEvaluation` and `runForgeRecallStress` reports (byte-identical apart from timing).

#### Bounded query-side lexical expansion, offline A/B (#124)

Tests one narrow paraphrase-recovery hypothesis without touching encoding: append documented terms to the QUERY only
and compare against the exact query on the same persisted projection.
`./gradlew :monada-evaluation:runForgeQueryExpansionComparison -q` records the `forge-experience-v1` corpus once into a
scratch store and recalls every #122 query through the public read-only API with its exact text and with each
candidate's expanded text; judgments stay those of the source query. The expansion table
`forge-query-expansion-v1/terms.psv` (evaluation resources only) has 23 whole-token rules (cap 24, at most 3 terms per
rule, at most 8 added terms per query, source text kept verbatim), each with a risk (12 LOW, 6 MEDIUM, 5 deliberately
risky HIGH) and a safe/unsafe rationale declared before the comparison ran. Candidates: `FULL` (all rules), `LOW_RISK`
(LOW rules only) and each rule in isolation.

Six hard checks decide the exit code (`QX1` #122 checks pass, `QX2` the exact side equals the #122 rankings, `QX3`
unexpanded and blank queries are sent unchanged and rank identically, `QX4` every expansion stays within its bounds and
only adds new terms of fired rules, `QX5` every store file is byte-identical after all candidate recalls, `QX6` two
fresh stores give identical candidate rankings). Candidate verdicts are exploratory and vetoed by any regression of a
scored BASELINE/NEGATION/RELATION/DUPLICATE_SUMMARY query (`V1`) or other scored query (`V2`), a judged confusable in
the top 5 that ranks better or scores higher than under exact recall (`V3`), or, on a query with nothing relevant, a new top-5 neighbour or a wrong match gaining similarity
(`V4`). The best verdict is `JUSTIFIED_FOR_FOLLOW_UP`; there is no adopt verdict.

Observed on 2026-10-14 (all six checks pass; 19 scored stress queries):

| candidate | verdict | ALL_STRESS Hit@1 | R@5 | MRR | improved | vetoes |
| --- | --- | --- | --- | --- | --- | --- |
| exact | — | 0.58 | 0.80 | 0.71 | — | — |
| FULL (23 rules) | REJECTED | 0.89 | 1.00 | 0.95 | S02 S03 S04 S06 S13 S14 S15 S16 | V3 S07, S13, S18; V4 S18 |
| LOW_RISK (12 rules) | REJECTED | 0.74 | 0.96 | 0.86 | S02 S04 S06 S13 S15 S16 | V3 S07, S13 |

- **Aggregate gain did not decide.** Both candidates improve every aggregate and both are rejected. R23
  (`rotate -> rotation tokens`) makes the no-relevant key-rotation query S18 return a new neighbour and doubles the
  similarity of the judged confusable token-cleanup attempt (0.13 -> 0.28): the key-versus-token collision the rule's
  rationale predicted.
- **Even LOW-risk rules strengthen the excluded approach of negation queries.** R10 (`paginate -> pagination`) raises
  the excluded offset attempt of S07 at #1 (0.5601 -> 0.6288); R11 (`paging -> pagination`) lifts the relevant keyset
  attempt of S13 to #1 but also raises the excluded offset attempt (0.4901 -> 0.5717, 0.0009 below it). A term map that
  adds the topic word cannot respect "without X"; R18 (`cursor -> keyset`) alone is the only S13 rule without a veto.
- **Risky rules look good in-sample.** R21 (`crashes -> null pointer`, symptom mapped to one cause) and R19
  (`idempotent -> dedup deduplication`) each improve their one query (S03, S14) without a veto, because this corpus has
  no crash that is not a null pointer and no other idempotent consumer. Their safety is not established; that is why
  the risk column, not the observed effect, defines `LOW_RISK`.
- No rule fires on S08–S12, so relation queries and the other negation queries are unchanged.
- **False sharing comes from the encoder, not only from meaning.** Under `LOW_RISK`, adding `ingestion` to S04 lifts
  the unrelated pagination attempt `b-page/a1` to #1: in the 128-bucket signed lexical hash, `ingestion` shares a
  bucket and sign with `customer` and `protect`. A query-side term map cannot control such collisions.
- Paraphrases needing several new words (S01 "chunks", S05 "purge stale credentials") stay unchanged or unimproved.

Decision: query-side expansion is **rejected** for this track: no candidate survives the collision and negation vetoes,
and nothing is adopted. Individually unvetoed rules (for example R01, R03–R05, R12) are in-sample observations only; a
follow-up would need its own approved Issue, a held-out judged set written without this table in view, negation-aware
handling and collision analysis against the encoder's hash buckets. The table was written with the judged
vocabulary in view, so all effects are in-sample evidence on 25 synthetic experiences.

Not changed: encoders, `ProjectionText`/`summary-text-v1`, projection rebuild format, query keys, `ExecutionMemory.recall`,
`MonadaMemory`, defaults, fixtures and judgments; the `runForgeExperienceEvaluation`, `runForgeRecallStress` and
`runForgeRecallThresholdSweep` reports are byte-identical apart from timing.

#### Scoped recall at realistic corpus sizes (#125)

Measures what the 25-experience #102 gate could not: how append, projection rebuild, reopen, read-only open and
scoped top-K recall behave as experience count grows, without changing anything they measure.
`./gradlew :monada-evaluation:runExecutionScaleSweep -q` (options via `--args`, e.g.
`--args="--sizes=25,250,1000,2500 --iterations=10"`) generates a synthetic corpus from an explicit seed (default
42) for three isolated scopes (`scale-alpha`, `scale-beta`, `scale-gamma`) at N = 25/250/1000 recallable experiences
per scope, using the #121 occurrence patterns: single accepted attempt, failure then repair, failure then an unfinished
repair, a finish corrected r1 -> r2 (its first text becomes a retired atom), groups of independent executions with
identical text (one atom, several refs) and one probe text identical in every scope. Each size runs in a fresh root:
append every event, check the incrementally maintained projection, rebuild twice, reopen writable, open all scopes
read-only and recall 10 queries per scope at K = 1/5/20 (2 untimed warmup + 5 timed rounds over every query and K,
with the K order rotated by one every round so no K arm always runs warmer). The probe, the first duplicate group and
three anchor executions (failure then repair, corrected finish, single attempt) form a size-independent prefix; the
queries are drawn from it before any size-dependent draw, so every size recalls the same queries (the report prints
one query set digest per size) and only the corpus size changes between scale points.

Five hard checks decide the exit code at every size (decision `SCALE_SWEEP_CHARACTERIZED`): `S1` zero foreign-scope
hits and foreign `ScopeId`s rejected by recall, load and export; `S2` every hit is an expected latest-revision finish
whose ref loads exactly from the ledger with matching coordinates, result and latest solution, and no recall returns
more than K hits; `S3` hits ordered by
similarity, atom id and canonical ref, with each atom's refs contiguous and equal to the published checkpoint's; `S4`
rankings identical after append (taken from the instance that appended, so its incrementally maintained projection is
what is compared), first and second rebuild, writable reopen, read-only open and across every repeated timed recall,
with the projection `CURRENT` and covering the whole ledger in each phase and every hit stamped with a covered
sequence equal to the recorded ledger and the projection version the recall reports; `S5` ledger events, refs,
live atoms (distinct texts), retired atoms (corrected texts; the same retired atom ids after append, each rebuild,
the reopen and in the read-only generation, never live in the same generation) and dimensions (128) match the generated ground truth.
The deterministic report also lists per scope executions, attempts started, unfinished attempts, corrections, active
attempts, the projection generation after each phase and the structural candidate count (live + retired atoms in
the published generation). Timing, latency percentiles, a heap proxy (used-heap delta after `System.gc()` when the
three read-only snapshots open) and on-disk bytes are printed separately and never gate. The cross-JVM determinism
test runs the command in 6 concurrent JVMs with `--sizes=25,100,250 --warmup=1 --iterations=2` (the full default
exceeds its 120 s budget when six JVMs fsync their stores at once).

Observed on 2026-10-15 (all five checks pass at every size; two runs render byte-identical reports; Java 27
OpenJDK 64-Bit Server VM 27+35, Azul; Linux 7.2.10 x86_64; 16 processors; max heap 3832 MiB):

| N per scope | ledger events (α/β/γ) | live / retired atoms (α) | rebuild, 3 scopes | read-only open | recall p50 / p95 K=1 | K=20 | heap delta | disk per scope (ledger + projection) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 25 | 119 / 120 / 124 | 20 / 1 | 74 ms | 34 ms | 28 / 40 µs | 49 / 80 µs | 0.4 MiB | ~36 + ~41 KiB |
| 250 | 1231 / 1232 / 1240 | 211 / 26 | 338 ms | 76 ms | 82 / 108 µs | 96 / 117 µs | 3.8 MiB | ~368 + ~415 KiB |
| 1000 | 4983 / 4946 / 4915 | 857 / 93 | 954 ms | 305 ms | 389 / 462 µs | 419 / 482 µs | 15.3 MiB | ~1.5 + ~1.6 MiB |

- **Correctness held at every size.** No leakage, every ref exact, tie order and atom grouping stable, parity across
  every phase and repeated recall; 18/18 exact-text queries per size returned the target (or an identical-text twin)
  at rank 1 (exploratory, not gated).
- **Recall and memory grow about linearly with scope size.** From 250 to 1000 experiences recall p50 rises about 4x
  and the read-only heap delta about 4x. With the K arms interleaved, larger K costs a little more (K=20 vs K=1 p50:
  +76% at N=25, +17% at N=250, +8% at N=1000): the per-hit work shrinks relative to the scan as the scope grows.
  Single machine, 150 samples per K and size: these are orders of magnitude, not benchmarks.
- **Retired atoms stay in the scan.** Corrected r1 texts remain retired atoms after both rebuilds (93 of 950 candidates
  in `scale-alpha` at N=1000, about 10%), so linear-scan work tracks every text ever projected, not only live refs.
- **Rebuild grows with scope size** (about 0.3 s per 1000-experience scope here, similar to appending the whole
  scope); append stayed under 1 s for all three scopes at every size.

Not changed: `ExecutionMemory`, `LinearScanResonanceIndex`, `MAX_LIMIT`, projection and ledger formats, defaults,
caching and rebuild policy (no implicit rebuild); the #102, #121, #122, #123 and #124 reports are unchanged apart
from timing. Follow-up decisions (each needs its own Issue): whether retired atoms should be compacted out of a
rebuilt generation, and whether sizes beyond a few thousand per scope warrant an index other than the linear scan.

#### Ledger growth, reopen/replay and sample-export paging (#126)

Finds where cost grows in the authoritative event history and the read-only export before any change to the ledger
layout is considered. `./gradlew :monada-evaluation:runLedgerGrowthBenchmark -q` (options via `--args`, e.g.
`--args="--sizes=100,1000,5000,10000 --exportPageSizes=1,50,100 --iterations=10"`) cuts exactly N = 100/1000/5000
events (plus a tail of N/10) from scope `scale-alpha` of the #125 generator (seed 42): single, repair, unfinished
repair, corrected r1 -> r2 and duplicate executions with several events per attempt; every prefix is a valid ledger.
Each size gets a fresh root and measures, through `ExecutionMemory` and through the storage-level `ExecutionLedger`
/ `ExecutionLedgerReader` so ledger cost can be told apart from index and projection cost: append (per event),
idempotent re-records, writable reopen, lock-free read-only open (with a cold first export page and a heap proxy),
replay view, full history paging at page sizes 1/100/500, exact `loadEvent` of every event, and full sample export
with the cutoff pinned at N at page sizes 1/50/100 (2 untimed warmup + 5 timed rounds; one fresh instance per open
round). It then appends the tail and re-reads the pinned export and pre-append cursors.

Five hard checks decide the exit code at every size (decision `LEDGER_GROWTH_CHARACTERIZED`): `L1` every event
`APPENDED` at the next sequence through both layers with byte-identical ledger segments, and re-records (up to 50
evenly spaced events, every event below 50, plus every correction) `IDEMPOTENT` at the original sequence without
changing the watermark or the segment; `L2` every writable reopen and read-only open (paged entry by entry in every
round), `ExecutionLedger.replay()` and `ExecutionLedgerReader.replay()` return exactly the recorded events in order,
as does a read-only snapshot at every history page size, healthy and without a torn tail, with the expected execution
count; `L3` every event loads exactly by id and revision, and every ref a sample carries (execution,
outcome, gates, attempt start/finish, stages, evidence, revisions) loads at or before the cutoff with matching
coordinates and sequence; `L4` the same samples in the same order at every export page size, on writable and
read-only instances and in every round, one per execution, with the expected page counts; `L5` after the tail is
appended, pinned pages, every resumed pre-append export cursor and a resumed pre-append history cursor return
byte-identical pages, each checked both on the writer and on a new read-only snapshot, while a `latest()` export sees
the tail. The
deterministic report prints event kinds, segment bytes and digest, history and export page counts, the samples digest
(independent of paging), the ledger prefix digest and per-page-size export digests. Timings, throughput, p50/p95,
the heap proxy, growth exponents and the file system of the work directory are printed separately and never gate.
The cross-JVM determinism test runs it with `--sizes=50,200,500 --warmup=0 --iterations=1`.

Observed on 2026-10-16 (all five checks pass at every size; two runs and a run on another file system render
byte-identical reports; Java 27 OpenJDK 64-Bit Server VM 27+35, Azul; Linux 7.2.10 x86_64; 16 processors; max heap
3832 MiB). Durable storage, btrfs on NVMe (`-Djava.io.tmpdir` on disk; the Gradle task uses `/tmp`, tmpfs here, where
fsync is nearly free), medians:

| N events | executions = samples | segment | record p50 facade / ledger | append N, facade | writable open facade / ledger | read-only open facade / reader | `loadEvent` | full export ps=1 / 50 / 100 | cold first page | heap delta |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 18 | 30 KiB | 2.1 / 0.97 ms | 0.27 s | 13 / 3.9 ms | 15 / 3.9 ms | 1.1 µs | 2.6 / 1.5 / 0.6 ms | 3.2 ms | 0.1 MiB |
| 1000 | 181 | 300 KiB | 2.4 / 1.3 ms | 2.5 s | 53 / 14 ms | 59 / 16 ms | 0.7 µs | 56 / 4.1 / 3.4 ms | 9.5 ms | 1.0 MiB |
| 5000 | 909 | 1.5 MiB | 2.5 / 1.3 ms | 13.6 s | 148 / 38 ms | 177 / 43 ms | 0.3 µs | 118 / 7.2 / 6.1 ms | 19 ms | 5.1 MiB |

On tmpfs at N=5000: record p50 51 / 6.3 µs, append 0.44 s, writable open 95 / 28 ms, read-only open 97 / 28 ms.

- **Correctness held at every size.** Idempotent re-records, exact replay of every reopened facade and at every page
  size, 18 648 sample ref resolutions (N distinct refs at each size: the samples reference every ledger event),
  identical samples across page sizes and instances, and pinned pages and all 2 278 pre-append cursors unchanged after later
  appends on the writer and on a new snapshot.
- **Append cost is durability, not growth.** Per-event cost is flat from 1000 to 5000 events (growth exponent about 0);
  on disk it is the per-append force (about 1.3 ms per ledger append, about 2.4 ms per `ExecutionMemory.record`, that
  is roughly 370 events/s through the facade), on tmpfs it drops to 6 / 51 µs. Total append time is linear in N only
  because each event costs the same.
- **Primary growth bottleneck: opening the scope.** Writable reopen and read-only open grow with N (exponent about
  0.6-0.7 between 1000 and 5000 on disk, about 0.35 on tmpfs) and take 3.5-4x the storage-level open or reader of the
  same ledger (148 / 38 ms and 177 / 43 ms at N=5000). Most of the open is therefore above the whole-scope ledger
  validation (history index and projection load and verification), and a read-only snapshot retains about 1 KiB of
  heap per event. Five thousand events is still well under a second; this ranks costs, it does not show a limit.
- **Reads after open are flat or bounded by the page.** `loadEvent` stays sub-microsecond, full history paging of 5000
  events at page size 500 takes about 13 µs, and an export page costs the same at 1000 and 5000 events (about 120 µs at
  page size 1): resuming a cursor does not rescan the ledger. Full export time follows the number of samples and pages;
  page size 1 costs about 16-19x page size 100 for the same samples. The first page of a fresh instance (about 19 ms at
  N=5000) pays for the per-cutoff work once.

Not changed: ledger codec, segment layout, `ExecutionMemory`, `ExecutionLedger`, `ExecutionLedgerReader`, the export
API, cursors, limits and defaults; the only edit to existing evaluation code makes two helpers of
`ExecutionScaleSweepEvaluator` package-private for reuse, and the #125 report is unchanged apart from timing. Follow-up decision (needs its own
Issue): measure, still without changing formats, how `ExecutionMemory.open` / `openReadOnly` split between ledger scan,
history index build and projection load/verification, at larger N, before considering a persisted index or a segment
lifecycle. No compaction is suggested: the ledger itself is not the dominant cost at these sizes.

Numbering is roadmap order; independent items (for example #94 and #95) may proceed in parallel.

Non-goals:

- HTTP/MCP servers, network or provider calls, live price lookup;
- automatic collection of repositories, logs, secrets or artifacts;
- changes to legacy storage formats, defaults, protected baselines or existing API signatures;
- a unified quality score or presenting subscription usage as API billing.

Future cross-repository consumer work (not part of this track and not implemented): Forge adapters
that record executions and use recall, and Neuron adapters that consume exported samples. Each needs its
own Spec Context in that repository.

## Long-Term Research Options

- approximate indexes;
- local model encoders;
- quantized vector formats;
- richer acoustic features;
- hybrid text/acoustic ranking;
- compaction and segment lifecycle;
- public speech API integration.

## Decision Rule

A roadmap item is ready when it has a Spec Context with current state, measurable problem, scope, non-goals, affected modules, acceptance criteria, verification commands, and compatibility notes.

Normalized feedback matching must not advance to a supported opt-in or protected contract from the
current evidence. Any future attempt requires a separate design Issue that changes normalization
semantics explicitly and re-runs both project-memory validation and adversarial stress evidence.

Execution-memory features must follow the [execution memory contract v1](specs/execution-memory-contract-v1.md);
semantic changes to it require updating that contract and ADR 0004 before implementation.
