# Execution Memory Contract v1

Status: accepted contract. The immutable domain records (section 15, issue #95) and the ledger with
replay and read-only audit (section 16, issue #96), the `ExecutionMemory` facade (section 17,
issue #97), scoped projections with bounded recall (section 18, issue #98) and the usage and
hypothetical-cost calculators (section 19, issue #99) and the evidence-gated comparison with Pareto
diagnostics (section 20, issue #100), the bounded sample export (section 21, issue #101) and the
reproducible Forge experience evaluation (section 22, issue #102) are implemented. Decision record: [ADR 0004](../adr/0004-embedded-execution-memory.md). Issue: #93 (Forge Integration 1/10).

This document is the normative v1 contract that issues #94–#102 implement. Later issues may refine
exact Java signatures and the byte-level payload codec, but must not change the semantics defined here
without updating this document and ADR 0004 first.

Normative words: **must**, **must not**, **should** and **may** carry their usual specification meaning.

## Background

Monada Forge needs to remember agent execution experiences (tasks, attempts, stages, outcomes, quality
evidence, usage and artifact references) and Monada Neuron needs neutral samples of that history.
[ADR 0002](../adr/0002-memory-owned-by-resonance-store.md) assigns persistence, encoding and retrieval
to this repository; ADR 0004 extends that ownership to execution memory while keeping orchestration,
evidence collection and judgment in Forge, and adaptation and routing in Neuron.

## Current State

Inspected `main` at `e823256` (JDK 27, Gradle Wrapper 9.8.0):

- `MonadaMemory` exposes `open(...)`, `remember(String)`, `remember(String, List<String>)`,
  `resonate(String)` and `feedback(...)`. It has no execution API and is not `AutoCloseable`.
- `FileAtomStore` writes 6-field tab-separated atom lines (reads legacy 5-field lines), rejects non-empty
  `KnowledgeAtom.metadata`, and resolves repeated IDs last-wins.
- `KnowledgeAtom.id` is `UUID.nameUUIDFromBytes(content UTF-8)`; identical content merges into one atom.
- Manifest versions `0.1`–`0.4` are accepted with version-specific validation; only an explicit
  `VectorRebuilder` run upgrades a store.
- `FeedbackEvent` (`query`, `queryKey`, `atomId`, `signal`, `delta`, `createdAt`) records retrieval
  usefulness only. `ExactQueryKeyStrategy` and feedback-aware ranking are the defaults.
- No storage component uses file locks; single-writer use is implicit.
- No `execution` package exists in any module.

## Goal

Define, without implementing, the v1 semantics for:

1. identity, scope and provenance;
2. a versioned append-only execution ledger, its layout, record envelope, replay and diagnostics;
3. idempotency, conflicts, revisions and lifecycle;
4. outcomes, quality evidence, usage, hypothetical cost and comparability;
5. ledger-derived projections, bounded recall, history reads and sample export;
6. compatibility with every existing store format, default and public API.

## Non-Goals

- No production code, schema change, default change, dependency, module, test or build change.
- No HTTP, MCP or other server; no network or provider calls; no live price lookup.
- No automatic collection of repositories, logs, transcripts, secrets or artifacts.
- No changes to Monada Forge or Monada Neuron.
- No unified quality score, no causal or model-superiority claims.
- No concurrent multi-writer support, compaction, encryption or retention policy in v1.

## Affected Modules

- `monada-core`: yes — `com.monada.core.execution` immutable records (#95, implemented).
- `monada-encoder`: no — projections reuse the existing encoding unchanged.
- `monada-storage`: yes — `com.monada.storage.execution` ledger, codec, replay, audit (#96, implemented).
- `monada-index`: no — projections reuse `LinearScanResonanceIndex` unchanged.
- `monada-learning`: no.
- `monada-api`: yes — `com.monada.api.execution` facade, derived acceptance and history (#97, implemented); projections and bounded recall (#98, implemented, section 18); sample export (#101, implemented, section 21).
- `monada-evaluation`: yes — `com.monada.evaluation.execution` usage/cost calculators (#99, implemented,
  section 19), evidence-gated comparison (#100, implemented, section 20) and export annotations and
  tool (#101, implemented, section 21); the reproducible Forge experience evaluation and its
  hard gates (#102, implemented, section 22).
- `monada-speech`: no.

`monada-api` must never depend on `monada-evaluation`. The documented dependency direction is unchanged.

## 1. Terminology and Identity

| Term | Meaning |
| --- | --- |
| Scope | Opaque caller-issued project/workspace identity. The isolation boundary for storage, recall, history and export. |
| Task | The unit of work the caller wants done (e.g. an issue). Many executions may target one task. |
| Execution | One end-to-end attempt chain to complete a task under a fixed starting provenance. |
| Attempt | One try inside an execution (initial work, repair, retry). Attempts are ordered by the caller. |
| Stage | A step inside an attempt (plan, implement, review, test, QA...). Recorded as a `STAGE_RECORDED` event. |
| Event | One immutable ledger record. The only persisted unit. |
| Revision | A correction of an earlier event, appended as a new event; earlier events are never rewritten. |
| Artifact reference | Opaque pointer to caller-held evidence (kind, reference, optional digest). Never dereferenced by Store. |
| Experience reference | `ExperienceRef(scope, task, execution, attempt?, eventId, revision)`: exact address of ledger facts. |

Identifier rules (`ScopeId`, `TaskId`, `ExecutionId`, `AttemptId`, `EventId`). Artifact `reference`
values follow the same character rules (no control characters, no leading or trailing space character,
no Unicode normalization form required) but allow up to 512 code points; artifact `kind` and `digest` are opaque descriptors
with the same 512 bound:

- Caller-issued; the library never generates them.
- 1–128 Unicode code points (512 for artifact `reference`), preserved exactly as supplied (no normalization form is required and none is applied, so NFC and NFD spellings are distinct IDs), no control
  characters, no leading or trailing space character (below). Validation happens before any I/O.
- Compared by exact `String.equals`; case-sensitive.
- Never derived from text hashes and never used as filesystem path segments.
- `EventId` is unique within a scope. Other IDs are unique within their parent (task within scope,
  execution within scope, attempt within execution).

A **space character** is any code point for which `Character.isWhitespace(cp)` or
`Character.isSpaceChar(cp)` is true, plus U+0085: U+0009–000D, U+001C–001F, U+0020, U+0085, U+00A0,
U+1680, U+2000–200A, U+2028, U+2029, U+202F, U+205F and U+3000. Summary texts that consist only of
space characters are blank and rejected. Other implementations must use exactly this set.

Provenance fields are descriptive, not authoritative:

- `sourceRevision` (e.g. a Git SHA), `contextFingerprint`, `constraintsFingerprint` and
  `evaluationPolicy` (id + version) are opaque strings recorded at `EXECUTION_STARTED`.
- Store never resolves them against a filesystem or repository.

All timestamps (`occurredAt`, `recordedAt`, stage start/end) are caller-supplied `Instant`s. The library
must not call `Instant.now()`, `System.currentTimeMillis()` or random generators for persisted data.

## 2. Ledger Layout v1

The host passes an explicit root directory to `ExecutionMemory.open(root, config)`. The root is
dedicated to execution memory; it must not be a legacy text store root.

```text
<root>/
├── execution-manifest.json          # format + version; written once on first writable open
├── write.lock                       # exclusive OS lock held by the single writer
└── scopes/
    └── s-<sha256hex(UTF-8 scope ID)>/
        ├── scope.id                 # raw scope ID, UTF-8, verified on every open
        ├── ledger/
        │   └── events-000001.log    # authoritative append-only events
        ├── projection.current       # pointer "projection-<n>" + LF, replaced atomically (section 18)
        └── projection-<n>/          # current generation: derived, rebuildable (sections 11 and 18)
            ├── projection-checkpoint.log
            └── memory/              # standard MonadaMemory layout (manifest 0.4)
```

`execution-manifest.json` v1 fields: `format` = `"monada-execution-ledger"`, `version` = `"1"`,
`recordCodec` = `"MXL1"`, `maxRecordBytes`, `scopeDirectoryScheme` = `"sha256-hex-v1"`.

Rules:

- Raw IDs are never path segments. The scope directory name is derived only from the SHA-256 of the
  scope ID; `scope.id` must equal the requested scope or the open fails (`SCOPE_ID_MISMATCH`).
- Every resolved path must stay inside `<root>` after normalization (fail closed; symlinks escaping the
  root are rejected).
- A root containing a legacy `manifest.json` but no `execution-manifest.json` is rejected.
- An unknown `format`/`version`/`recordCodec` is rejected; there is no automatic upgrade.
- Writable open creates missing directories only for the scope being written. Read-only open creates
  nothing: a missing root or manifest fails cleanly, while a missing scope of an existing root is an empty
  history (no files are created).
- Segment rollover (`events-000002.log`) is reserved; v1 writes a single segment.

## 3. Record Envelope

One event = one UTF-8 line terminated by `\n` (explicit LF, never `System.lineSeparator()`):

```text
MXL1<TAB><sequence><TAB><payloadByteLength><TAB><sha256hex(payload)><TAB><payload>\n
```

- `sequence`: per-scope ledger sequence, assigned by the ledger, starting at 1, strictly +1.
- `payloadByteLength` and the SHA-256 digest detect torn tails and interior corruption.
- `payload`: canonical, versioned encoding of the event with a fixed field order and no free-form maps.
  It must not contain raw TAB or LF; text fields are escaped or Base64-encoded. #96 chose a small
  hand-written token codec with no new dependency (section 16.2).

Envelope fields carried by every event payload, in this order:

| Field | Required | Notes |
| --- | --- | --- |
| `schemaVersion` | yes | `1` |
| `eventKind` | yes | Section 4 |
| `eventId` | yes | Unique within scope |
| `scopeId` | yes | Must match the directory's `scope.id` |
| `taskId` | yes | |
| `executionId` | yes | |
| `attemptId` | kind-specific | Absent only for `EXECUTION_STARTED` and execution-level outcomes |
| `revision` | yes | `1` for original facts; `n+1` for corrections |
| `supersedes` | corrections only | `eventId` of the latest revision being corrected |
| `occurredAt` | yes | Caller time the fact happened |
| `recordedAt` | yes | Caller time the fact was recorded |
| kind payload | yes | Typed fields of section 4 |

Limits (validated before I/O; values recorded in the manifest):

| Limit | v1 value |
| --- | --- |
| Encoded record (whole line) | ≤ 65,536 bytes |
| Each summary text (task, solution, lesson, justification) | ≤ 4,096 code points |
| Artifact references per event | ≤ 64 |
| Quality observations per event | ≤ 64 |
| Usage counters per event | ≤ 64 |
| Identifier length | ≤ 128 code points |
| Artifact `reference` length | ≤ 512 code points |

## 4. Event Kinds

| Kind | Purpose | Key payload |
| --- | --- | --- |
| `EXECUTION_STARTED` | Opens an execution | task summary, `sourceRevision`, `contextFingerprint`, `constraintsFingerprint`, `evaluationPolicy` (id, version, mandatory dimensions) |
| `ATTEMPT_STARTED` | Opens an attempt | attempt ordinal, `previousAttemptId?`, reason (`INITIAL`, `REPAIR`, `RETRY`, `REVIEW_FOLLOW_UP`) |
| `STAGE_RECORDED` | One stage of an attempt | stage name, `RouteDescriptor` (worker, provider, model, effort, billing mode), start/end, `usageDeclaration` (section 9), usage counters, artifact refs |
| `EVIDENCE_RECORDED` | Quality observations | `QualityObservation` list, artifact refs |
| `ATTEMPT_FINISHED` | Closes an attempt | `COMPLETED`, `FAILED` or `CANCELLED`; solution and lesson summaries |
| `OUTCOME_RECORDED` | Execution outcome | `Outcome` status + origin (section 8), accepted attempt if any |
| `CORRECTION` | Revises an earlier event | Full replacement payload of the superseded event kind, justification |

Ordering and reference rules (checked before append):

- `ATTEMPT_STARTED`, `STAGE_RECORDED`, `EVIDENCE_RECORDED`, `ATTEMPT_FINISHED` and `OUTCOME_RECORDED`
  must reference an execution (and attempt where applicable) already present in the scope's ledger.
- No `STAGE_RECORDED` or `EVIDENCE_RECORDED` after its attempt's `ATTEMPT_FINISHED` except via `CORRECTION`.
- An execution may receive several `OUTCOME_RECORDED` events (e.g. `PENDING` then `ACCEPTED`); the
  latest in ledger sequence is current, and earlier ones remain history.
- `CORRECTION` must target the latest revision of an existing event in the same execution; its
  `revision` is the target's revision + 1. Correcting a superseded event is a `CONFLICT`.

## 5. Write Semantics

`record(event)` returns exactly one of:

| Result | Condition | Effect |
| --- | --- | --- |
| `APPENDED` | New `eventId`, valid references | One line appended; sequence assigned |
| `IDEMPOTENT` | Same `eventId` and byte-identical canonical payload | Nothing written; original sequence returned |
| `CONFLICT` | Same `eventId` with different payload, superseded correction target, or other identity clash | Nothing written; reason returned |

Invalid input (limits, missing references, bad IDs, wrong scope) fails with a validation error before I/O
and writes nothing. Corrections never rewrite earlier lines, and replaying a correction never adds its
usage a second time (section 9).

## 6. Replay and Read

- Replay reads segment lines in file order; sequences must be contiguous from 1.
- A logical view (execution, attempt) applies events in sequence order, replacing corrected events by
  their latest revision while keeping all revisions available as history.
- An execution without `OUTCOME_RECORDED` replays as `PENDING`; an attempt without `ATTEMPT_FINISHED`
  replays as `IN_PROGRESS`. Incomplete but valid runs are never errors.
- Diagnostics carry file, line, sequence (if parseable) and category, and are deterministic:

| Category | Writable open | Read-only open / audit |
| --- | --- | --- |
| `MANIFEST_MISSING` / `MANIFEST_INVALID` / `UNSUPPORTED_VERSION` | Reject | Error |
| `SCOPE_ID_MISMATCH` | Reject | Error |
| `UNSUPPORTED_SCHEMA` | Reject | Error for that record |
| `MALFORMED_RECORD` / `DIGEST_MISMATCH` / `OVERSIZED_RECORD` (interior) | Reject | Error for that record |
| `SEQUENCE_GAP` / `DUPLICATE_SEQUENCE` | Reject | Error |
| `CONFLICTING_DUPLICATE_EVENT` / `DANGLING_REFERENCE` | Reject | Error |
| `TORN_TAIL` (incomplete final line) | Reject until explicit repair | Warning; valid prefix replayed |

- No silent truncation, skipping or auto-repair. An explicit repair operation is future work.
- Read-only open and audit never create, lock or modify files.

## 7. Lifecycle and Ownership

- `ExecutionMemory.open(...)` acquires an exclusive OS lock on `<root>/write.lock`. A second writable open
  (same or other process) fails immediately with a clear contention error; there is no waiting or
  multi-writer mode.
- `close()` releases the lock and is idempotent. Every operation after `close()` fails with
  `IllegalStateException`.
- A writable instance is not thread-safe; the host serializes calls. Read-only instances may be used
  concurrently with a writer and see a valid prefix of the ledger.
- Durability: `record(...)` returns `APPENDED` only after the full line is written and forced to the
  storage device (`FileChannel.force`). This survives process crashes; it is not a transactional
  guarantee across operating-system or hardware failures. A torn tail is detected on next open.
- Legacy `MonadaMemory` instances keep their current lifecycle; execution memory adds no hidden globals,
  background threads or shared static state.

## 8. Outcomes, Quality Evidence and Retrieval Feedback

Three separate concepts that never substitute for each other:

1. **Outcome** (`OUTCOME_RECORDED`): status `ACCEPTED`, `REJECTED`, `FAILED`, `CANCELLED` or `PENDING`,
   plus origin:
   - `VALIDATED` — the caller asserts acceptance under the execution's evaluation policy;
   - `IMPORTED_CLAIM` — historical or third-party claim without policy validation.
2. **Quality evidence** (`QualityObservation`): `dimension` (`CORRECTNESS`, `TESTS`, `SECURITY`,
   `ARCHITECTURE`, `MAINTAINABILITY`, `COMPLEXITY`), `state` (`PASS`, `FAIL`, `UNKNOWN`,
   `NOT_APPLICABLE`), optional measured value + unit, evaluator id/version, policy version, source
   (`TOOL`, `HUMAN`, `MODEL_JUDGE`, `IMPORTED`), evidence artifact references. `NOT_APPLICABLE` requires
   a justification. Each observation carries `policyId` and `policyVersion`. One event may carry at most
   one observation per dimension; a second one is a validation error before I/O.
3. **Retrieval feedback** (`FeedbackEvent`): unchanged; usefulness of a recalled atom for a query. It is
   never written automatically from outcomes or evidence.

Forge judges evidence; Store only applies the following structural selection rules (implemented with
the facade in `monada-api`, #97).

**Current outcome.** The `OUTCOME_RECORDED` event (after applying corrections) with the highest ledger
sequence. An `ACCEPTED` outcome must name `acceptedAttemptId`, which must exist in the execution.

**Applicable evidence.** For a dimension `d`, an observation is applicable only when:

1. it belongs to an `EVIDENCE_RECORDED` event of the accepted attempt;
2. its `policyId` and `policyVersion` equal the execution's evaluation policy from `EXECUTION_STARTED`
   (after corrections);
3. its event is the latest revision of its correction chain (a corrected event is replaced by its
   correction, never counted alongside it).

**Effective observation.** Among the applicable observations for `d`, the one whose effective event has
the highest ledger sequence (for a corrected event, the sequence of the correction). A later applicable
`FAIL` or `UNKNOWN` therefore overrides an earlier `PASS`, and a later `PASS` overrides an earlier `FAIL`.
Observations from another policy or another attempt are ignored for acceptance (but remain history).
Derivation uses every ledger event visible at the read point; there is no hidden cutoff.

Derived acceptance (computed, never stored as a separate fact):

| Derived status | Condition |
| --- | --- |
| `VALIDATED_ACCEPTED` | Current outcome `ACCEPTED` + origin `VALIDATED` + the accepted attempt finished `COMPLETED` + every mandatory dimension has an effective observation that is `PASS` or justified `NOT_APPLICABLE` |
| `ACCEPTED_UNVALIDATED` | Current outcome `ACCEPTED` but origin `IMPORTED_CLAIM`, or the accepted attempt is unfinished, `FAILED` or `CANCELLED` (even when mandatory evidence passes), or some mandatory dimension's effective observation is `FAIL` or `UNKNOWN`, or it has none; reasons list each dimension |
| `NOT_ACCEPTED` | Current outcome `REJECTED`, `FAILED` or `CANCELLED` |
| `PENDING` | No outcome or current outcome `PENDING` |

Normative cases (policy `forge-gates` v`1`, mandatory `TESTS`; outcome `ACCEPTED`, `VALIDATED`,
accepted attempt `a2`; sequences in brackets):

| Evidence on the ledger | Effective `TESTS` | Derived status |
| --- | --- | --- |
| `a2` PASS v1 [5] | PASS [5] | `VALIDATED_ACCEPTED` |
| `a2` PASS v1 [5], `a2` FAIL v1 [7] | FAIL [7] | `ACCEPTED_UNVALIDATED` (`TESTS`: FAIL) |
| `a2` FAIL v1 [5], `a2` PASS v1 [7] | PASS [7] | `VALIDATED_ACCEPTED` |
| `a2` PASS v1 [5], `a2` UNKNOWN v1 [7] | UNKNOWN [7] | `ACCEPTED_UNVALIDATED` (`TESTS`: UNKNOWN) |
| `a2` PASS **v2** [5] | none | `ACCEPTED_UNVALIDATED` (`TESTS`: missing, policy mismatch) |
| `a1` PASS v1 [3] only | none | `ACCEPTED_UNVALIDATED` (`TESTS`: missing on accepted attempt) |
| `a2` PASS v1 [5] corrected to FAIL [9] | FAIL [9] | `ACCEPTED_UNVALIDATED` (`TESTS`: FAIL) |
| `a2` NOT_APPLICABLE v1 with justification [5] | NOT_APPLICABLE [5] | `VALIDATED_ACCEPTED` |

- `FAILED` and `CANCELLED` never imply acceptance.
- Renewed evidence is a new event or revision; prior evidence remains history.
- No unified quality score is computed; each dimension is reported individually with provenance.

Usage is recorded through the facade (`monada-api`, #97). The usage and cost calculators below are
pure, evaluation-side code in `com.monada.evaluation.execution` (#99); the production facade exposes no
calculator operation, and `monada-api` never depends on `monada-evaluation`.

`UsageCounter`: `kind` (`INPUT_TOKENS`, `CACHED_INPUT_TOKENS`, `OUTPUT_TOKENS`, `REASONING_TOKENS`,
`REQUESTS`, `TOOL_CALLS`), optional non-negative `long` value, provenance (`REPORTED`, `ESTIMATED`,
`UNKNOWN`), source. A counter with value `0` and provenance `REPORTED` or `ESTIMATED` is a **known zero**.
`UNKNOWN` has no value; an absent counter is **not measured**. Neither is ever treated as zero. A stage
carries at most one counter per kind.

`RouteDescriptor.billingMode`: `API_METERED`, `SUBSCRIPTION`, `LOCAL`, `UNKNOWN`.

`PriceSnapshot` (caller-supplied, versioned): id + version, provider, model, currency (ISO 4217),
effective date, source, assumptions text, and price lines. Each `PriceLine` has a counter kind, a
non-negative `BigDecimal` price, a positive integer `unitSize` (e.g. 1,000,000 tokens; zero or negative is
rejected) and an optional `includedIn` kind that declares overlap (e.g. `CACHED_INPUT_TOKENS` included in
`INPUT_TOKENS`). A snapshot must contain at least one price line and at most one line per kind; an
empty or duplicate-kind snapshot is invalid input and is rejected before any calculation. The billable
kinds of a stage are exactly the kinds that have a price line in the snapshot matching the stage route's
provider and model, so a matched snapshot always yields a non-empty set of billable kinds.

`STAGE_RECORDED.usageDeclaration` (validated before I/O):

- `MEASURED` (default): the stage consumed resources that must be measured; coverage rules below apply.
- `NO_BILLABLE_USAGE`: the caller explicitly declares that the stage consumed nothing billable (e.g. a
  local deterministic check). It requires a justification and must carry no usage counters; a stage
  with this declaration and any counter is rejected. Omitting counters never implies this declaration.

Billable quantities (non-overlapping):

- `billable(k) = value(k) − Σ value(j)` over priced kinds `j` declared `includedIn = k`.
- A negative billable quantity is inconsistent usage and is reported as `INCONSISTENT_USAGE`, never
  clamped to zero.

Cost rules:

- Line cost = `billable(k) × price(k) / unitSize(k)`. Multiplication is exact; the division uses
  `BigDecimal.divide` with scale 18 and `HALF_EVEN` (exact whenever `unitSize` is a power of ten).
- Totals sum unrounded line costs. The reported amount is rounded once, at the end, to scale 6 with
  `HALF_EVEN`; the unrounded total is kept alongside it.
- Worked example: 1,000 `INPUT_TOKENS` at USD 2 per 1,000,000 tokens → `1000 × 2 / 1000000` =
  **USD 0.002** (reported `0.002000`), not USD 2,000.
- Overlap example: `INPUT_TOKENS` 10,000 (USD 2 / 1M) and `CACHED_INPUT_TOKENS` 4,000 (USD 0.5 / 1M,
  `includedIn = INPUT_TOKENS`) → `6000 × 2 / 1M + 4000 × 0.5 / 1M` = USD 0.012 + 0.002 = **USD 0.014**.
- It is labelled hypothetical for every billing mode. **Subscription usage is never presented as API
  billing.** An actual billed amount is reported only when the caller records one, and separately.
- Amounts in different currencies are never summed; each currency is reported on its own.
- Stage effort (sum of stage durations) is reported separately from elapsed wall-clock time
  (first start to last end) so that parallel stages are not double counted.

`CostToAcceptedOutcome` covers every event in the execution's attempt chain (failed attempts, repairs,
reviews, retries, QA) up to and including the `OUTCOME_RECORDED` event that establishes
`VALIDATED_ACCEPTED`. Later events are reported separately as post-acceptance activity. Events are
deduplicated by `eventId`; a correction replaces its target, never adds to it.

The acceptance cutoff is the highest ledger sequence among every fact required for `VALIDATED_ACCEPTED`: the
current `ACCEPTED` outcome event, the effective `ATTEMPT_FINISHED(COMPLETED)` of the accepted attempt and the
effective mandatory observations (a corrected fact counts at its correction's sequence). `STAGE_RECORDED` events after that
sequence are post-acceptance activity.

Usage coverage is evaluated per `STAGE_RECORDED` event in the chain (after corrections), never per
counter list:

- A `MEASURED` stage is **covered** when it has a matching (non-empty) price snapshot and, for every
  billable kind of that snapshot, a counter with a value (known zero included) and no
  `INCONSISTENT_USAGE`.
- A `NO_BILLABLE_USAGE` stage is covered with cost zero, needs no snapshot, and is always listed as
  `DECLARED_NO_BILLABLE_USAGE(stage)` so the zero stays visibly declared rather than measured.
- Otherwise each gap is a reason: `MISSING_PRICE(stage)`, `MISSING_USAGE(stage, kind)`,
  `UNKNOWN_USAGE(stage, kind)` or `INCONSISTENT_USAGE(stage, kind)`.
- A chain with no `STAGE_RECORDED` events has no coverage at all (`NO_USAGE_RECORDED`).
- No rule may be satisfied vacuously: coverage is never inferred from an empty snapshot, an empty
  counter list or an empty stage list.

| Status | Condition |
| --- | --- |
| `AVAILABLE` | `VALIDATED_ACCEPTED`, at least one stage in the chain, and every stage covered |
| `PARTIAL` | `VALIDATED_ACCEPTED`, at least one stage, some stage not covered; reports the lower bound from covered lines plus every reason |
| `UNAVAILABLE` | No `VALIDATED_ACCEPTED` outcome (pending, rejected, failed, cancelled or unvalidated), or `NO_USAGE_RECORDED` |

Normative cases (validated accepted chain, snapshot pricing `INPUT_TOKENS` and `OUTPUT_TOKENS`):

| Chain usage | Status | Amount |
| --- | --- | --- |
| No `STAGE_RECORDED` events | `UNAVAILABLE` (`NO_USAGE_RECORDED`) | none — never 0 |
| Two stages; stage 2 has no counters | `PARTIAL` (`MISSING_USAGE(stage2, INPUT_TOKENS)`, `MISSING_USAGE(stage2, OUTPUT_TOKENS)`) | lower bound = stage 1 cost |
| One stage; `OUTPUT_TOKENS` provenance `UNKNOWN` | `PARTIAL` (`UNKNOWN_USAGE(stage1, OUTPUT_TOKENS)`) | lower bound = input cost |
| One stage; both counters `REPORTED` value 0 | `AVAILABLE` | 0.000000 (known zero) |
| One `MEASURED` stage, no counters, no matching snapshot | `PARTIAL` (`MISSING_PRICE(stage1)`) | lower bound 0.000000, labelled lower bound |
| Snapshot supplied with no price lines | Rejected as invalid input before calculation | none |
| One stage declared `NO_BILLABLE_USAGE` with justification, no counters | `AVAILABLE` (`DECLARED_NO_BILLABLE_USAGE(stage1)`) | 0.000000 (declared zero) |
| Stage declared `NO_BILLABLE_USAGE` but carrying a counter | Rejected at `record` before I/O | none |

Low cost never substitutes for validated quality: cost is only reported next to, never instead of, the
acceptance status and per-dimension evidence.

## 10. Comparability

Two executions are directly comparable only when all of these match exactly:
scope, task, `sourceRevision`, `contextFingerprint`, `constraintsFingerprint`, evaluation policy id and
version.

- Otherwise the comparison is `NOT_COMPARABLE` with one reason per mismatching field.
- Comparable pairs with `UNKNOWN`, `FAIL` or missing mandatory evidence, incomplete cost, mixed currencies
  or mixed pricing assumptions are `INCONCLUSIVE` for the affected dimension.
- Pareto diagnostics use only eligible, complete, commensurable measurements under caller-declared
  objectives, directions and tolerances. When tradeoffs remain there is no automatic winner.
- Results are observational evidence for that corpus, not causal or general model-superiority claims.
- Comparison lives in `monada-evaluation` only (#100, implemented: section 20).

## 11. Projections and Bounded Recall

Implemented in #98; section 18 records the implemented details (experience unit, checkpoint format,
retired atoms, status rules and limits).

- Each scope has its own projection store (`projection-<n>/memory/`), a standard `MonadaMemory` opened with
  `MonadaMemoryOptions.defaults()` (exact query keys, existing encoder and ranker). Scope selection
  happens before ranking, so top-K never mixes scopes.
- Projection text is built from caller-approved summaries (task, solution, lesson) in a fixed canonical
  order. IDs, costs, gate states and route identifiers must not influence similarity.
- `projection-checkpoint.log` maps each projected atom ID to the exact `ExperienceRef`s it represents and
  records the last ledger sequence projected. Identical summary text from distinct attempts produces one
  atom but several distinct refs; `KnowledgeAtom.content` and atom ID rules are unchanged.
- The ledger is written first. If the projection update fails, `record` still reports `APPENDED`, the
  projection status becomes `STALE`, and the event is never retried as a new event.
- `projectionStatus(scope)` reports `CURRENT`, `STALE`, `MISSING` or `INCOMPATIBLE` with the ledger
  sequence covered. `rebuildProjection(scope)` is explicit and deterministic from the ledger; incompatible
  projection stores are never rewritten automatically.

`recall(scope, query, limit)`:

- `limit` must be 1–50.
- A blank query returns no hits.
- Total experience order: similarity descending, then atom ID ascending (the existing ranker's tie
  order), then canonical `ExperienceRef` string ascending.
- Algorithm: run the existing `MonadaQuery` with `topK = limit` (unchanged ranker behavior), expand each
  returned atom to its refs from the checkpoint, sort by the total order, and truncate to `limit`.
  Because every projected atom maps to at least one ref and the order is atom-major, the first `limit`
  experiences always come from the first `limit` atoms, so truncating atoms first never changes the
  result. An atom without refs makes the projection `INCOMPATIBLE` and is reported, never skipped.
- Validation cases: (a) atoms A and B tie, `A < B` by atom ID, A → ref `z`, B → ref `a`, `limit = 1`
  → returns A/`z`; (b) one atom with refs `r1 < r2`, `limit = 1` → returns `r1`; `limit = 2` → `r1`, `r2`.
- Each `ExperienceHit` carries the ref, similarity, projection version and covered ledger sequence.
- Similarity is resonance similarity, never quality or confidence. Missing refs are reported, never
  silently dropped. Projections hold no retrieval feedback: there is no feedback
  operation on projections, the projection feedback log must stay empty (section 18.3), and outcomes never
  become feedback.

## 12. History and Export

`history(scope, cursor, pageSize)`:

- `pageSize` must be 1–500.
- Events are returned in ledger sequence order, including every revision.
- Snapshot pagination: a call without a cursor starts a new snapshot whose high-watermark is the scope's
  last valid ledger sequence at that moment. Every page of that snapshot contains only events with
  `sequence ≤ highWatermark`.
- The cursor is an opaque, stateless token: format version, scope, high-watermark and last returned
  sequence. The same cursor always returns the same page, even if events were appended meanwhile; a
  cursor for another scope, or with a high-watermark beyond the ledger, is rejected.
- `hasMore` is false when the last returned sequence equals the high-watermark. Events appended later are
  read by starting a new snapshot (no cursor).
- Example (`pageSize = 2`): ledger has 1–3 → first page `[1, 2]`, cursor `(hw=3, last=2)`. Event 4 is
  appended. The cursor returns `[3]` with `hasMore = false`, and returns `[3]` again if reused. A new
  call without a cursor returns `[1, 2]` with `hw=4`.

`exportSamples(ExportRequest)` is read-only and versioned (`execution-sample/1`; implemented in #101, see section 21 for
the exact shapes and the clarifications):

- The request names an explicit scope, a cutoff (ledger sequence or `recordedAt`), page size (1–500),
  cursor, evidence policy, and view (`ADAPTER_READY` or `DIAGNOSTIC`).
- One sample per execution at its latest revisions as of the cutoff. Each sample includes task/context
  descriptors, route descriptors, outcome, derived acceptance, per-dimension effective evidence, full-chain
  usage counters with provenance, exact refs and revisions, and the ledger checkpoint and projection
  version used. Cost and comparison annotations are added only by evaluation tooling in
  `com.monada.evaluation.execution` (#99–#101), never by `monada-api`.
- The export cursor follows the history snapshot rule, with the cutoff as the high-watermark.
- Negative and incomplete executions are exported with eligibility and missing-evidence reasons. The
  `ADAPTER_READY` view never labels `UNKNOWN` mandatory gates as success.
- Only caller-approved summaries, typed observations and artifact references are exported; no raw
  transcripts or secret blobs. There is no hidden persistent export cursor and no automatic `feedback()`.

## 13. Compatibility

| Surface | Current behavior | v1 execution memory |
| --- | --- | --- |
| Atom log (`atoms/segment-000001.log`) | 6 tab-separated fields written; legacy 5-field lines read; last-wins for repeated IDs | Unchanged |
| `KnowledgeAtom.metadata` | `FileAtomStore` rejects non-empty metadata | Unchanged; execution identity is not stored in atom metadata |
| Atom ID | `UUID.nameUUIDFromBytes(content UTF-8)` | Unchanged; execution identity lives in the ledger |
| Manifest `0.4` | Encoding + physical vector profile required and validated | Unchanged; projection stores are created as `0.4` |
| Manifest `0.3` | Encoding profile required; fixed-frame layout inferred | Unchanged; opened as before |
| Manifest `0.1`–`0.2` | Legacy encoding safeguards; fixed-frame layout inferred | Unchanged; opened as before |
| Unknown manifest version / partial metadata | Rejected; explicit `VectorRebuilder` only | Unchanged |
| `EncodingProfile` / `VectorFormatProfile` / `vector-map.idx` | Exact-match validation, FLOAT32 big-endian | Unchanged; reused by projections |
| Feedback log (JSON lines) | `queryKey` defaults to `query` when missing | Unchanged; never written from outcomes |
| `ExactQueryKeyStrategy`, feedback-aware ranking | Defaults | Unchanged defaults; projections use them |
| `MonadaMemory` / `MonadaQuery` public API | Current signatures | Unchanged; execution API is a separate facade |
| Protected baselines (text `default-databases-v1`, speech `PROTECTED`) | CI-enforced | Unchanged; #102 adds no protected baseline: its correctness/isolation gates are enforced by tests in `./gradlew test`, not by a versioned PROTECTED snapshot, and its retrieval quality stays EXPLORATORY (section 22) |
| Speech layout `.monada-speech/` | Separate | Unchanged |

Old vectors stay comparable because projections use the same encoder and options. Existing stores are
reused as-is; the execution ledger is additive and invisible to legacy open paths. A future ledger
version must be rejected by v1 readers rather than reinterpreted.

## 14. Implementation Sequence

| # | Deliverable | Modules | Depends on |
| --- | --- | --- | --- |
| #93 | ADR 0004 + this contract | docs | — |
| #94 | Consumable Java 27 Maven publications + isolated consumer smoke test | build | #93 |
| #95 | Immutable execution/evidence domain records (**implemented**, section 15) | `monada-core` | #93 |
| #96 | Versioned append-only ledger, replay, integrity diagnostics | `monada-storage` | #93, #95 |
| #97 | `ExecutionMemory` facade: idempotent recording, revisions, history | `monada-api` | #94, #95, #96 |
| #98 | Scoped projections and bounded recall | `monada-api` | #97 |
| #99 | Attempt-chain usage and hypothetical `CostToAcceptedOutcome` calculators (**implemented**, section 19) | `monada-evaluation` | #95, #97 |
| #100 | Evidence-gated pairwise comparison and Pareto diagnostics (**implemented**, section 20) | `monada-evaluation` | #99 |
| #101 | Bounded provenance-preserving sample export (+ evaluation annotations) (**implemented**, section 21) | `monada-api`, `monada-evaluation` | #97, #98, #99, #100 |
| #102 | Reproducible end-to-end Forge experience evaluation and isolation gates (**implemented**, section 22) | `monada-evaluation` | #94, #96–#101 |

Consumer integration inside Monada Forge and Monada Neuron is future cross-repository work and is not
part of this track.

## 15. Domain Records (implemented in #95)

Package `com.monada.core.execution` in `monada-core`. The records are additive: no existing type,
signature, default or storage format changes, and no retrieval metric is affected. They validate a
single event in isolation; ledger-dependent checks (references to existing executions/attempts,
idempotency, conflicts, `CORRECTION` targeting the latest revision) belong to #96/#97.

| Type | Purpose / enforced rules |
| --- | --- |
| `ScopeId`, `TaskId`, `ExecutionId`, `AttemptId`, `EventId` | Distinct opaque ID types, `of(String)`. 1-128 code points, no control characters, no leading/trailing space character (section 1), no unpaired surrogates, not re-normalized, exact `String.equals`. Never generated by the library. |
| `EventEnvelope` | Event/scope/task/execution IDs, optional attempt, `revision` (>= 1), optional `supersedes`, `occurredAt`, `recordedAt`. `supersedes` is present exactly when `revision > 1`, never the event itself; `recordedAt` is not before `occurredAt`. |
| `ExecutionEvent` (sealed) | `ExecutionStarted`, `AttemptStarted`, `StageRecorded`, `EvidenceRecorded`, `AttemptFinished`, `OutcomeRecorded`, `Correction`. Attempt ID is absent only for `ExecutionStarted` and `OutcomeRecorded`. Only `Correction` may have revision > 1; it wraps a full replacement of an original-shaped event addressing the same event, execution, attempt and times. |
| `AttemptStarted` | `INITIAL` has ordinal 1 and no previous attempt, other reasons have ordinal >= 2 and require one; an attempt cannot follow itself. |
| `StageRecorded` | `startedAt <= endedAt <= recordedAt`; `RouteDescriptor` (absent provider/model/effort are explicit `Optional.empty()`, `BillingMode.UNKNOWN` when unknown); at most one `UsageCounter` per kind; `NO_BILLABLE_USAGE` requires a justification and no counters, `MEASURED` forbids a justification. Empty counters mean "not measured", never zero. |
| `EvidenceRecorded` | 1-64 `QualityObservation`s, at most one per dimension. Event-level artifacts plus every observation's evidence references together stay within the 64-reference event limit. |
| `QualityObservation` | Dimension, state (`PASS`/`FAIL`/`UNKNOWN`/`NOT_APPLICABLE`), evaluator id/version, policy id/version, source, evidence `ArtifactRef`s. `NOT_APPLICABLE` requires a justification. An optional measured value must be finite, non-negative, carry a unit, and is allowed in any state. It never yields a score or routing decision. |
| `UsageCounter` | `REPORTED`/`ESTIMATED` require a non-negative value (0 is a known zero); `UNKNOWN` carries no value. |
| `PriceSnapshot`, `PriceLine` | Caller-supplied: at least one line, one line per kind, price >= 0, `unitSize > 0`, ISO 4217 currency (`PriceSnapshot.currency(String)`), `includedIn` must name another priced kind and the inclusion chain must be acyclic. No calculation lives here (#99). |
| `Outcome` | `ACCEPTED` requires an accepted attempt; every other status forbids one. Origin is `VALIDATED` or `IMPORTED_CLAIM`. |
| `EvaluationPolicy`, `SourceProvenance`, `ArtifactRef`, `ExperienceRef` | Opaque descriptors with bounded text; `ArtifactRef` digest is optional; a policy declares at least one mandatory dimension. |

Limits (`ExecutionLimits`): identifier 128 code points; summaries 4,096 code points (non-blank,
well-formed Unicode, newlines allowed); artifact `reference` and other opaque descriptors 512 code points; at most 64 artifact
references, quality observations and usage counters per event. `MAX_RECORD_BYTES = 65,536` is exposed
for the storage layer (#96), which also enforces it because it depends on the payload codec.

Collections are copied defensively (`List.copyOf`/`Set.copyOf`); caller mutation after construction has
no effect and exposed collections are unmodifiable. Records are deterministic value objects: no
`Instant.now()`, no randomness, no I/O. `Set` iteration order is unspecified, so the ledger codec must
write sets (for example mandatory dimensions) in enum ordinal order.

### Examples

Two comparable executions of the same task text (same scope, task, `sourceRevision`, context and
constraints fingerprints, and policy `forge-gates` v`1`) with distinct identities:

| Execution | Attempts and evidence |
| --- | --- |
| `exec-direct` | `a1`: stage with reported usage, `TESTS` `PASS`, finished `COMPLETED`; outcome `ACCEPTED`/`VALIDATED` for `a1` |
| `exec-repaired` | `a1`: `TESTS` `FAIL`, finished `FAILED`; `a2` (`REPAIR`, previous `a1`): reported input and estimated output usage, `TESTS` `PASS`; outcome `ACCEPTED`/`VALIDATED` for `a2` |

Incomplete and cancelled evidence stays distinguishable from zero and pass:

- an incomplete execution has a stage whose `OUTPUT_TOKENS` is `UNKNOWN`, no `INPUT_TOKENS` counter at
  all (not measured), a `TESTS` observation `UNKNOWN`, and neither `ATTEMPT_FINISHED` nor
  `OUTCOME_RECORDED` (replayed later as `IN_PROGRESS`/`PENDING`);
- a cancelled execution has `ATTEMPT_FINISHED` with `CANCELLED` and `OUTCOME_RECORDED` with
  `CANCELLED`, which names no accepted attempt.

These scenarios are executable in `ExecutionExperienceExamplesTest`.

### Verification (issue #95)

~~~bash
./gradlew :monada-core:test
./gradlew test
git diff --stat origin/main
~~~

Future consumer integration in Monada Forge and Monada Neuron remains future cross-repository work.

## 16. Execution Ledger (implemented in #96)

Package `com.monada.storage.execution` (`monada-storage`). Scope: one writable ledger per scope with
append, exact lookup, deterministic replay and read-only audit. The `ExecutionMemory` facade was added
afterwards by #97 (section 17). Out of scope for #96 (projections, recall and export were added later by #98 and #101,
sections 18 and 21) and still future work: segment rollover, compaction and explicit repair.

### 16.1 Public API

| Type | Role |
| --- | --- |
| `ExecutionLedger.open(root, scope)` | Writable owner of one scope: `append(event)`, `find(eventId)`, `replay()`, `view()`, `close()`. |
| `AppendResult` | `APPENDED` (+ sequence), `IDEMPOTENT` (+ original sequence) or `CONFLICT` (+ reason). |
| `ExecutionLedgerReader.open(root, scope)` | Read-only snapshot: `replay()`, `find`, `view()`, `diagnostics()`, `hasTornTail()`. |
| `ExecutionReplay` | Sequence-ordered view; an execution with no outcome or a current `PENDING` outcome is `pending`, an attempt without finish is `in progress`. |
| `LedgerDiagnostic` / `LedgerDiagnosticCategory` | File, 1-based line, sequence (if parseable), category, message. |
| `LedgerException`, `LedgerLockedException`, `LedgerRejectedException` | Open/lock failures and invalid events (the latter is thrown before any I/O). |
| `ExecutionLedgerAuditor` | Read-only audit of a whole ledger root (`ExecutionLedgerAuditReport`, `ExecutionLedgerAuditMain`). The reader's audit helpers are package-private. |

A ledger instance is bound to one scope. A corrected `ATTEMPT_STARTED` keeps the effective attempt
ordinals of the execution unique and its `previousAttempt` chain acyclic (a link that would close a loop is
rejected as `INVALID_ORDER`) (a clash is a `CONFLICT`, and the ordinal it replaced becomes free). Appending an event of another scope is rejected
(`SCOPE_ID_MISMATCH`). Opening writable creates the root, the manifest and that scope's directories only.

### 16.2 Payload codec (schema 1)

Tokens joined by `|`, in the field order of section 3 (envelope), followed by the kind payload. Text
tokens escape `\\`, `|` (`\p`), TAB (`\t`), LF (`\n`), CR (`\r`) and other ISO control characters
(`\xHHHH`); everything else is written as UTF-8. An optional value is the empty token when absent and `~` plus
the escaped value when present. Lists are a count token followed by their elements; enum sets (mandatory
dimensions) are written in ordinal order. `Instant` uses ISO-8601 (`Instant.toString`), numbers use
`Long.toString` / `Double.toString`. A `CORRECTION` writes the outer envelope, its justification, the
replacement kind and the replacement payload. Decoding is strict: missing, extra, unknown or
non-canonical tokens are `MALFORMED_RECORD` (the decoder re-encodes and compares bytes), and a schema
version other than `1` is `UNSUPPORTED_SCHEMA`. No JSON library or other dependency was added; the
manifest is a fixed-shape JSON object checked by a strict pattern.

Example (one line; `<TAB>` is a tab; length and digest are illustrative):

```text
MXL1<TAB>1<TAB>187<TAB>2f1c...<TAB>1|EXECUTION_STARTED|e1|scope-1|task-1|exec-1||1||2026-01-01T00:00:00Z|2026-01-01T00:00:00Z|Fix the bug|rev-1|ctx-1|constraints-1|policy|2|2|CORRECTNESS|TESTS
```

### 16.3 Limits

- A whole line, including the LF, is at most 65,536 bytes. Larger appends are rejected before I/O
  (`OVERSIZED_RECORD`); larger lines on disk are reported, never truncated; an unterminated final record already beyond the limit is
  reported as `OVERSIZED_RECORD` (error) in addition to `TORN_TAIL` (warning). Lines are streamed, never loaded whole.
- Field limits are those of section 3 and `ExecutionLimits` (enforced by the domain records).

### 16.4 Write semantics, ownership and durability

- `append` checks scope, encodes, then (in this order): identical payload for the same event ID is
  `IDEMPOTENT` (file unchanged); a different payload for that ID, an identity clash (execution or attempt
  started twice, reused attempt ordinal) or a correction of a non-latest revision is `CONFLICT`; a missing
  reference, an event after `ATTEMPT_FINISHED` (other than a correction), a second finish, a correction
  that changes the event kind, or an oversized record is rejected with `LedgerRejectedException`
  (`DANGLING_REFERENCE`, `INVALID_ORDER`, `OVERSIZED_RECORD`). Nothing is written in any of these cases.
- Exclusive ownership is an OS file lock on `<root>/write.lock`. A second writable open fails at once with
  `LedgerLockedException`; it never waits. The lock is released by `close()` (idempotent) and when `open`
  fails. After `close()` every operation, including `scope()`, throws `IllegalStateException`.
- `APPENDED` is returned only after the full line is written and `FileChannel.force(true)` returned. This
  survives a process crash. It is **not** a transactional guarantee against operating-system or hardware
  failure, the parent directory is not fsynced, and network file systems are unsupported. If a write fails
  the instance becomes unusable (a partial line may exist); the next writable open reports `TORN_TAIL`.
- A writable open validates the whole segment and refuses any corruption or torn tail
  (`LedgerException` with diagnostics). The ledger never truncates, skips or repairs.

### 16.5 Paths

Scope directories are `s-<sha256hex(UTF-8 scope ID)>`; raw identifiers are never path segments. Every path
(including `scopes/` itself, before it is listed) is resolved through real paths and must stay inside the
real ledger root; a symbolic link that leaves the
root fails closed (`PATH_ESCAPE`). `scope.id` must equal the requested scope (`SCOPE_ID_MISMATCH`). A root that does not exist yet is built under a private sibling name (with its manifest) and renamed into
place, so a concurrent reader never sees a root without a manifest (an already existing directory cannot be
published atomically and keeps the regular path). A manifest that is present but not a regular file is
`MANIFEST_INVALID`, never `MANIFEST_MISSING`. While checking for a displaced scope, an entry that escapes
the root fails closed (`PATH_ESCAPE`) instead of being ignored. The initial `execution-manifest.json` of an
existing empty root is likewise written to a temporary name and renamed atomically. Metadata,
segment and lock files must be regular files (a named pipe is rejected without being opened, so nothing can
hang). A scope whose canonical directory is absent
but whose `scope.id` is found in another `s-<hash>` directory (a renamed or displaced scope) is rejected by
readers and by writable open (`SCOPE_ID_MISMATCH`); it is never treated as new, so sequences cannot restart
and event ids cannot be reused over hidden history. A new
scope is built under `scopes/.staging-s-<hash>` (with `scope.id`, `ledger/` and an empty `events-000001.log`) and published with one atomic
rename, so a concurrent reader never sees a partial scope; a scope directory without `scope.id` or `ledger/`
is damage, reported by readers and the audit; so is a scope whose segment file has disappeared (the ledger
never restarts a history over it, and writable open refuses it). A leftover staging directory is discarded by the next writer
(it holds the lock). A root that holds a legacy `manifest.json` but no `execution-manifest.json` is rejected
on writable open, and so is a root with `scopes/` but no manifest (`MANIFEST_MISSING`): a manifest is only
created for a genuinely new root. `execution-manifest.json` (1 KiB) and `scope.id` (512 bytes) are read with a
size bound and decoded as strict UTF-8 (malformed bytes are never replaced by U+FFFD); larger or malformed files are `MANIFEST_INVALID` / `SCOPE_ID_MISMATCH`. The check
is not atomic with later use (no defence against a concurrent hostile local process).

### 16.6 Diagnostics and audit

Categories are those of section 6 plus `INVALID_ORDER` (ordering rule violated inside the file) and
`PATH_ESCAPE`. Within a scope, records are accepted only while every earlier line is valid, so replay
returns the longest valid prefix; later lines are still examined for framing, digest, sequence and schema
problems, and a line with a sequence error, including a malformed or non-canonical sequence field, is still checked
for its other defects, as is a line with a wrong payload length or digest (all are reported, none enters
replay); only unusable framing (field count, codec
marker, oversized line) ends the examination of that line. A `scopes` path that is not a directory is structural damage for readers too, never an
empty ledger; `diagnostics()` is an immutable view. A `TORN_TAIL` diagnostic carries the sequence when the incomplete record's sequence field is complete and
canonical. Diagnostics are sorted (severity, file, line, category, message) and so are reproducible.

The read-only audit (`ExecutionLedgerAuditor`) validates the manifest directly (it opens no scope, so no scope id is special) and every `s-<hash>`
scope directory it discovers (containment is checked before any child is read, `scope.id` must hash to the
directory name, `ledger/` must exist, and that same directory is scanned record by record, even when its name does not match the hash: the
`SCOPE_ID_MISMATCH` is reported together with every other defect found). A damaged `scope.id` (missing,
oversized, malformed, invalid) is reported the same way and the ledger is still scanned, with only the
per-record scope comparison skipped. The audit also checks that
`write.lock`, when present, is a contained regular file, without creating or acquiring it. It never creates, locks, truncates or
repairs anything, and reports a missing root or manifest as diagnostics. Unexpected entries under `scopes/`
or `ledger/` (for example a reserved `events-000002.log`) are warnings.

```bash
./gradlew :monada-storage:runExecutionLedgerAudit -Dmonada.execution.dir=/path/to/ledger
```

The task prints the report and exits 1 when any error exists (torn tails and unexpected entries are
warnings). The task is registered in `monada-storage/build.gradle.kts`.

### 16.7 Compatibility

The ledger uses its own manifest and directory and does not read or write legacy atom, vector, feedback or
`manifest.json` files. Unknown manifest format, version, record codec, scope directory scheme or payload
schema is rejected, never reinterpreted (section 13).

### 16.8 Verification (issue #96)

~~~bash
./gradlew :monada-storage:test
./gradlew test
./gradlew :monada-storage:runExecutionLedgerAudit -Dmonada.execution.dir=<ledger>
~~~

Tests live in `com.monada.storage.execution`: round trip across reopen, idempotency and conflicts, reference
and order rules, locking and release, torn tail, interior corruption, unknown versions and schemas, size
limits, Unicode and escapes, traversal and symlink escapes, and byte-identical files after audit.

Observed limitations: checking whether a scope was displaced (renamed) reads the `scope.id` of every other scope
each time a scope is treated as new or absent, so it is linear in the number of scopes per open (not per append);
the held segment file is compared with the path on every append, and a vanished or replaced segment fails the
writer instead of being recreated; a root created by `open` gets the default directory permissions; manifests
of a newer version are reported as `UNSUPPORTED_VERSION` whatever their shape; single segment, whole-scope validation on every writable open (linear in ledger
size), the in-memory event index grows with the scope, and no repair tool exists yet.

## Implementation Boundaries

- New code only in the packages listed in ADR 0004; no changes to existing public signatures or defaults.
- Experimental behavior stays behind explicit options; quality evidence stays separate from protected
  correctness gates.
- Stable Java 27 features only; no preview or incubator flags; no incidental dependency upgrades.

## 17. ExecutionMemory Facade (issue #97)

Package `com.monada.api.execution` in `monada-api`. As shipped in #97 it was ledger-backed only (projections
and recall were added by #98, section 18): no cost calculators, comparison or export, and no mapping of
outcomes to ranking feedback.
`MonadaMemory` and its lifecycle are unchanged.

### 17.1 Ownership and threading

- `ExecutionMemory.open(root, scope[, config])` binds **one scope per instance** and takes the exclusive
  writer lock of `root` (the ledger lock is per root). A second open fails immediately with the unchecked
  `ExecutionMemoryLockedException`. Reading other scopes of the same root needs a separate instance after
  close; a multi-scope writer is future work.
- `ExecutionMemory.openReadOnly(root, scope[, config])` returns a **snapshot** of the valid ledger prefix at
  open time: no lock, nothing created, usable next to a live writer. Later appends are not visible; the host
  opens a new read-only instance to see them. `record` throws `UnsupportedOperationException`. A missing root
  or manifest fails with `UncheckedIOException`, and so does a ledger with integrity errors (the writable
  open refuses it too), so a damaged history is never served silently truncated. A torn tail (possibly a
  writer mid-append) is tolerated and reported by `hasTornTail()`. A missing scope is an empty history.
  Lifecycle rules are the same as for the writable instance.
- Operations keep the contract's `ScopeId` parameter; a scope other than the opened one throws
  `IllegalArgumentException`.
- Single writer, **not thread-safe**: the host serializes calls. `close()` is idempotent and releases the
  lock; every other call afterwards throws `IllegalStateException`. No hidden globals, threads or clock reads.
- I/O and corruption failures throw `UncheckedIOException`.

### 17.2 Operations

| Operation | Result |
| --- | --- |
| `record(ExecutionEvent)` | `RecordResult(APPENDED \| IDEMPOTENT \| CONFLICT, sequence, reason)` |
| `loadExecution(scope, execution)` | `Optional<ExecutionView>` (provenance, policy, current outcome with origin, `DerivedAcceptance`, attempts, full history) |
| `loadAttempt(scope, execution, attempt)` | `Optional<AttemptView>` (route, usage, evidence exactly as recorded) |
| `loadEvent(scope, eventId[, revision])` | `Optional<HistoryEntry>` (sequence + event); `eventId` may name any member of a correction chain; revision 1 is the original |
| `history(scope[, cursor], pageSize)` | `HistoryPage(entries, highWatermark, hasMore, next)` |
| `openReadOnly(root, scope[, config])` | Lock-free snapshot instance; `record` is unsupported; `hasTornTail()` |

Outcomes of `record`: identical retry -> `IDEMPOTENT` (nothing written); same id with a different payload,
duplicate execution/attempt start, reused ordinal, or a correction that is not target revision + 1 or not of
the latest revision -> `CONFLICT` (nothing written). Wrong scope, missing references, events after
`ATTEMPT_FINISHED`, and oversized records throw `IllegalArgumentException` before any I/O. The lifecycle check
comes first (`IllegalStateException` after close, even for a null argument); a null argument on an open
instance throws `NullPointerException`.

### 17.3 Acceptance, revisions, history

- `ExecutionView.acceptance()` is derived on every read (section 8), never stored. `VALIDATED_ACCEPTED`
  requires a `VALIDATED` accepted outcome whose accepted attempt finished `COMPLETED`, and passing (or justified not-applicable) evidence of the accepted
  attempt under the execution's own policy id and version for every mandatory dimension. An
  `IMPORTED_CLAIM` outcome stays visible as such and is never validated. `FAILED`, `REJECTED` and `CANCELLED`
  are `NOT_ACCEPTED`.
- A correction appends a new revision; earlier lines are never rewritten and stay in `history`. Views show
  the latest revision; usage is exposed as recorded and each fact is replayed once, so a revision replay
  never charges cost a second time.
- `history` pages are 1..`maxPageSize` (default max 500, default page 100; there is no unbounded read), in
  ascending sequence including every revision. `HistoryCursor` is an opaque token (version, scope,
  high-watermark, last sequence): the same cursor always returns the same page even after later appends;
  a foreign-scope or beyond-ledger cursor is rejected.

### 17.4 Usage

```java
ScopeId scope = ScopeId.of("project-a");
try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
    RecordResult r = memory.record(ExecutionEvent.executionStarted(EventId.of("ev-1"), scope, taskId,
            executionId, provenance, policy, "Fix the bug", Instant.parse("2026-01-01T00:00:00Z")));
    ExecutionView view = memory.loadExecution(scope, executionId).orElseThrow();
    HistoryPage page = memory.history(scope, null, 50);
}
```

### 17.5 Compatibility and verification

No persisted format change and no change to existing API signatures or defaults. Verification:
`./gradlew :monada-api:test`, `./gradlew test`, `./gradlew verifyLibraryConsumer` (class-path and JPMS
consumers record, retry, close, reopen and load execution history).

## 18. Projections and Bounded Recall (issue #98)

Implements section 11. New code: `com.monada.api.execution` (`ProjectionStatus`, `ExperienceRecall`,
`ExperienceHit`, additive `ExecutionMemory` operations) and, in `com.monada.storage.execution`, the
projection layout and checkpoint (`ProjectionLayout`, `ProjectionCheckpoint`, `ExperienceRefCodec`).
`MonadaMemory`, `KnowledgeAtom`, atom/vector/feedback formats and ranking/query-key defaults are unchanged.

### 18.1 Experience unit and projection text

- One experience is one `ATTEMPT_FINISHED` fact at its latest revision, whatever its `AttemptResult`.
- Its `ExperienceRef` is `(scope, task, execution, attempt, eventId, revision)` of the exact ledger event:
  the original finish (revision 1) or the `CORRECTION` that replaced it (its own event ID and revision).
- Projection text, scheme `summary-text-v1`: the execution's latest task summary, then the solution summary
  and the lesson summary when present, joined by `\n`. Identifiers, outcomes, gate states, usage, costs,
  routes and artifacts are never part of the text. Each part is bounded by the 4096 code point summary limit.
- The identity envelope is kept out of the text on purpose (section 11): identical summaries from distinct
  attempts produce **one atom with several refs**, and the refs live only in the checkpoint.
- A correction of the finish, or of the execution's task summary, retires the old atom/ref pair and projects
  the new one. An atom left without refs is a **retired atom**: recorded explicitly, never returned, and not
  counted against `limit` (recall pads the ranker's top-K by the retired atoms still stored). A rebuild
  compacts them: the checkpoint still records the full history, but the new memory stores only the atoms
  live at the end.

### 18.2 Checkpoint format (`projection-checkpoint.log`, version 1)

Line framing `MXP1 TAB lineNumber TAB payloadBytes TAB sha256hex TAB payload LF` (max 65,536 bytes per
line). Payload tokens are `|`-separated and escaped like ledger payloads:

| Payload | Meaning |
| --- | --- |
| `HEADER\|1\|summary-text-v1\|<dims>\|<scope>` | Line 1: projection manifest (format version, text scheme, vector dimension, scope). Format 1 fixes the dimension at 128 (the production default); any other value is rejected |
| `PROJECT\|<seq>\|<atomId>\|<ref>` | The atom now represents the ref |
| `RETIRE\|<seq>\|<atomId>\|<ref>` | The atom no longer represents the ref |
| `COVER\|<seq>\|<sha256>` | Ledger sequence `seq` is fully projected; digest of its ledger payload |

`<ref>` is the canonical ref `scope|task|execution|~attempt|eventId|revision` (`ExperienceRefCodec`), which
is also the last recall tie-break. Every ledger sequence gets exactly one `COVER`, in order from 1, streamed
after its entries through a bounded buffer and forced once; the projector hands entries to the checkpoint one
at a time (no per-event batch is materialized, however many attempts a correction touches); readers fold in two
streaming passes (find the
last `COVER`, then apply entries up to it immediately), so memory stays bounded for large batches. Entries after the last `COVER` (an interrupted batch) and a
torn final line are ignored and make the projection `STALE`; any other defect (digest, framing, version,
order, duplicate projection, unknown retirement) stops reading and makes it `INCOMPATIBLE`.

### 18.3 Status, writes and reconciliation

- `open` loads the projection and verifies it against the ledger by re-projecting the covered prefix:
  cover digests, mapping and retired atoms must match, and every live atom must exist in `memory/`
  (retired atoms may be absent). The published generation is the one named by `projection.current`, which
  must be a regular file holding exactly `projection-<n>` and a line feed; a malformed pointer or one naming
  a missing generation is `INCOMPATIBLE`. Verification is read-only and fail-closed: any symbolic link or
  special file under the generation is
  `INCOMPATIBLE` before anything is read through it; the fixed memory layout (`manifest.json`, atom log,
  vector segment, `vector-map.idx`, feedback log and their directories) must be complete, the manifest
  must name exactly the standard atom, vector and feedback segments and be a 0.4 manifest with the default
  encoding profile and current vector format at the format-1 projection dimension (128, a constant of the
  code, never trusted from the checkpoint header or the manifest; the header and manifest must both name it) (a legacy manifest would apply other query weights), the
  feedback log must be empty
  (projections hold no retrieval feedback, so default feedback-aware ranking is a no-op), and the
  side-effect-free `StorageIntegrityAuditor` must report no error (for example `ATOM_WITHOUT_VECTOR`) and
  no duplicate vector (`DUPLICATE_VECTOR_INDEX_ID`, `DUPLICATE_VECTOR_OFFSET`), all
  before `MonadaMemory` is opened, so diagnosing a damaged projection never recreates files. Finally the
  projection text of every stored atom the checkpoint knows (live, and retired atoms still stored, which a
  later correction could reactivate without rewriting) is re-encoded with the production pipeline (`WeightedAtomEncoder`
  and the manifest's encoder dimensions) and must equal its stored vector, so a structurally valid but
  altered frame is `INCOMPATIBLE`. It
  initializes a projection only for an **empty** ledger; a scope recorded before #98 is `MISSING`.
- `projectionStatus(scope)` returns `ProjectionStatus(state, coveredSequence, ledgerSequence,
  projectionVersion, diagnostics)`:
  `CURRENT` (covered = ledger), `STALE` (covered < ledger, or an interrupted checkpoint write), `MISSING`
  (no projection), `INCOMPATIBLE` (unsound checkpoint, foreign ledger digests, mapping that disagrees with the
  ledger, missing or incompatible `memory/`, unmapped atoms in a caught-up projection, or a recalled atom or
  ref that cannot be resolved).
- `record`: the ledger is appended first. When the projection is `CURRENT` the event is projected; if that
  fails, `record` still returns `APPENDED`, the projection becomes `STALE` and the event is never retried as
  a new event. While not `CURRENT`, appends are not projected; nothing catches up implicitly.
- `rebuildProjection(scope)` (writable only) is the single, explicit reconciliation path for every state:
  it re-projects the whole ledger into a new generation directory (`projection-<n+1>/`, a name never
  reused), stores only the final live atoms, and publishes it by atomically replacing `projection.current`.
  The scope is never without a published projection and no directory a reader may hold is moved. A failure
  before publication deletes the new generation and leaves the pointer. A pointer entry that is not a regular
  file (a dangling or escaping link, or a directory) is removed itself, never followed, and replaced, so a
  rebuild also repairs it. After publication the rebuild reclaims every other generation (read-only
  snapshots are frozen in memory and never read generation files after opening), including generations
  orphaned by an interrupted build.
- `pruneProjection(scope)` (writable only) performs the same reclamation without rebuilding: it deletes every
  generation except the published one (all of them when nothing is published), invalid entries and a
  leftover temporary pointer, typically to retry a cleanup that failed. With an unreadable pointer it deletes
  nothing and says to rebuild first.
  Generation entries are enumerated without resolving links: only real directories count as generations,
  numbering skips every name already present (links included; if numbers above the largest entry are
  exhausted, the smallest free number is used), and entries that are links or files are
  removed after publication (the link itself, never its target), so a rebuild recovers from them.
  Publication is the commit point: deleting generations or entries afterwards (in a rebuild or a prune) is
  best effort, so a cleanup failure never throws; it is reported in the diagnostics of the status that
  operation returns (not in later statuses) and retried by the next rebuild or `pruneProjection`.
  Incremental projection and rebuild share one projection function, so a rebuild of a current projection is
  byte-identical in `projection-checkpoint.log` and ranking-identical.
- `openReadOnly` creates nothing: it verifies an existing projection and reports `MISSING` otherwise;
  `rebuildProjection` and `pruneProjection` are unsupported. After verification the generation's atoms and
  vectors are loaded once into an immutable in-memory index, so the snapshot is truly frozen: its recall never
  reads a file again, and later appends, rebuilds, prunes or damage to the files never change its answers.
  Read-only verification uses existing-only readers (the integrity audit, a read-only atom-log ID reader and
  `FileFrequencyStore.openExisting`) and never `MonadaMemory.open` or a creating store constructor, so a
  generation reclaimed by a concurrent rebuild or prune mid-open fails the attempt with an I/O error instead
  of being partly recreated; the bounded open retry then takes a consistent snapshot or reports
  `INCOMPATIBLE` ("reopen to retry"). The frozen index is built from the live atoms' texts and their verified
  vectors (equal to the stored ones), so no file is needed after verification.
  It ranks with the same `LinearScanResonanceIndex` and the query steps of `MonadaQuery` with production
  defaults (feedback-aware wrapping is a no-op because the feedback log is verified empty); the recall
  regression gate and a writable-versus-read-only equality test guard this equivalence. Beside a live writer
  the open itself can observe a write in progress: if the projection looks `INCOMPATIBLE` or `MISSING` and a
  generation fingerprint of the scope (ledger segment size, pointer and every file size of the current
  generation) changed during the open, the snapshot is taken again, at most 3 attempts with no waiting;
  afterwards it stays `INCOMPATIBLE` with a "reopen to retry" diagnostic. Damage observed while nothing
  changes is reported on the first attempt.

### 18.4 Recall

`recall(scope, query, limit)` returns `ExperienceRecall(status, hits)`:

- `limit` 1–50 (`ExperienceRecall.MAX_LIMIT`), otherwise `IllegalArgumentException`; a null query throws
  `NullPointerException`; a blank query returns no hits.
- `MISSING` / `INCOMPATIBLE`: no hits, status explains why. `STALE`: hits from the covered prefix, each
  carrying that `coveredSequence`.
- Ranking is the existing `MonadaMemory` query with `MonadaMemoryOptions.defaults()` (exact query keys,
  default encoder, threshold 0.0); order is similarity descending, atom ID ascending, canonical ref
  ascending, truncated to `limit`.
- `ExperienceHit(ref, atomId, similarity, event, projectionVersion, coveredSequence)`: `event` is the
  exact ledger `HistoryEntry` for the ref's event ID and revision. `similarity` is resonance similarity, not
  quality or confidence. A ref missing from the ledger makes the projection `INCOMPATIBLE`; it is never
  skipped silently.

```java
ScopeId scope = ScopeId.of("project-a");
try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
    ExperienceRecall recall = memory.recall(scope, "flaky gradle cache test", 5);
    if (recall.status().state() != ProjectionStatus.State.CURRENT) {
        memory.rebuildProjection(scope); // explicit reconciliation
    }
    for (ExperienceHit hit : recall.hits()) {
        ExecutionView view = memory.loadExecution(scope, hit.ref().execution()).orElseThrow();
    }
}
```

### 18.5 Limits and compatibility

- One scope per instance, as in section 17; another scope is never loaded, so it cannot consume top-K
  slots. There is no cross-scope search.
- The default threshold 0.0 is kept, so with fewer relevant experiences than `limit` the tail can contain
  zero-similarity hits; callers filter by similarity if they need to.
- `MonadaMemory.remember` stamps `createdAt` from the clock (unchanged legacy behavior), so atom log bytes
  differ between rebuilds; ranking, scores and the checkpoint do not.
- Retrieval feedback on projection atoms is not exposed; outcomes never become feedback.
- Projection verification on open re-projects the covered ledger in memory (linear in the scope size).
- Incremental projection writes call `MonadaMemory.remember` only for atoms not already stored. A rebuild
  stores each final live atom once through `MonadaMemory.rememberAll` (issue #111). `FileAtomStore` keeps an
  incremental view of the atom log: each lookup parses only the bytes appended since the previous one (records
  appended by another writer are picked up; a shrunk log is reloaded), so each record is parsed once per store
  instance. Both `remember` and `rememberAll` are therefore linear in the number of atoms written, and a full
  rebuild of N distinct live texts is linear in N. The log format, atom IDs, vector bytes, ranking and
  `projection-checkpoint.log` are unchanged.
- A read-only snapshot holds its generation's atoms and vectors in memory for its lifetime (proportional to
  the scope's projected atoms); hosts close snapshots they no longer need.
- Existing ledgers stay valid: a pre-#98 scope opens as `MISSING` until rebuilt. Legacy text stores,
  baselines and production defaults are untouched.

Verification: `./gradlew :monada-storage:test`, `./gradlew :monada-api:test`,
`./gradlew :monada-evaluation:test`, `./gradlew test`, `./gradlew verifyLibraryConsumer`. Per-query top-K
evidence for the isolation fixture comes from `ExperienceRecallIsolationReportTest`, which is also the
recall regression gate: it compares every query's top-K refs and similarities with an embedded protected
baseline, prints a baseline-vs-candidate table and fails on any reordering, changed similarity, cross-scope hit
or drop in the protected aggregate metrics (Hit@1, MRR, Recall@3 over annotated queries).

### 18.6 Interruption windows and recovery (issue #127)

Clarification only: this section restates the behavior of sections 16–18 for each window in which an
operation can be interrupted between the ledger and its projection. It changes no format, status or default.
In every window the ledger is the authority. A fact the ledger accepted keeps its sequence. A projection
failure never rolls back or duplicates that fact. A published generation stays valid until a rebuild
replaces it. Nothing is repaired, retried or rebuilt implicitly.

| Window | Observed state | Exact recovery | Tests |
| --- | --- | --- | --- |
| W1a: append rejected before I/O (validation, conflict, foreign scope) | no write. `CONFLICT`, or an exception for an invalid event. Projection unchanged | none: the caller fixes the event | `ExecutionMemoryRecordOutcomesTest`, `ExecutionLedgerTest` |
| W1b: append refused by I/O because the segment was replaced (same contents, new file) on a file system that reports file keys | `record` throws `UncheckedIOException`. No fact is added. The projection stays `CURRENT` at the previous sequence. The instance refuses further records (`IllegalStateException`) | close and reopen. A retry is then a normal `APPENDED`. Where `fileKey()` is `null` the replacement check is skipped, so a replaced segment is not detected there (and the test is skipped) | `LedgerHardeningTest`, `CrossLayerInterruptionMatrixTest` |
| W1b′: append refused because the segment vanished while open | `record` throws. No fact is added, and the segment is never recreated. Reopening is refused as possible data loss: the writable open fails and read-only opens report `MANIFEST_INVALID` | operator action outside v1: restore the segment from a backup. Reopening alone does not recover | `LedgerHardeningTest`, `LedgerRound5Test` |
| W1c: crash during the ledger write (torn final line) | the writable open refuses (`no automatic repair`). Read-only opens report `hasTornTail()` and serve the valid prefix, with the projection `CURRENT` for that prefix. The auditor reports a `TORN_TAIL` warning. No file is modified | operator action outside v1: no API truncates the ledger | `ExecutionLedgerTest`, `ExecutionMemoryReadOnlyTest`, `CrossLayerInterruptionMatrixTest` |
| W1d: interior ledger corruption | writer, read-only open and auditor refuse it or report it as an error. It is never served as healthy | operator action outside v1 | `LedgerCorruptionTest`, `ExecutionMemoryReadOnlyTest` |
| W2a: projection write fails after the durable append | `APPENDED` and `STALE` (covered < ledger). Later appends stay unprojected. Recall answers from the covered prefix | `rebuildProjection` | `ExperienceProjectionFailureTest` |
| W2b: crash in the middle of a checkpoint batch (no COVER, or a torn line) | `STALE`, with the diagnostic "interrupted". Uncovered atoms are never served and take no top-K slot | `rebuildProjection` | `ExperienceProjectionFailureTest` |
| W2c: retry of an event whose projection failed | same event: `IDEMPOTENT` with the original sequence. Changed payload: `CONFLICT`. Ledger and checkpoint bytes are unchanged and the state stays `STALE`. After a rebuild the fact is projected once, and the checkpoint is byte-identical to an uninterrupted run | `rebuildProjection` | `CrossLayerInterruptionMatrixTest` |
| W2d: read-only open of a `STALE` scope | `STALE` with the same covered and ledger sequences. Hits come from the covered prefix only. Nothing is created or changed | a writer calls `rebuildProjection`, then the reader reopens | `CrossLayerInterruptionMatrixTest` |
| W3a: rebuild fails before publication (`beforePublish`, or a COVER of the new generation) | the rebuild throws. The previous state and pointer are kept, the half-built generation is deleted, and the ledger is untouched | retry `rebuildProjection` | `ExperienceProjectionGenerationTest`, `CrossLayerInterruptionMatrixTest` |
| W3b: crash during construction (orphan generation) | the published generation answers and the orphan is ignored | the next `rebuildProjection` or `pruneProjection` reclaims it | `ExperienceProjectionGenerationTest` |
| W4a: crash between the temporary pointer and the atomic move | the old pointer and generation stay `CURRENT` in both modes. The temporary pointer is never promoted. Readers delete nothing | `rebuildProjection` publishes the next generation and reclaims the orphan and the temporary pointer (or `pruneProjection`) | `ProjectionCheckpointTest`, `CrossLayerInterruptionMatrixTest` |
| W4b: cleanup of old generations fails after publication | the rebuild is committed. The status is `CURRENT`, with a diagnostic naming the generation that could not be deleted | `pruneProjection` | `ExperienceProjectionGenerationTest` |
| W5a: reader opens while a writer changes the scope's files and the loaded projection looks `INCOMPATIBLE` or `MISSING` | the whole snapshot is retried (at most `READ_ONLY_ATTEMPTS`, no waiting). If every attempt is inconsistent it is `INCOMPATIBLE` with "reopen to retry". A coherent `CURRENT` or `STALE` snapshot is returned on the first attempt and does not wait for or include the writer's later append (see W5b). An open snapshot never changes | reopen | `ExperienceProjectionFailureTest`, `ExperienceProjectionGenerationTest`, `ExperienceRecallReadOnlySnapshotTest` |
| W5b: reader opens between the writer's durable append and its COVER | the snapshot is `STALE` (covered = ledger − 1). The uncovered fact is never served, even after the writer covers it. The reader mutates nothing. A fresh snapshot is `CURRENT` | reopen | `CrossLayerInterruptionMatrixTest` |
| Any window, other scopes | interruptions, retries and rebuilds in one scope leave another scope's files byte-identical. Its snapshots never return foreign refs | none | `CrossLayerInterruptionMatrixTest` (a sibling scope is checked after every cell), `ExperienceRecallIsolationReportTest` (normal recall) |

The behavior was already in the code: issue #127 adds tests only, and no window needed a production change.

## 19. Usage and Cost Calculators (issue #99)

Implements the usage and cost rules of section 8 in `com.monada.evaluation.execution` (`monada-evaluation`
only; no change to `monada-core`, `monada-api`, storage formats, recall metrics or defaults). The code is
pure: it reads no clock, file or network, looks up no prices, ranks no model and calls no LLM.

### 19.1 Public API

```java
PriceBook prices = PriceBook.of(List.of(snapshot));                 // caller-supplied, versioned
ExecutionView view = memory.loadExecution(scope, execution).orElseThrow();
ExecutionSummary s = ExecutionSummaryCalculator.summarize(view, prices, List.of(billedAmount));
s.cost().status();      // AVAILABLE | PARTIAL | UNAVAILABLE   (basis: HYPOTHETICAL_API_EQUIVALENT)
```

| Type | Role |
| --- | --- |
| `ExecutionSummaryCalculator.summarize(ExecutionView, PriceBook, List<ActualBilledAmount>)` | Single entry point; deterministic for identical input |
| `PriceBook`, `PriceBook.Match` | Snapshot selection by provider, model and effective date |
| `ExecutionSummary` | Chain and post-acceptance stages, usage, time, cost, gates, actual billed amounts, ignored duplicates |
| `StageUsageCost`, `CostLine`, `CostReason` | Per-stage priced lines, snapshot used, coverage and reasons |
| `CostToAcceptedOutcome`, `CurrencyCost`, `CostBasis` | Status, per-currency amounts, reasons; the basis is always hypothetical |
| `UsageTotals`, `TimeSummary`, `GateEvidence`, `ActualBilledAmount` | Usage by provenance, effort vs elapsed time, per-dimension evidence, caller-supplied billing |

### 19.2 Input and event identity

- The input is the `ExecutionView` of #97. Its `history()` carries every ledger entry with its sequence, which
  the cutoff needs (`AttemptView.stages()` loses sequences).
- Entries are processed in ascending sequence. An event is identified by `eventId`: a repeated `eventId` is
  ignored and listed in `ignoredDuplicateEvents`; the same content under a different `eventId` is a different
  fact and counts. A `Correction` replaces the revision it supersedes; superseded revisions never count.
- **Chain membership** uses the sequence of the *first* revision of a stage (`originalSequence`). A stage
  recorded before the cutoff stays in the chain when it is corrected afterwards: the correction fixes its data
  but does not make the work post-acceptance. `StageUsageCost.sequence()` is the latest revision's sequence.
- **Cutoff** (section 8): the highest sequence among the current `ACCEPTED` outcome event, the effective
  `ATTEMPT_FINISHED(COMPLETED)` of the accepted attempt and its effective mandatory observations. An outcome may
  be recorded while the accepted attempt is still open; the derived status stays `ACCEPTED_UNVALIDATED` until
  the completion, so work recorded in between belongs to the chain. Late or corrected validating facts
  (evidence or a finish corrected to `COMPLETED`) therefore move the cutoff and pull the work done before it
  into the chain. If the view says `VALIDATED_ACCEPTED` but the outcome, the completed finish or a mandatory
  observation is missing from the supplied history, the cost is `UNAVAILABLE` with
  `CUTOFF_UNDERIVABLE`; nothing is guessed.
- Without a cutoff (pending, rejected, failed, cancelled, unvalidated) every recorded stage is listed in
  `chainStages` so no work is hidden, `postAcceptanceStages` is empty and the cost is `UNAVAILABLE`.
  `ExecutionSummary.chainScope()` says which case applies: `UP_TO_ACCEPTANCE` or `ALL_RECORDED_NO_ACCEPTANCE`.
  Consumers must not treat the `chain*` fields of an `ALL_RECORDED_NO_ACCEPTANCE` summary as an accepted chain
  (the comparison of section 20 treats such an execution as not eligible).
- Correction resolution mirrors the facade's own (`ExecutionViews`, package-private in `monada-api`) and is
  kept honest by `ChainScopeGatesAndParityTest`, which compares the resolved effective stages, evidence and
  outcome with the facade's `ExecutionView` after multi-revision corrections.

### 19.3 Price selection and arithmetic

- A stage is priced by the snapshot with the same provider and model and the latest `effectiveDate` on or
  before the UTC date of the stage's `endedAt`. A stage without provider or model, or without such a snapshot,
  is `MISSING_PRICE`. Two different snapshots sharing the winning date are `AMBIGUOUS_PRICE` (an addition to
  the section 8 reason list). `PriceBook.of` rejects duplicate `(id, version)` pairs. The snapshot (id, version,
  source, assumptions) used by each stage stays visible in `StageUsageCost.priceSnapshot()`.
- Lines follow section 8: `billable(k)` removes directly included kinds; a negative value is
  `INCONSISTENT_USAGE`. A line is computed only when its own counter and the counters of every kind included
  in it are valued; otherwise the missing child is the reason and the parent line is not guessed.
- `line = billable x price / unitSize`: exact product, one division at scale 18 `HALF_EVEN`. Per-currency totals
  add unrounded lines; the amount is rounded **once** to scale 6 `HALF_EVEN`. `unrounded` stays alongside and
  `reportedBasis + estimatedBasis = unrounded`, split by usage provenance (a line is estimated when any
  contributing counter is). Currencies are sorted by ISO code and never summed.
- `long` overflow while summing quantities is rejected with `IllegalArgumentException`.

### 19.4 Completeness

Status and reasons follow section 8. Clarifications:

- A `PARTIAL` result reports a lower bound per currency of the snapshots actually used. When no stage has a
  matching snapshot there is no currency to attach a bound to, so `amounts` is empty and the status is
  `PARTIAL` (a `MISSING_PRICE` chain does not report an unlabelled `0.000000`).
- A chain made only of `NO_BILLABLE_USAGE` stages is `AVAILABLE` with empty `amounts` (`isDeclaredZero()`) and
  one `DECLARED_NO_BILLABLE_USAGE` reason per stage; a declared zero beside priced stages keeps its reason.
- `UNAVAILABLE` never carries an amount. Reasons: `NO_VALIDATED_ACCEPTANCE` (with the derived status and
  reasons), `NO_USAGE_RECORDED`, `CUTOFF_UNDERIVABLE`.

### 19.5 Usage, time, gates, actual billing

- `UsageTotals` keeps, per `UsageKind`, reported and estimated sums apart, plus the number of stages with an
  `UNKNOWN` counter and the number of `MEASURED` stages that did not measure it. Neither is added as zero.
  "Not measured" is counted only for kinds the stage's price snapshot prices (a stage without a snapshot
  already has `MISSING_PRICE`; unpriced kinds such as `REQUESTS` are not a coverage gap), so it agrees with the
  coverage reasons.
- `StageUsageCost.covered()`, `CurrencyCost.unrounded()`/`amount()` and `TimeSummary.parallelOverlap()` are
  derived accessors, not stored state, so they cannot disagree with the data they come from.
- `TimeSummary`: `effort` = sum of stage durations; `wallClock` = first start to last end; `activeTime` = union
  of the stage intervals (touching intervals merge); `parallelOverlap` = `effort - activeTime`. Without stages
  every duration is absent with a reason, never `Duration.ZERO`.
- `GateEvidence` has one entry per `QualityDimension` (enum order), built from the accepted attempt's
  applicable evidence of section 8. It carries the full observation (state, measured value and unit,
  evaluator, policy, source, artifacts) and its sequence; `MAINTAINABILITY` and `COMPLEXITY` are rendered like
  any other dimension. No combined score exists.
- `ActualBilledAmount` is supplied by the caller (the ledger has no billing event) and is only echoed in
  `actualBilled`; it is never added to, or compared with, the hypothetical amount.

### 19.6 Compatibility and verification

No persisted format, public signature or default changes. Verification: `./gradlew :monada-evaluation:test`,
`./gradlew test`, `./gradlew verifyLibraryConsumer`. Tests run on real histories recorded through
`ExecutionMemory` in temporary directories; the hand-calculated chain (failed implementation, repair, review,
QA, accepted, plus one post-acceptance stage) is documented in `AttemptChainSummaryTest`: total 0.1365 USD
(reported 0.0865, estimated 0.05), reported as `0.136500`, effort 180 s, wall-clock 170 s, active 170 s,
overlap 10 s. Wiring calculators into Forge or Neuron is future cross-repository work.

## 20. Evidence-gated Comparison and Pareto Diagnostics (issue #100)

Implements section 10 in `com.monada.evaluation.execution` (`monada-evaluation` only; no change to
`monada-core`, `monada-api`, storage formats, `EvaluationComparator`, retrieval metrics, routing, learning or
defaults). The code is pure: no clock, file, network, provider or judge; it ranks no model and trains nothing.
Results are **observational evidence about the supplied executions**, not causal and not a general claim about
any model (`ExecutionComparator.INTERPRETATION` is carried by every pairwise result and report).

### 20.1 Public API

```java
ComparisonCandidate a = ComparisonCandidate.of(scope, viewA, prices, List.of());   // summarizes viewA (#99)
ComparisonCandidate b = ComparisonCandidate.of(scope, viewB, prices, List.of());
ComparisonPolicy policy = new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
        new Objective("cost", new Metric.Cost(Currency.getInstance("USD")), Direction.MINIMIZE, new BigDecimal("0.001")),
        new Objective("effort", new Metric.Effort(), Direction.MINIMIZE, BigDecimal.ZERO)));
PairwiseComparison pair = ExecutionComparator.compare(a, b, policy);          // one pair
ComparisonReport report = ExecutionComparator.report(List.of(a, b, c), policy, judgments); // every pair + Pareto
```

| Type | Role |
| --- | --- |
| `ComparisonCandidate` | Caller scope + `ExecutionView` + the `ExecutionSummary` computed from that view; only `of(...)` builds it, so a summary cannot be paired with another view (a view does not carry its scope). `of` rejects a view whose events belong to another scope or execution, or whose task, provenance, policy, current outcome or derived acceptance differ from what its own history derives (section 8 rules); every event must also carry the view's task, and the history must obey the ledger's ordering and reference rules (no stage or evidence for an unstarted or finished attempt, no dangling or out-of-order correction, ascending sequences, unique event ids) |
| `ComparisonPolicy`, `Objective`, `Metric` | Caller-declared mandatory gates and objectives: `Cost(currency)`, `Effort`, `WallClock`, `Measured(dimension, unit)`; direction and tolerance per objective. No defaults, no weights |
| `Comparability` | `NOT_COMPARABLE` reasons, one `Mismatch(field, left, right)` per field |
| `Eligibility` | Exclusion reasons; empty means eligible |
| `ObjectiveResult` | `LEFT_BETTER`, `RIGHT_BETTER`, `TIE` or `INCONCLUSIVE` with reasons, both values |
| `PairwiseComparison` | Conclusion, optional winner and its basis, all objective results, judgments |
| `Participant` | Acceptance, eligibility, gate evidence with provenance, resource-chain coverage |
| `ComparisonReport`, `ParetoGroup` | Deterministic report: participants, all pairs, one Pareto analysis per comparable group |
| `PairwiseJudgment` | Evaluator-supplied preference with judge, version, source, rubric and evidence |

**Trust boundary.** `ComparisonCandidate.of` rejects views that contradict themselves (identity, task, provenance,
policy, outcome, acceptance, ledger ordering and references), but it cannot prove that a caller-built `ExecutionView`
is the facade's complete, unaltered snapshot: `ExecutionView` and `HistoryEntry` have public constructors in
`monada-api`, and a history whose stage usage was lowered or whose stage was removed is still internally consistent.
Like the rest of the library (section 8: Store validates structure, not truth), the comparison treats the supplied
views as caller-owned data, so views must come from `ExecutionMemory.loadExecution`. Making views unforgeable (a
facade-issued snapshot or digest) needs a `monada-api` change and is out of scope for #100; it is recorded as a
follow-up.

The two re-implemented rule sets that verification needs (ledger ordering and reference rules in `HistoryStructure`,
acceptance derivation in `ViewVerifier`) are private to `monada-storage` and `monada-api`. `ViewLedgerParityTest` keeps
them aligned: every event the real ledger accepts must be accepted with an identical acceptance, and every event it
rejects must make a view built from the extended history fail. A shared public validator is part of the follow-up.

### 20.2 Comparability

Direct comparison requires exact equality of scope, task, `sourceRevision`, `contextFingerprint`,
`constraintsFingerprint`, evaluation policy id and policy version. Every differing field is reported as its own
`Mismatch` (`SCOPE`, `TASK`, `SOURCE_REVISION`, `CONTEXT_FINGERPRINT`, `CONSTRAINTS_FINGERPRINT`, `POLICY_ID`,
`POLICY_VERSION`). A `NOT_COMPARABLE` pair has no objective results and no winner: nothing is compared across
different starting conditions. In a report, executions are grouped by these seven values; each group has its own
Pareto analysis and no group is ranked against another.

### 20.3 Eligibility

An execution is eligible for a validated winner or a Pareto front only if **all** hold:

1. its derived acceptance is `VALIDATED_ACCEPTED` (an accepted outcome alone, an imported claim or an
   unfinished accepted attempt is `NOT_VALIDATED_ACCEPTED`) and its cutoff is derivable
   (`CUTOFF_UNDERIVABLE` otherwise);
2. for every dimension of the **caller's** `mandatoryGates` the effective observation of the accepted attempt is
   `PASS` or a justified `NOT_APPLICABLE`. `FAIL` is `GATE_FAIL`, `UNKNOWN` is `GATE_UNKNOWN` and no applicable
   evidence is `GATE_MISSING`. The caller's set is checked in addition to the execution policy's own mandatory set.

Measured values (lines of code, complexity, maintainability index, dependency counts) and evaluator judgments
never satisfy or waive a gate. Low cost never compensates for an exclusion. An excluded execution still gets
objective results for display, but `PairwiseComparison.informationalOnly()` is true and it is never a winner. Cost
of an execution without validated acceptance is `UNAVAILABLE` (section 8) and therefore shows as inconclusive, not
as cheap.

### 20.4 Objectives, tolerance and `INCONCLUSIVE`

Each objective reads one metric from the chain up to the acceptance cutoff:

| Metric | Value | Inconclusive when (reason code) |
| --- | --- | --- |
| `Cost(currency)` | `CurrencyCost.unrounded()` of that currency, exact `BigDecimal` | cost not `AVAILABLE` (`INCOMPLETE_COST`, with the section 8 reasons); chain priced in more than one currency or in another one (`MIXED_CURRENCY`); one chain mixes pricing assumptions, or the two sides use different `PriceSnapshot.assumptions` (`MIXED_PRICING_ASSUMPTIONS`) |
| `Effort`, `WallClock` | `TimeSummary` seconds | absent (`MISSING_TIME`) |
| `Measured(dimension, unit)` | value of the effective observation, only when its state is `PASS` | no value (`MISSING_MEASUREMENT`); a value whose observation is `FAIL`, `UNKNOWN` or `NOT_APPLICABLE` (`UNRELIABLE_MEASUREMENT`); another unit, or another evaluator, version or source on the two sides (`INCOMMENSURABLE_AXIS`) |

A declared zero (every stage `NO_BILLABLE_USAGE`) is exactly 0 and carries no pricing assumption. Different
models are expected to use different snapshots; only the **assumptions text** must be identical, so callers
that want cost comparable across models keep it identical. `|left - right| <= tolerance` is a `TIE`; otherwise the
better side follows the direction. Tolerances make dominance a pairwise relation that is not claimed to be
transitive. Values are never combined across objectives.

### 20.5 Conclusions, winner and Pareto

For two comparable executions (`PairwiseComparison.Conclusion`):

| Conclusion | Condition | Winner |
| --- | --- | --- |
| `NOT_COMPARABLE` | any section 20.2 mismatch | none |
| `NO_ELIGIBLE_EXECUTION` | neither side eligible | none |
| `ONLY_LEFT_ELIGIBLE` / `ONLY_RIGHT_ELIGIBLE` | exactly one side eligible | that side, basis `ONLY_ELIGIBLE` (says nothing about cost or quality) |
| `NO_OBJECTIVES` | both eligible, policy declares none | none |
| `TRADEOFF` | each side is better on some conclusive objective (stands even if another objective is inconclusive) | none |
| `INCONCLUSIVE` | no tradeoff, but some objective is inconclusive | none |
| `LEFT_DOMINATES` / `RIGHT_DOMINATES` | every objective conclusive; one side is better on at least one and the other on none | that side, basis `DOMINANCE` |
| `EQUIVALENT` | every objective conclusive and tied | none |

`ParetoGroup` (per comparable group):

- `excluded`: ineligible executions with their exclusion reasons;
- `unassessed`: eligible, but with an incomplete, unreliable or incommensurable measurement on a declared objective, or no
  declared objective, or part of a dominance cycle (`DOMINANCE_CYCLE`: tolerances make dominance non-transitive, so A can
  dominate B, B dominate C and C dominate A; members of such a cycle with no dominator outside it are not reported as
  worse than anyone); they are neither on the front nor dominated, and every reason is listed;
- `dominated`: dominated by the listed executions through a conclusive pairwise `DOMINATES`. Domination stays conclusive when
  the execution is also incommensurable with another member; those reasons are kept in `unresolved` so none is lost;
- `front`: the remaining assessable executions, each conclusive against every other assessable one. This holds even when a pair is already a `TRADEOFF`: an inconclusive third objective keeps both out of the front.

Mixed currencies or pricing assumptions therefore produce `unassessed`, never a total order. When tradeoffs remain,
the front holds several executions and there is no automatic winner.

### 20.6 Determinism, judgments, coverage

- Participants and pairs are ordered by `ExecutionRef` (execution id, then scope as tiebreak because execution ids
  are unique per scope only); groups by the seven comparability values; judgments by pair, dimension, judge,
  version, source, rubric, preference and evidence references. Input collection order, including judgments, never changes the report
  (`ComparisonOrderAndValidationTest` shuffles). Duplicate executions, unknown judgment targets, an empty gate set,
  duplicate objective ids and negative tolerances are rejected before comparing.
- `PairwiseJudgment` requires judge id and version, rubric and at least one evidence reference, and names a
  preferred execution or none. It is attached to its pair as given; it changes no gate, objective result or conclusion.
  Store creates no judgments and calls no judge.
- `Participant.resources` keeps cost status and reasons, the number of chain stages and how many are covered, the
  distinct price snapshots (id, version, provider, model, currency, source, assumptions). Candidates are built from
  verified histories, which cannot contain duplicate event ids (they are rejected), so no ignored-duplicates list is kept.
  `Participant.gates` keeps every dimension's evidence with evaluator, policy, source and artifacts.

### 20.7 Compatibility and verification

No persisted format, public signature, ranking default or quality-policy default changes, and nothing feeds
retrieval feedback, routing or learning. This is experimental evaluation evidence, distinct from protected
correctness gates and not a supported production default. Consumer wiring in Forge or Neuron is future
cross-repository work. Retrieval metrics are not involved, so no per-query ranking evidence applies.

Verification: `./gradlew :monada-evaluation:test`, `./gradlew test`, `./gradlew verifyLibraryConsumer`.
`ExecutionComparatorTest` records real executions in temporary directories and hand-computes (USD per 1M tokens:
input 2, output 10): a cheap execution failing `TESTS` (0.003, not a cost to an accepted outcome) beside a direct
accepted one (10,000 in / 1,000 out = 0.03 USD, 10 s) and an accepted repair (0.03 + 0.06 = 0.09 USD, 10 s + 30 s =
40 s), where direct dominates repaired and the cheap one is excluded; a tradeoff (0.04 USD / 10 s against 0.02 USD /
60 s) with no winner; a three-execution front (0.02 / 30 s, 0.04 / 10 s, 0.05 / 40 s) where the third is dominated by
both; a tie exactly at tolerance; incomplete usage; mixed currency and mixed pricing assumptions; and one case per
comparability field.

## 21. Bounded Sample Export (issue #101)

Implements section 12 in `com.monada.api.execution` (`monada-api`: the export, its records and the canonical JSON) and
`com.monada.evaluation.execution` (`monada-evaluation`: cost and comparison annotations and a read-only command). No
persisted format, existing signature, default, retrieval metric or storage file changes; the only additions to existing
types are `ExecutionMemory.exportSamples` and `ExecutionMemory.loadExecution(scope, execution, cutoffSequence)`.
Nothing is converted into retrieval feedback, nothing is trained or routed, and no transcript or secret is exported.

### 21.1 Ownership

Store owns persistent execution memory and hands out **neutral observations**. A Neuron adapter (in its own
repository) decides what to learn from them; Forge collects and judges evidence. This repository contains no
adaptation, routing or scoring code, no Neuron or Forge dependency and no network call. `AdapterConsumerExampleTest`
is the executable example of the boundary (section 21.6).

### 21.2 API

```java
ExportRequest first = ExportRequest.first(scope, ExportCutoff.atSequence(cutoff), 100, ExportView.ADAPTER_READY,
        ExportEvidencePolicy.requiring(QualityDimension.SECURITY));          // extra gates are optional
ExperienceSamplePage page = memory.exportSamples(first);
String json = ExperienceSampleJson.toJson(page);                              // canonical rendering of this page
String digest = page.digest();                                                // SHA-256 of that rendering
ExperienceSamplePage next = memory.exportSamples(ExportRequest.resume(page.next().get(), 100)); // while hasMore
```

| Type | Role |
| --- | --- |
| `ExportRequest` | Scope, cutoff, page size, view, evidence policy; `first(...)` for a first page, `resume(cursor, pageSize)` for the next. A resumed request cannot carry a cutoff and its view and gates always come from the cursor |
| `ExportCutoff` | `latest()`, `atSequence(n)` or `recordedUpTo(instant)` (section 21.3) |
| `ExportView` | `ADAPTER_READY` (default for consumers) or `DIAGNOSTIC` (section 21.4) |
| `ExportEvidencePolicy` | Extra gate dimensions on top of each execution's own mandatory dimensions; it can only tighten a label |
| `ExportCursor` | Opaque, stateless token `x2`: scope, resolved cutoff sequence, start sequence of the last returned execution, view, gates and the digest of the ledger prefix it was issued for |
| `ExperienceSamplePage` | `schema`, scope, view, gates, `LedgerCheckpoint`, `ProjectionStamp`, samples, `hasMore`, `next` |
| `ExperienceSample` | One execution as of the cutoff (section 21.4) |
| `ExperienceSampleJson` | Canonical JSON and digest |

`pageSize` is 1..`ExecutionMemoryConfig.maxPageSize` (default 500, hard limit 500). The export works on writable and
read-only instances. It needs no current projection and never writes one.

### 21.3 Cutoff, order, cursor and reproducibility

- A sample is derived from the **ledger prefix `1..cutoff`** only: events and corrections recorded later are invisible.
  An execution is exported when its first event has a sequence `<= cutoff`.
- `atSequence(n)` requires `0 <= n <=` the last sequence (`IllegalArgumentException` otherwise); `0` is an empty page.
  `recordedUpTo(t)` resolves to the longest prefix whose events were **all** recorded at or before `t`: it stops at
  the first event recorded later. A correction carries its own `recordedAt` (it need not equal the original's).
  Because later appends can extend a prefix that reaches the end of the ledger, hosts that need repeatable exports pin
  `page.ledger().cutoffSequence()`. The first page of a `latest()` or `recordedUpTo` export reports the resolved
  sequence and the cursor freezes it.
- Executions are ordered by the ledger sequence of their first event. Appends cannot reorder or insert before it,
  so the order is stable and the cursor needs only that sequence (`afterStartSequence`).
- The cursor is the history snapshot rule of section 12 with the cutoff as high-watermark: a continuation returns
  the same page whatever was appended meanwhile; a cursor of another scope, beyond the ledger, malformed or of
  another token version is rejected before anything is read. The token carries the `prefixDigest` of the ledger
  prefix it was issued for, so a cursor resumed against a different ledger history (another or replaced ledger of the
  same scope) is rejected, never answered with a page of another snapshot. Options cannot drift: the `ExportRequest` constructor
  rejects a view or gate set that disagrees with the cursor.
- `LedgerCheckpoint(cutoffSequence, prefixDigest)`: `prefixDigest` is SHA-256 (hex) over the lines
  `<sequence>:<ledger payload digest>` + LF for sequences `1..cutoff`, using the payload digest the ledger line itself
  carries, so a different ledger prefix can never share a checkpoint. The instance saves SHA-256 states every 1,024
  sequences, so a paged export hashes the ledger once, not once per page.
- `ProjectionStamp(formatVersion, textScheme, state, coversCutoff)` names the projection that serves recall (`1`,
  `summary-text-v1`). Only `formatVersion` and `textScheme` are in the canonical JSON and digest; `state` and
  `coversCutoff` are the projection's live status when the page was read and change when it is rebuilt, so they stay
  out of the reproducibility fingerprint. Samples are never derived from the projection: a `MISSING` and a `CURRENT`
  projection of the same ledger give the same canonical page and digest.
- The same scope, cutoff sequence and options give byte-identical pages and digests after reopen, on writable and
  read-only instances, and independently of page size (the samples are equal; only the page boundaries differ).

### 21.4 Samples, labels and eligibility

Every sample carries: the `ExperienceRef` of the execution start (latest revision), task summary, provenance,
policy, required gates, current outcome with origin (an imported claim stays distinguishable) and its ref, the
derived acceptance, the label, the eligibility, per-dimension gates, attempts (route, stages, usage, evidence,
result, solution and lesson summaries, each with its `ExperienceRef`), revisions, and the first and last ledger
sequence it uses. All text is caller-approved summaries; artifacts are opaque references, never dereferenced.

| Label | Condition |
| --- | --- |
| `SUCCESS` | Derived acceptance `VALIDATED_ACCEPTED` **and** every additional gate of the export is `PASS` or justified `NOT_APPLICABLE` |
| `FAILURE` | Current outcome `REJECTED` or `FAILED` with origin `VALIDATED` (derived `NOT_ACCEPTED`) |
| `UNLABELED` | Anything else: pending or no outcome, a `CANCELLED` outcome (a cancellation is not a verdict on the route), any imported claim whether positive or negative, an unfinished or not-completed accepted attempt, a required gate that is `FAIL`, `UNKNOWN` or has no applicable evidence |

`eligibility.eligible` is true exactly for `SUCCESS` and `FAILURE`. An ineligible sample is **exported, never
dropped**, with reasons (`OUTCOME_PENDING`, `OUTCOME_CANCELLED`, `OUTCOME_IMPORTED_CLAIM`, `ACCEPTED_ATTEMPT_UNFINISHED`,
`ACCEPTED_ATTEMPT_NOT_COMPLETED`, `GATE_FAIL`, `GATE_UNKNOWN`, `GATE_MISSING` with the dimension). The label never
claims more than the section 8 derivation: the builder reuses the facade's own `DerivedAcceptance`, and if its reasons
ever disagree with it the sample is exported `UNLABELED` with `INCONSISTENT_DERIVATION` (a defensive path that valid
ledgers do not reach) instead of failing the page. Facts are resolved by one shared `EffectiveFacts` that the
facade's views also use, so samples and views cannot resolve corrections differently. A `SUCCESS` label is a structural verdict under the evidence policy, not a
quality score or a recommendation; low cost or a high similarity never changes it.

`gates` (only when the outcome is `ACCEPTED`) has one entry per required dimension plus every dimension with an
applicable observation, in enum order: state `PASS`, `NOT_APPLICABLE`, `FAIL`, `UNKNOWN` or `MISSING`, with the
effective observation, its ref and ledger sequence. Applicability and override rules are those of section 8 (accepted
attempt, same policy id and version, latest effective sequence). Measured values never satisfy a gate.

**Views.** Both views use the same labels, so `UNKNOWN` mandatory gates are never `SUCCESS` in either.

| | `ADAPTER_READY` | `DIAGNOSTIC` |
| --- | --- | --- |
| Observations in attempts | only those recorded under the execution's own policy | all, each flagged `policyMatches` |
| `diagnostics` | absent | acceptance reasons, every effective outcome event, every ignored observation and why (other policy, other attempt, overridden) |

**Deduplication.** A sample is one execution at its latest revisions. A correction replaces the fact it corrects:
exactly one effective ref per fact, and `revisions` lists the superseded refs (oldest first) so old and new revisions
are never two independent successes. Retries and repairs are attempts of one execution; only the accepted attempt is
marked `accepted`, and the failed ones keep their `FAILED`/`CANCELLED` result. An idempotent retry of `record` writes
nothing, so it cannot change an export. Two distinct executions of the same task are distinct facts, so they are
**marked, never hidden**: every sample carries its `group` (task, source revision, context and constraints
fingerprints, policy id and version, i.e. the section 10 comparability key without the scope) and a `SUCCESS` sample
the `successOrdinal` among the group's successes in start order as of the cutoff and gates. A consumer that wants
one example per repeated piece of work keeps `successOrdinal == 1`; ordinals do not depend on page size. The first
export per (cutoff, gates) labels every execution of the scope once to compute them.

**Usage provenance.** Each stage lists one `SampleUsage` per `UsageKind`, in enum order: `REPORTED` or `ESTIMATED`
(with value, a known zero stays `0`), `UNKNOWN` (no value), `NOT_MEASURED` (counter absent) or
`DECLARED_NO_BILLABLE_USAGE` (stage declared nothing billable). None of them is ever rendered as zero.

### 21.5 Canonical JSON and the evaluation tooling

`ExperienceSampleJson.toJson(page)` is JSON with a fixed field order, two-space indentation, LF endings, `null` for an
absent optional and escaped control characters. It is write-only (no parser is shipped); its SHA-256 is
`page.digest()`. Example (abridged):

```json
{
  "schema": "execution-sample/1",
  "scope": "demo",
  "view": "ADAPTER_READY",
  "ledger": { "cutoffSequence": 25, "prefixDigest": "fd8b0bec..." },
  "samples": [ { "label": "UNLABELED", "eligibility": { "eligible": false,
      "reasons": [ { "code": "GATE_UNKNOWN", "dimension": "TESTS", "detail": "TESTS effective observation is UNKNOWN" } ] } } ],
  "hasMore": true,
  "nextCursor": "eDF8..."
}
```

`ExperienceExportReport.build(memory, firstRequest, priceBook, Optional<ComparisonPolicy>)` (evaluation only) pages
through the whole export and adds, beside the unchanged samples: the hypothetical `CostToAcceptedOutcome` as of the
cutoff (section 19; `UNAVAILABLE`, never zero, without validated acceptance) and, when a `ComparisonPolicy` is given,
the Pareto placement of each sample (`FRONT`, `DOMINATED`, `UNASSESSED`, `EXCLUDED`, section 20) with
`ExecutionComparator.INTERPRETATION` (observational evidence, not causal). `exportDigest()` covers the sample pages
only, so annotations can never change it. `summary()` prints one deterministic line per sample.

```bash
./gradlew :monada-evaluation:runExecutionSampleExport -Dmonada.execution.dir=<ledger> -Dmonada.execution.scope=<scope> \
    [-Dmonada.execution.cutoff=<sequence>] [-Dmonada.execution.pageSize=<n>] [-Dmonada.execution.view=DIAGNOSTIC] \
    [-Dmonada.execution.gates=TESTS,SECURITY] [-Dmonada.execution.output=json]
```

The command opens the ledger read-only (nothing is created or locked), exits 1 on a usage or ledger error, and prints
`summary` or one JSON array of the canonical pages (a single valid document, whatever the page count). It has no price book, so its cost column reflects usage coverage only.

### 21.6 Consumer example

`AdapterConsumerExampleTest` (`monada-api`, fixture-driven, no Neuron, Forge or network): it pins a cutoff, pulls
bounded pages, keeps repeated validated work once (`successOrdinal == 1`), turns eligible samples into plain rows (label, route of the accepted attempt, whether input and output
usage is fully known, exact source ref) and keeps ineligible ones aside with their first reason. It makes no
adaptation decision and asserts that the ledger did not move. A Neuron adapter would do the same in its own repository
and add its own policy on top; wiring it is future cross-repository work.

### 21.7 Compatibility and limits

- No persisted format changes: the export reads the in-memory ledger index of an open instance. Ledgers, projections
  and ranking-feedback files are byte-identical before and after any export (asserted over every file under the root,
  with a current projection and its empty feedback log present).
- `monada-api` still does not depend on `monada-evaluation`. Existing signatures and defaults, `MonadaMemory`, the
  retrieval ranking and the protected baselines are untouched, so no per-query ranking evidence applies.
- Page size bounds the number of samples, not their size: a sample holds every event of its execution up to the
  cutoff, so a very long execution makes a large sample (per event limits of section 3 still apply).
- Each page scans the ledger from the cursor to the cutoff; the prefix digest is incremental (saved SHA-256 states) and the
  success index is computed once per (cutoff, gates), so the main cost per page is the scan, `O(pages x ledger size)`
  in the worst case; the instance already keeps the whole ledger index in memory.
- `ExperienceExportReport` derives each execution up to three times (sample, as-of view, comparison candidate); it
  retains candidates only when a comparison policy is given. The evaluation module keeps its own correction
  resolution (`ChainResolver`) because `EffectiveFacts` is package-private to `monada-api`; `ChainScopeGatesAndParityTest`
  and `ViewLedgerParityTest` keep them aligned. A single scope per instance, as in sections 17 and 18.
- Samples show the **effective** facts as of the cutoff; the full correction history is available through `history`
  and `loadEvent`. Recalled similarity is not part of a sample: join `recall` hits to samples by execution id.
- Evidence about retrieval quality is not involved. The export is experimental evaluation input and does not authorize
  a new supported default.

### 21.8 Verification (issue #101)

~~~bash
./gradlew :monada-api:test
./gradlew :monada-evaluation:test
./gradlew test
./gradlew verifyLibraryConsumer
./gradlew :monada-evaluation:runExecutionSampleExport -Dmonada.execution.dir=<ledger> -Dmonada.execution.scope=<scope>
~~~

Tests: `ExperienceSampleExportTest` (reproducibility after reopen on writable and read-only instances, page-size
independence, byte-identical ledger, projection and feedback files, labels for validated, rejected, pending, unknown
gate, imported claim and running executions, additional gates, corrections and cutoffs, foreign scope at every page
boundary, usage provenance, cursor tampering, views, canonical JSON), `AdapterConsumerExampleTest`, and in
`monada-evaluation` `ExperienceExportReportTest` (hand-computed 0.03 and 0.09 USD chains, Pareto placement, cost as of
the cutoff, annotation independence, read-only command). Real executions are recorded through `ExecutionMemory` in
temporary directories; fixtures contain no credentials, providers or private data.

## 22. Forge Experience Evaluation (issue #102)

Implements the end-to-end decision gate of the track in `com.monada.evaluation.execution` (`monada-evaluation`):
`ForgeExperienceEvaluator`, `ForgeExperienceEvaluationMain`, `ForgeFixture`, `ForgeFixtureRecorder`, `ForgeGate`,
`ForgeEvaluationReport`, `ForgeQueryResult` and `ForgeSampleChecks`. It uses only public APIs of the other modules and
needs no Forge installation, network or provider. No persisted format, signature, default, encoder, ranking or query-key
behaviour changes, and no protected baseline is added or altered.

### 22.1 Fixture

Fixture `forge-experience` version `1` lives in `monada-evaluation/src/main/resources/com/monada/evaluation/execution/forge-experience-v1/`
(`manifest.properties`, `executions.psv`, `attempts.psv`, `queries.psv`, `expectations.properties`). It is synthetic and
manually authored and judged: relevance judgments and expected values are reviewed data, never produced by a model or a
provider. It holds at least 24 experiences and 12 judged queries in at least 2 opaque projects (the bundled loader
`ForgeFixture.load()` rejects smaller corpora; a `ForgeFixture` constructed directly is not validated), with fixed
identifiers, times, context SHAs and a fixed price snapshot. One experience is one attempt and its id
is `<execution>/a<ordinal>`. Query kinds include direct, paraphrase, multi-relevant, confusable,
same-summary/different-attempt, stale revision, missing evidence, no-relevant and blank.

### 22.2 Flow

Per project: record through `ExecutionMemory`, close, reopen, reconcile the projection, load exact events, scoped
recall, usage and hypothetical-cost summary per execution, and paged export (page size 4, below the executions of
each project, so every export traverses continuation pages). Pairwise comparison is a separate step over judged
pairs, which include pairs within a project and a cross-project pair (`alpha:a-inv1` vs `beta:b-inv`, judged
`NOT_COMPARABLE` with a `SCOPE` mismatch). The whole flow runs twice in fresh `run-*` directories
under the caller's work directory; the second run only feeds G7.

### 22.3 Hard gates

| Gate | Property |
| --- | --- |
| `G1-SCOPE-ISOLATION` | No foreign-scope leakage in recall, load or export; cross-scope probes are rejected |
| `G2-NO-INVALID-SUCCESS` | No success label without validated evidence; labels equal the judged labels, including recalled hits |
| `G3-EXACT-REFERENCES` | Every recalled hit and effective export reference loads the exact ledger event at its latest revision, and every `revision.superseded` reference resolves to its historical, older revision; paged export equals the unpaged export |
| `G4-REPLAY-IDEMPOTENCY` | Re-recording is idempotent; reopen, replay and rebuild leave views, exports and rankings unchanged |
| `G5-RESOURCE-ACCOUNTING` | Attempt-chain usage and hypothetical `CostToAcceptedOutcome` equal the hand-verified values, also as exported |
| `G6-COMPARISON` | Comparable and incomparable executions get the judged conclusion and mismatching fields |
| `G7-DETERMINISM` | Two fixed-input runs produce identical content, gates, rankings and export digests |

A gate passes exactly when it collected no violation. Once the snapshots are built, gates report violations instead
of throwing, so a detected regression yields a report. A failure before that point (for example an execution that
cannot be loaded after reopen, or an I/O or storage error) propagates as an exception and the command prints no report.

### 22.4 Gates versus exploratory quality

The gates are enforced by tests in `./gradlew test` (`ForgeExperienceEvaluationTest`, `ForgeGatesTest`,
`ForgeReferenceChecksTest`, `ForgeSampleContractTest`, `ForgeCrossJvmDeterminismTest`), not by a versioned PROTECTED
baseline snapshot. Precision, Recall, Hit and MRR are reported per query and in aggregate, are EXPLORATORY, are never
enforced or frozen, and do not influence the decision. The CI-enforced protected baselines (text `default-databases-v1`,
speech `PROTECTED`) are unchanged, and the unrelated exploratory decisions of other tracks (`NORMALIZED_STRESS_RISK`,
`HYBRID_CANDIDATE_RISKY`) are neither baselines nor affected. Quality numbers are intentionally not recorded in
this contract; re-run the command for current output.

### 22.5 Empty relevance and blank queries

Queries with an empty judged relevant set (`NO_RELEVANT`, `BLANK`) are reported but excluded from every metric
denominator and average. A blank query must return no hits (checked by G1). A failed experience may be relevant to a
query; labels are per execution: a failed attempt inside an ultimately accepted execution is recalled and exported
with that execution's `SUCCESS` label, so a host that needs "what failed" must join the attempt result (`FAILED`) to
the hit. `FAILURE` is limited to a validated `REJECTED` or `FAILED` outcome. Pending or cancelled executions and
imported claims are `UNLABELED` (section 21.4) and must not be read as negative examples. Success is never labelled
without validated evidence (G2). See the roadmap gap "Labels are per execution".

### 22.6 Decision

| Decision | Condition |
| --- | --- |
| `READY_FOR_HOST_ADAPTER_EXPERIMENT` | All seven gates pass; the command exits 0 |
| `GAPS_REQUIRE_FOLLOW_UP` | At least one gate fails; the report prints the decision and the command exits 1 |

Observed outcome for the delivered fixture `forge-experience-v1`: all seven gates pass and the decision is `READY_FOR_HOST_ADAPTER_EXPERIMENT`. This is a correctness and isolation result
only; exploratory quality did not take part in it.

An exception before the snapshots are built (section 22.3) is a distinct failure: no report and no decision are printed.

The decision only authorizes a bounded host-adapter experiment outside this repository. Elapsed timing is printed
separately after the report and is the only nondeterministic output.

### 22.7 Limits

- No routing benefit is claimed, and nothing is trained, routed or adapted.
- The corpus is tiny and synthetic: quality values carry no statistical meaning. Scale and scoped linear-scan latency
  are not part of the gates.
- The default exact lexical encoding is unchanged and untuned; recall adds no relevance floor.
  Observed gaps and the next bounded experiment are recorded in [`docs/roadmap.md`](../roadmap.md)
  (Forge experience evaluation: observed gaps and next experiment), not duplicated here.
- A reduced two-project scenario (record, reopen, reconcile, scoped recall, export) also runs against the published
  artifacts in the isolated consumers of `integration-tests/library-consumer`.
- Wiring Forge or Neuron is future cross-repository work.

### 22.8 Verification (issue #102)

~~~bash
./gradlew :monada-evaluation:runForgeExperienceEvaluation -q
./gradlew :monada-evaluation:test
./gradlew test
./gradlew verifyLibraryConsumer
~~~

## Acceptance Criteria

- [x] ADR documents Store/Neuron/Forge ownership and concrete proposed public operations/types with examples.
- [x] Versioned v1 ledger layout, replay, identity/conflict, scope isolation and lifecycle rules are explicit.
- [x] Compatibility table preserves atom logs (5/6 fields), manifest 0.1–0.4, vector profiles,
  ranking/query-key defaults and existing API.
- [x] Quality gates, unknown values, hypothetical API prices and evidence provenance have unambiguous semantics.
- [x] Roadmap identifies implementation dependencies and future cross-repository consumer work without
  claiming it is implemented.

## Verification Commands

Documentation-only issue: no runtime change and no new tests. `./gradlew test` is not required; it may be
run to show the build is untouched.

~~~bash
git diff --stat origin/main -- . ':!docs'
./gradlew test
~~~

## Evaluation Requirements

None for #93. Retrieval evidence (per-query top-K, regressions) is required from #98 and #102. For #102 the delivered
evidence is the per-query report of `./gradlew :monada-evaluation:runForgeExperienceEvaluation -q` (section 22);
its quality values are EXPLORATORY, not a protected baseline.

## Documentation Updates

- `docs/adr/0004-embedded-execution-memory.md` (new).
- `docs/specs/execution-memory-contract-v1.md` (this file).
- `docs/architecture.md`, `docs/roadmap.md`, `docs/vision.md`.

## Risks / Edge Cases

- Content-derived atom IDs merge identical projection text; mitigated by the projection checkpoint.
- Ledger and projection can diverge; mitigated by explicit status and deterministic rebuild.
- Torn tails after crashes block the writer until an explicit repair exists; read-only access still works.
- Callers may supply inconsistent provenance or overlapping usage counters; Store validates structure, not
  truth, and reports unknowns instead of guessing.
- Single-writer locking depends on OS file-lock semantics; network filesystems are unsupported in v1.
