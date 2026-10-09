# Execution Memory Contract v1

Status: accepted contract. The immutable domain records (section 15, issue #95) and the ledger with
replay and read-only audit (section 16, issue #96) and the `ExecutionMemory` facade (section 17,
issue #97) are implemented; projections, calculators and export remain future work. Decision record:
[ADR 0004](../adr/0004-embedded-execution-memory.md). Issue: #93 (Forge Integration 1/10).

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

- `monada-core`: yes (later) — `com.monada.core.execution` immutable records (#95).
- `monada-encoder`: no — projections reuse the existing encoding unchanged.
- `monada-storage`: yes (later) — `com.monada.storage.execution` ledger, codec, replay, audit (#96).
- `monada-index`: no — projections reuse `LinearScanResonanceIndex` unchanged.
- `monada-learning`: no.
- `monada-api`: yes (later) — `com.monada.api.execution` facade, derived acceptance, recall, export (#97, #98, #101).
- `monada-evaluation`: yes (later) — `com.monada.evaluation.execution` usage/cost calculators, comparison,
  export annotations and end-to-end gates (#99, #100, #101, #102).
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
        └── projection/              # derived, rebuildable (section 11)
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
  nothing and fails cleanly when the root or scope does not exist.
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
| `VALIDATED_ACCEPTED` | Current outcome `ACCEPTED` + origin `VALIDATED` + every mandatory dimension has an effective observation that is `PASS` or justified `NOT_APPLICABLE` |
| `ACCEPTED_UNVALIDATED` | Current outcome `ACCEPTED` but origin `IMPORTED_CLAIM`, or some mandatory dimension's effective observation is `FAIL` or `UNKNOWN`, or it has none; reasons list each dimension |
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

The acceptance cutoff is the highest ledger sequence among the current `ACCEPTED` outcome event and the
effective mandatory observations that make it `VALIDATED_ACCEPTED`. `STAGE_RECORDED` events after that
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
- Comparison lives in `monada-evaluation` only (#100).

## 11. Projections and Bounded Recall

- Each scope has its own projection store (`projection/memory/`), a standard `MonadaMemory` opened with
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
  silently dropped. Retrieval feedback on projection atoms remains the explicit `feedback(...)` path.

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

`exportSamples(ExportRequest)` is read-only and versioned (`execution-sample/1`):

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
| Protected baselines (text `default-databases-v1`, speech `PROTECTED`) | CI-enforced | Unchanged; execution evaluation adds no protected gate until #102 decides |
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
| #99 | Attempt-chain usage and hypothetical `CostToAcceptedOutcome` calculators | `monada-evaluation` | #95, #97 |
| #100 | Evidence-gated pairwise comparison and Pareto diagnostics | `monada-evaluation` | #99 |
| #101 | Bounded provenance-preserving sample export (+ evaluation annotations) | `monada-api`, `monada-evaluation` | #97, #98, #99, #100 |
| #102 | Reproducible end-to-end Forge experience evaluation and isolation gates | `monada-evaluation` | #94, #96–#101 |

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
afterwards by #97 (section 17). Out of scope and still future work: projections, recall, export, segment
rollover, compaction and explicit repair.

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

Package `com.monada.api.execution` in `monada-api`. It is ledger-backed only: no projections, recall,
cost calculators, comparison or export, and no mapping of outcomes to ranking feedback.
`MonadaMemory` and its lifecycle are unchanged.

### 17.1 Ownership and threading

- `ExecutionMemory.open(root, scope[, config])` binds **one scope per instance** and takes the exclusive
  writer lock of `root` (the ledger lock is per root). A second open fails immediately with the unchecked
  `ExecutionMemoryLockedException`. Reading other scopes of the same root needs a separate instance after
  close; a multi-scope writer is future work.
- `ExecutionMemory.openReadOnly(root, scope[, config])` returns a **snapshot** of the valid ledger prefix at
  open time: no lock, nothing created, usable next to a live writer. Later appends are not visible; the host
  opens a new read-only instance to see them. `record` throws `UnsupportedOperationException`. A missing root
  or manifest fails with `UncheckedIOException`; a missing scope is an empty history. Lifecycle rules are the
  same as for the writable instance.
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
| `loadEvent(scope, eventId[, revision])` | `Optional<LedgerRecord>`; `eventId` may name any member of a correction chain; revision 1 is the original |
| `history(scope[, cursor], pageSize)` | `HistoryPage(records, highWatermark, hasMore, next)` |

Outcomes of `record`: identical retry -> `IDEMPOTENT` (nothing written); same id with a different payload,
duplicate execution/attempt start, reused ordinal, or a correction that is not target revision + 1 or not of
the latest revision -> `CONFLICT` (nothing written). Wrong scope, missing references, events after
`ATTEMPT_FINISHED`, and oversized records throw `IllegalArgumentException` before any I/O. The lifecycle check
comes first (`IllegalStateException` after close, even for a null argument); a null argument on an open
instance throws `NullPointerException`.

### 17.3 Acceptance, revisions, history

- `ExecutionView.acceptance()` is derived on every read (section 8), never stored. `VALIDATED_ACCEPTED`
  requires a `VALIDATED` accepted outcome and passing (or justified not-applicable) evidence of the accepted
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

None for #93. Retrieval evidence (per-query top-K, regressions) is required from #98 and #102.

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
