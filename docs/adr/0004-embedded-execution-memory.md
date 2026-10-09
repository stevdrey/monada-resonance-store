# ADR 0004: Embedded Execution Memory

## Status

Accepted

This ADR fixes ownership and contracts. Implemented so far: the domain records (#95), the append-only
ledger (#96) and the `ExecutionMemory` facade for recording, loading and history (#97). Projections, recall,
cost calculators, comparison and export are still planned; the implementation sequence is tracked in
[`docs/roadmap.md`](../roadmap.md) (Forge Integration track, issues #93–#102) and the normative v1 contract
lives in [`docs/specs/execution-memory-contract-v1.md`](../specs/execution-memory-contract-v1.md) (section 17
describes the shipped facade).

## Context

Monada Forge coordinates coding agents and wants to reuse prior work: context from similar tasks,
routing informed by measured outcomes, comparison of quality evidence, and token usage with
hypothetical API-cost estimates. Monada Neuron wants the same history as neutral samples for its
adaptation and routing logic. [ADR 0002](0002-memory-owned-by-resonance-store.md) already assigns
persistence, encoding and retrieval to this repository.

The current text memory cannot hold that history safely:

- `MonadaMemory` offers only `remember(String[, aliases])`, `resonate(String)` and ranking `feedback(...)`;
  it has no execution-history operations and no `close()`.
- `FileAtomStore` rejects any non-empty `KnowledgeAtom.metadata` ("FileAtomStore does not persist atom
  metadata").
- Atom IDs are `UUID.nameUUIDFromBytes(content UTF-8)` (content-derived), so two different attempts with
  identical summary text would collapse into one atom.
- `FeedbackEvent` describes whether a retrieved atom was useful for a query. It is not an execution
  outcome and must not become one.
- Storage has no file locking; the single-writer assumption is implicit.

Forge's own Product-Vision lists outcome-informed routing and hypothetical API-cost estimates as future
work and states that subscription activity must not be presented as actual API billing. Forge and Neuron
are consumers; this ADR does not authorize changes in their repositories.

## Decision

1. **Embedded library only.** Execution memory is an in-process Java 27 library extension. No HTTP,
   MCP or other server, no network access, no provider SDKs, no hidden globals.
2. **Authoritative append-only ledger.** Execution facts are persisted in a dedicated, versioned,
   append-only execution ledger with its own manifest and directory. It never reuses or rewrites the
   legacy `manifest.json`, atom log, vector segment, vector map or feedback log.
3. **Derived projections.** Searchable text is a projection derived from ledger events into the
   existing `MonadaMemory` encoding and ranking (exact-query defaults). Projections are rebuildable,
   versioned and never authoritative; a recall hit always resolves to exact ledger references.
4. **Caller-owned identity, time and scope.** Scope, task, execution, attempt, event and artifact IDs and
   all timestamps are supplied by the host. The library never calls `Instant.now()` or random generators
   for persisted facts and never derives execution identity from text hashes.
5. **Explicit lifecycle.** A ledger has exactly one writable owner at a time (enforced lock), opened and
   closed explicitly by the host. Read-only access never creates or modifies files.
6. **Separate concepts.** Execution outcomes, multidimensional quality evidence, retrieval similarity
   and retrieval feedback are distinct types with distinct storage. No unified quality score; similarity
   is not confidence; low cost never substitutes for validated quality.
7. **Evidence, not collection.** v1 accepts only caller-supplied structured summaries, observations,
   usage counters and opaque artifact references. The library never reads repositories, logs, secrets,
   transcripts or artifacts on its own.
8. **Cost honesty.** Usage is recorded with provenance (reported, estimated, unknown). Hypothetical
   API-equivalent cost is computed only from caller-supplied price snapshots and is reported separately
   from billed amounts. Subscription usage is never presented as API billing. Unknown is never zero.
9. **Conservative compatibility.** Existing public API signatures, defaults (`ExactQueryKeyStrategy`,
   feedback-aware ranking), protected baselines and all legacy storage formats remain unchanged.
   Experimental evidence (normalized stress, speech hybrid) does not become a supported default.

## Ownership

Monada Resonance Store owns:

- the execution ledger format, its manifest, replay and integrity diagnostics;
- identity, idempotency, conflict and revision rules for recorded facts;
- ledger-derived projections, scoped bounded recall and their reconciliation;
- derived acceptance from structural evidence-selection rules (in `monada-api`);
- deterministic evaluation-side calculators (attempt-chain usage, hypothetical `CostToAcceptedOutcome`)
  in `monada-evaluation`;
- evaluation-only comparison and Pareto diagnostics;
- read-only, versioned sample export.

Monada Neuron owns:

- adaptation, routing and learning policies that consume exported samples;
- deciding how (or whether) recall results influence planning;
- any model or provider integration.

Monada Forge owns:

- task selection, planning and execution of agents;
- collecting, validating and judging evidence (tests, reviews, gates);
- deciding which summaries, observations and artifact references are recorded;
- issuing scope/task/execution/attempt/event IDs and timestamps;
- price snapshots and the meaning of its evaluation policies.

The memory engine contains no orchestration: it never schedules work, retries attempts, judges
outcomes or decides routes.

## Proposed Public Surface

Names are binding intent for the follow-up issues; exact signatures are finalized in #95 (domain
records), #96 (ledger), #97 (facade), #98 (projections/recall), #99 (calculators), #100 (comparison) and
#101 (export). New code lives in dedicated packages inside existing modules:

| Package | Module | Contents |
| --- | --- | --- |
| `com.monada.core.execution` | `monada-core` | Immutable IDs and domain records |
| `com.monada.storage.execution` | `monada-storage` | Ledger manifest, record codec, replay, audit |
| `com.monada.api.execution` | `monada-api` | `ExecutionMemory` facade, derived acceptance, history (implemented, #97); recall, export (planned) |
| `com.monada.evaluation.execution` | `monada-evaluation` | Usage/cost calculators, comparison/Pareto reports, export annotations (evaluation only) |

Core types (`com.monada.core.execution`): `ScopeId`, `TaskId`, `ExecutionId`, `AttemptId`, `EventId`,
`ExecutionEvent` (sealed by event kind), `EvaluationPolicy`, `RouteDescriptor`, `Outcome`, `QualityObservation`,
`UsageCounter`, `PriceSnapshot`, `ArtifactRef`, `ExperienceRef`.

Facade operations (`com.monada.api.execution.ExecutionMemory`, `AutoCloseable`):

| Operation | Result |
| --- | --- |
| `open(Path root, ScopeId scope, ExecutionMemoryConfig config)` | Exclusive writable instance for one scope (implemented) |
| `openReadOnly(Path root, ScopeId scope, ExecutionMemoryConfig config)` | Lock-free read-only snapshot of one scope; creates nothing (implemented) |
| `record(ExecutionEvent event)` | `RecordResult` = `APPENDED`, `IDEMPOTENT` or `CONFLICT` |
| `loadExecution(ScopeId, ExecutionId)` | `Optional<ExecutionView>` at latest revisions |
| `loadAttempt(ScopeId, ExecutionId, AttemptId)` | `Optional<AttemptView>` |
| `loadEvent(ScopeId, EventId[, int revision])` | `Optional<HistoryEntry>`, exact revision or latest |
| `history(ScopeId[, HistoryCursor], int pageSize)` | `HistoryPage` in ledger sequence order, fixed snapshot high-watermark |
| `recall(ScopeId, String query, int limit)` | `ExperienceRecall` of `ExperienceHit` (planned, #98) |
| `projectionStatus(ScopeId)` / `rebuildProjection(ScopeId)` | Explicit reconciliation (planned, #98) |
| `exportSamples(ExportRequest)` | `ExportPage` (read-only, versioned) (planned, #101) |

The facade has no cost or comparison operation. Evaluation-side code (`monada-evaluation`, #99/#100)
reads facade views and computes `ExecutionSummary` / `CostToAcceptedOutcome` and comparisons;
`monada-api` never depends on `monada-evaluation`.

Example host usage (compiles against the shipped #97 surface; `recall` and `exportSamples` are planned
and therefore not shown):

```java
ScopeId scope = ScopeId.of("forge-workspace-7f3a");              // opaque, caller-issued
try (ExecutionMemory memory = ExecutionMemory.open(root, scope, ExecutionMemoryConfig.defaults())) {
    Instant now = hostClock.instant();                           // caller-owned time

    RecordResult started = memory.record(ExecutionEvent.executionStarted(
            EventId.of("evt-0001"), scope, TaskId.of("issue-93"), ExecutionId.of("exec-1"),
            new SourceProvenance("e823256e3da71f15a73e06e0ca6d54d7f6d6ac87",
                    "ctx-sha256:9b1c...", "constraints-sha256:41aa..."),
            new EvaluationPolicy("forge-gates", "1", Set.of(QualityDimension.TESTS)),
            "Document execution-memory ownership and v1 contract", now));

    // ... attempt, stage and evidence events recorded the same way ...

    memory.record(ExecutionEvent.outcomeRecorded(
            EventId.of("evt-0009"), scope, TaskId.of("issue-93"), ExecutionId.of("exec-1"),
            Outcome.accepted(OutcomeOrigin.VALIDATED, AttemptId.of("attempt-2")),
            hostClock.instant()));

    // Acceptance is VALIDATED_ACCEPTED only if attempt-2 has an effective TESTS observation
    // (EVIDENCE_RECORDED, policy "forge-gates" v"1") that is PASS or justified NOT_APPLICABLE.
    ExecutionView view = memory.loadExecution(scope, ExecutionId.of("exec-1")).orElseThrow();
    DerivedAcceptance acceptance = view.acceptance();
    HistoryPage page = memory.history(scope, null, 50);
}

// Another component can inspect the same scope while the writer is open (snapshot at open):
try (ExecutionMemory reader = ExecutionMemory.openReadOnly(root, scope)) {
    reader.loadAttempt(scope, ExecutionId.of("exec-1"), AttemptId.of("attempt-2"));
}
```

## Consequences

- Forge and Neuron can rely on one local, inspectable source of execution history without Store
  absorbing orchestration or judgment.
- Execution memory adds new files and packages but leaves every legacy format, default and public
  signature unchanged; existing stores open exactly as before.
- Identical summary text from distinct attempts stays distinguishable because identity lives in the
  ledger, not in `KnowledgeAtom.id`.
- Projections may be stale relative to the ledger; staleness is explicit and repaired only by an
  explicit, deterministic rebuild.
- Cost and quality reports may be partial or inconclusive by design; consumers must handle
  `UNKNOWN`, `PARTIAL`, `UNAVAILABLE`, `NOT_COMPARABLE` and `INCONCLUSIVE` states.
- Single-writer ownership simplifies durability but excludes concurrent writers until a later ADR.

## Review Checklist

A change respects this ADR when:

- execution memory stays an embedded library without servers, network access or provider SDKs;
- the ledger remains authoritative and separate from atom/vector/feedback files;
- IDs and timestamps come from the caller, and identity is never derived from text hashes;
- outcomes, quality evidence, retrieval similarity and retrieval feedback stay distinct;
- hypothetical cost is never presented as billing, and unknown values are never treated as zero;
- no legacy format, default, protected baseline or existing public signature changes;
- the memory engine performs no orchestration, collection or judgment.
