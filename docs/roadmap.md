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
- **Scale.** 25 experiences cannot support statistical claims; scoped linear-scan latency was not part of this gate.

Next bounded consumer experiment (future, outside this repository): a host adapter in Forge that records real
executions into `ExecutionMemory`, applies its own similarity threshold, joins recall hits to exported samples by
execution, and is judged against its own manually reviewed queries with these same gates. No routing, learning or
Neuron adaptation is enabled by this track.

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
