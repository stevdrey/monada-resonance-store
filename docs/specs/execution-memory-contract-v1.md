# Execution Memory Contract v1

Status: accepted contract, **not implemented**. Decision record:
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
- `monada-api`: yes (later) — `com.monada.api.execution` facade, recall, calculators, export (#97–#99, #101).
- `monada-evaluation`: yes (later) — `com.monada.evaluation.execution` comparison and end-to-end gates (#100, #102).
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

Identifier rules (`ScopeId`, `TaskId`, `ExecutionId`, `AttemptId`, `EventId`, artifact references):

- Caller-issued; the library never generates them.
- 1–128 Unicode code points, NFC as supplied (not re-normalized), no control characters, no leading or
  trailing whitespace. Validation happens before any I/O.
- Compared by exact `String.equals`; case-sensitive.
- Never derived from text hashes and never used as filesystem path segments.
- `EventId` is unique within a scope. Other IDs are unique within their parent (task within scope,
  execution within scope, attempt within execution).

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
  It must not contain raw TAB or LF; text fields are escaped or Base64-encoded. The exact payload codec
  (small tab-separated codec or canonical fixed-order JSON) is chosen in #96; a new serialization
  dependency requires justification there.

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

## 4. Event Kinds

| Kind | Purpose | Key payload |
| --- | --- | --- |
| `EXECUTION_STARTED` | Opens an execution | task summary, `sourceRevision`, `contextFingerprint`, `constraintsFingerprint`, `evaluationPolicy` (id, version, mandatory dimensions) |
| `ATTEMPT_STARTED` | Opens an attempt | attempt ordinal, `previousAttemptId?`, reason (`INITIAL`, `REPAIR`, `RETRY`, `REVIEW_FOLLOW_UP`) |
| `STAGE_RECORDED` | One stage of an attempt | stage name, `RouteDescriptor` (worker, provider, model, effort, billing mode), start/end, usage counters, artifact refs |
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
   a justification.
3. **Retrieval feedback** (`FeedbackEvent`): unchanged; usefulness of a recalled atom for a query. It is
   never written automatically from outcomes or evidence.

Derived acceptance (computed, never stored as a separate fact):

| Derived status | Condition |
| --- | --- |
| `VALIDATED_ACCEPTED` | Latest outcome `ACCEPTED` + origin `VALIDATED` + every mandatory dimension of the policy has latest-revision `PASS` or justified `NOT_APPLICABLE` evidence on the accepted attempt |
| `ACCEPTED_UNVALIDATED` | Latest outcome `ACCEPTED` but origin `IMPORTED_CLAIM`, or any mandatory dimension is `FAIL`, `UNKNOWN` or missing |
| `NOT_ACCEPTED` | Latest outcome `REJECTED`, `FAILED` or `CANCELLED` |
| `PENDING` | No outcome or latest outcome `PENDING` |

- `FAILED` and `CANCELLED` never imply acceptance.
- Renewed evidence is a new event or revision; prior evidence remains history.
- No unified quality score is computed; each dimension is reported individually with provenance.

## 9. Usage and Hypothetical Cost

`UsageCounter`: `kind` (`INPUT_TOKENS`, `CACHED_INPUT_TOKENS`, `OUTPUT_TOKENS`, `REASONING_TOKENS`,
`REQUESTS`, `TOOL_CALLS`), optional non-negative `long` value, provenance (`REPORTED`, `ESTIMATED`,
`UNKNOWN`), source. `UNKNOWN` has no value and is never treated as zero. Overlap rules (for example
whether cached input is included in input) are declared by the caller's price snapshot, not guessed.

`RouteDescriptor.billingMode`: `API_METERED`, `SUBSCRIPTION`, `LOCAL`, `UNKNOWN`.

`PriceSnapshot` (caller-supplied, versioned): provider, model, counter kind, currency (ISO 4217), price
per unit as `BigDecimal`, unit size (e.g. 1,000,000 tokens), effective date, source, assumptions text.

Rules:

- Hypothetical API-equivalent cost = Σ (counter value × unit price), computed with exact `BigDecimal`
  arithmetic; the final amount is rounded once with `HALF_EVEN` to the snapshot currency scale.
- It is labelled hypothetical for every billing mode. **Subscription usage is never presented as API
  billing.** An actual billed amount is reported only when the caller records one, and separately.
- Amounts in different currencies are never summed; each currency is reported on its own.
- Stage effort (sum of stage durations) is reported separately from elapsed wall-clock time
  (first start to last end) so that parallel stages are not double counted.

`CostToAcceptedOutcome` covers every event in the execution's attempt chain (failed attempts, repairs,
reviews, retries, QA) up to and including the `OUTCOME_RECORDED` event that establishes
`VALIDATED_ACCEPTED`. Later events are reported separately as post-acceptance activity. Events are
deduplicated by `eventId`; a correction replaces its target, never adds to it.

| Status | Condition |
| --- | --- |
| `AVAILABLE` | `VALIDATED_ACCEPTED` and every counter in the chain has a value and a matching price |
| `PARTIAL` | `VALIDATED_ACCEPTED` but some usage or price is missing; reports a lower bound plus missing reasons |
| `UNAVAILABLE` | No `VALIDATED_ACCEPTED` outcome (pending, rejected, failed, cancelled or unvalidated) |

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
- Retrieve the top `limit` atoms from the scope's projection, expand each to its refs, sort by similarity
  descending then by the canonical `ExperienceRef` string ascending, and truncate to `limit`.
- Each `ExperienceHit` carries the ref, similarity, projection version and covered ledger sequence.
- Similarity is resonance similarity, never quality or confidence. Missing refs are reported, never
  silently dropped. Retrieval feedback on projection atoms remains the explicit `feedback(...)` path.

## 12. History and Export

`history(scope, cursor, pageSize)`:

- `pageSize` must be 1–500.
- Events are returned in ledger sequence order, including every revision.
- The cursor is an opaque, stateless token (scope + last sequence + format version), so the same cursor
  always returns the same page.

`exportSamples(ExportRequest)` is read-only and versioned (`execution-sample/1`):

- The request names an explicit scope, a cutoff (ledger sequence or `recordedAt`), page size (1–500),
  cursor, evidence policy, and view (`ADAPTER_READY` or `DIAGNOSTIC`).
- One sample per execution at its latest revisions as of the cutoff. Each sample includes task/context
  descriptors, route descriptors, outcome, derived acceptance, per-dimension evidence, full-chain usage
  and cost status, exact refs and revisions, and the ledger checkpoint and projection version used.
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
| #95 | Immutable execution/evidence domain records | `monada-core` | #93 |
| #96 | Versioned append-only ledger, replay, integrity diagnostics | `monada-storage` | #93, #95 |
| #97 | `ExecutionMemory` facade: idempotent recording, revisions, history | `monada-api` | #94, #95, #96 |
| #98 | Scoped projections and bounded recall | `monada-api` | #97 |
| #99 | Attempt-chain usage and hypothetical `CostToAcceptedOutcome` | `monada-api` | #95, #97 |
| #100 | Evidence-gated pairwise comparison and Pareto diagnostics | `monada-evaluation` | #99 |
| #101 | Bounded provenance-preserving sample export | `monada-api` | #97, #98, #99, #100 |
| #102 | Reproducible end-to-end Forge experience evaluation and isolation gates | `monada-evaluation` | #94, #96–#101 |

Consumer integration inside Monada Forge and Monada Neuron is future cross-repository work and is not
part of this track.

## Implementation Boundaries

- New code only in the packages listed in ADR 0004; no changes to existing public signatures or defaults.
- Experimental behavior stays behind explicit options; quality evidence stays separate from protected
  correctness gates.
- Stable Java 27 features only; no preview or incubator flags; no incidental dependency upgrades.

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
