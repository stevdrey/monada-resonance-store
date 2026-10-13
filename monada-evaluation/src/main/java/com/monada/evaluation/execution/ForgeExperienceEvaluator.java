package com.monada.evaluation.execution;

import com.monada.api.execution.AttemptView;
import com.monada.api.execution.DerivedAcceptance;
import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.ExperienceHit;
import com.monada.api.execution.ExperienceRecall;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.api.execution.HistoryEntry;
import com.monada.api.execution.ProjectionStatus;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.UsageKind;
import com.monada.evaluation.execution.Comparability.Mismatch;
import com.monada.evaluation.execution.ComparisonPolicy.Direction;
import com.monada.evaluation.execution.ComparisonPolicy.Metric;
import com.monada.evaluation.execution.ComparisonPolicy.Objective;
import com.monada.evaluation.execution.ForgeEvaluationReport.ForgeCorpusLine;
import com.monada.evaluation.execution.ForgeEvaluationReport.RankedHit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs the complete Forge experience flow through public APIs only (issue #102):
 * record, close, reopen, reconcile the projection, exact load, scoped recall, usage and cost summary, pairwise
 * comparison and export. It then checks the hard gates and measures the EXPLORATORY retrieval quality.
 *
 * <p>The whole flow runs twice in two fresh directories; the second run only feeds the determinism gate. It uses
 * no network, no provider and no Forge installation.
 */
public final class ForgeExperienceEvaluator {
    private static final int EXPORT_PAGE_SIZE = 10;

    private ForgeExperienceEvaluator() {
    }

    /** Evaluates {@code fixture} using fresh stores below {@code workDir} (created if missing). */
    public static ForgeEvaluationReport evaluate(ForgeFixture fixture, Path workDir) {
        Run first = runOnce(fixture, workDir.resolve("run-1"));
        try {
            Run second = runOnce(fixture, workDir.resolve("run-2"));
            try {
                List<ForgeGate> gates = new ArrayList<>(first.gates);
                gates.add(determinismGate(first, second));
                List<ForgeCorpusLine> corpus = corpus(fixture, first.snapshot);
                return new ForgeEvaluationReport(fixture.version(), fixture.provenance(), fixture.attempts().size(),
                        fixture.scopeIds().size(), first.queries, gates, corpus, first.elapsed);
            } finally {
                second.snapshot.close();
            }
        } finally {
            first.snapshot.close();
        }
    }

    // --- one complete run -----------------------------------------------------------------------------

    private record Run(Snapshot snapshot, List<ForgeQueryResult> queries, List<ForgeGate> gates,
                       Map<String, Long> elapsed, String fingerprint) {
    }

    private static Run runOnce(ForgeFixture fixture, Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Long> elapsed = new LinkedHashMap<>();
        List<String> recordViolations = new ArrayList<>();

        long t = System.nanoTime();
        for (String scopeKey : fixture.scopeIds().keySet()) {
            try (ExecutionMemory memory = ExecutionMemory.open(root, ForgeFixtureRecorder.scope(fixture, scopeKey))) {
                List<RecordResult> results = ForgeFixtureRecorder.recordScope(memory, fixture, scopeKey);
                for (RecordResult r : results) {
                    if (r.status() != RecordResult.Status.APPENDED) {
                        recordViolations.add(scopeKey + ": first recording was " + r.status() + " " + r.reason());
                    }
                }
            }
        }
        elapsed.put("record", System.nanoTime() - t);

        t = System.nanoTime();
        List<String> reconcileEvidence = new ArrayList<>();
        List<String> reconcileViolations = new ArrayList<>();
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ScopeId scope = ForgeFixtureRecorder.scope(fixture, scopeKey);
            try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) { // reopen
                ProjectionStatus before = memory.projectionStatus(scope);
                ProjectionStatus after = memory.rebuildProjection(scope);
                reconcileEvidence.add(scopeKey + ": projection " + before.state() + " -> " + after.state()
                        + " covering " + after.coveredSequence() + "/" + after.ledgerSequence());
                if (!after.isCurrent() || after.coveredSequence() != after.ledgerSequence()) {
                    reconcileViolations.add(scopeKey + ": projection not current after rebuild: " + after);
                }
            }
        }
        elapsed.put("reopen+reconcile", System.nanoTime() - t);

        Snapshot a = snapshot(fixture, root, elapsed);

        // Replay: reopen for writing, record everything again, reconcile again, take a second snapshot.
        t = System.nanoTime();
        List<String> replayViolations = new ArrayList<>();
        int replayed = 0;
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ScopeId scope = ForgeFixtureRecorder.scope(fixture, scopeKey);
            try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
                for (RecordResult r : ForgeFixtureRecorder.recordScope(memory, fixture, scopeKey)) {
                    replayed++;
                    if (r.status() != RecordResult.Status.IDEMPOTENT) {
                        replayViolations.add(scopeKey + ": replayed event was " + r.status() + " " + r.reason());
                    }
                }
                memory.rebuildProjection(scope);
            }
        }
        elapsed.put("replay", System.nanoTime() - t);
        Snapshot b = snapshot(fixture, root, new LinkedHashMap<>());

        List<ForgeGate> gates = new ArrayList<>();
        gates.add(isolationGate(fixture, a));
        gates.add(successGate(fixture, a));
        gates.add(referenceGate(fixture, a));
        gates.add(replayGate(fixture, a, b, replayed, recordViolations, reconcileViolations, replayViolations,
                reconcileEvidence));
        gates.add(accountingGate(fixture, a));
        gates.add(comparisonGate(fixture, a));

        List<ForgeQueryResult> queries = new ArrayList<>(a.results.values());
        return new Run(a, queries, gates, elapsed, a.fingerprint());
    }

    // --- snapshot of a stable store -------------------------------------------------------------------

    /** Everything observed through public read APIs on one stable store. */
    private static final class Snapshot {
        final Map<String, ExecutionMemory> memories = new LinkedHashMap<>();
        final Map<String, Map<String, ExecutionView>> views = new LinkedHashMap<>(); // scope key -> execution -> view
        final Map<String, ExperienceExportReport> exports = new LinkedHashMap<>();
        final Map<String, ForgeQueryResult> results = new LinkedHashMap<>();
        final Map<String, List<ExperienceHit>> hits = new LinkedHashMap<>(); // query id -> raw hits
        final Map<String, ProjectionStatus> status = new LinkedHashMap<>();
        final Map<String, ExecutionSummary> summaries = new LinkedHashMap<>(); // execution id -> summary
        final PriceBook prices;

        Snapshot(PriceBook prices) {
            this.prices = prices;
        }

        String fingerprint() {
            StringBuilder out = new StringBuilder();
            exports.forEach((scope, e) -> out.append(scope).append(' ').append(e.exportDigest()).append('\n'));
            results.forEach((id, r) -> {
                out.append(id);
                hits.get(id).forEach(h -> out.append(' ').append(ForgeFixtureRecorder.experienceId(
                        h.ref().execution().value(), h.ref().attempt().orElseThrow())).append('@')
                        .append(h.ref().revision()).append(':').append(h.similarity()));
                out.append('\n');
            });
            return out.toString();
        }

        void close() {
            memories.values().forEach(ExecutionMemory::close);
        }
    }

    private static Snapshot snapshot(ForgeFixture fixture, Path root, Map<String, Long> elapsed) {
        Snapshot s = new Snapshot(PriceBook.of(List.of(ForgeFixtureRecorder.priceSnapshot(fixture))));
        try {
            for (String scopeKey : fixture.scopeIds().keySet()) {
                s.memories.put(scopeKey, ExecutionMemory.openReadOnly(root,
                        ForgeFixtureRecorder.scope(fixture, scopeKey)));
            }
            long t = System.nanoTime();
            for (String scopeKey : fixture.scopeIds().keySet()) {
                ScopeId scope = ForgeFixtureRecorder.scope(fixture, scopeKey);
                ExecutionMemory memory = s.memories.get(scopeKey);
                s.status.put(scopeKey, memory.projectionStatus(scope));
                Map<String, ExecutionView> views = new LinkedHashMap<>();
                for (ForgeFixture.Execution e : fixture.executionsOf(scopeKey)) {
                    ExecutionView view = memory.loadExecution(scope, ExecutionId.of(e.id())).orElseThrow(
                            () -> new IllegalStateException("execution not loadable after reopen: " + e.id()));
                    views.put(e.id(), view);
                    s.summaries.put(e.id(), ExecutionSummaryCalculator.summarize(view, s.prices, List.of()));
                }
                s.views.put(scopeKey, views);
            }
            elapsed.put("exact load+summary", System.nanoTime() - t);

            t = System.nanoTime();
            for (String scopeKey : fixture.scopeIds().keySet()) {
                ScopeId scope = ForgeFixtureRecorder.scope(fixture, scopeKey);
                s.exports.put(scopeKey, ExperienceExportReport.build(s.memories.get(scopeKey),
                        ExportRequest.first(scope, ExportCutoff.latest(), EXPORT_PAGE_SIZE, ExportView.ADAPTER_READY,
                                ExportEvidencePolicy.executionPolicyOnly()), s.prices, Optional.of(policy(fixture))));
            }
            elapsed.put("export", System.nanoTime() - t);

            t = System.nanoTime();
            Map<String, String> labels = new LinkedHashMap<>();
            s.exports.values().forEach(e -> e.samples().forEach(a -> labels.put(a.sample().executionId().value(),
                    a.sample().label().name())));
            Map<String, String> results = attemptResults(s);
            for (ForgeFixture.Query q : fixture.queries()) {
                ScopeId scope = ForgeFixtureRecorder.scope(fixture, q.scopeKey());
                ExperienceRecall recall = s.memories.get(q.scopeKey()).recall(scope, q.text(), fixture.recallLimit());
                s.hits.put(q.id(), recall.hits());
                List<RankedHit> ranked = new ArrayList<>();
                int rank = 0;
                for (ExperienceHit h : recall.hits()) {
                    rank++;
                    String id = ForgeFixtureRecorder.experienceId(h.ref().execution().value(),
                            h.ref().attempt().orElseThrow());
                    ranked.add(new RankedHit(rank, id, h.similarity(), results.getOrDefault(id, "UNKNOWN"),
                            labels.getOrDefault(h.ref().execution().value(), "UNKNOWN"), h.ref().revision(),
                            q.relevant().contains(id)));
                }
                s.results.put(q.id(), ForgeQueryResult.of(q, recall.status().state().name(), ranked, fixture.ks()));
            }
            elapsed.put("recall", System.nanoTime() - t);
            return s;
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /** Experience id to the result of that attempt, read from the exported samples. */
    private static Map<String, String> attemptResults(Snapshot s) {
        Map<String, String> results = new LinkedHashMap<>();
        s.exports.values().forEach(e -> e.samples().forEach(a -> a.sample().attempts().forEach(at ->
                results.put(ForgeFixtureRecorder.experienceId(a.sample().executionId().value(), at.id()),
                        at.result().map(Enum::name).orElse("UNFINISHED")))));
        return results;
    }

    static ComparisonPolicy policy(ForgeFixture fixture) {
        return new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
                new Objective("cost", new Metric.Cost(Currency.getInstance(fixture.price().currency())),
                        Direction.MINIMIZE, BigDecimal.ZERO),
                new Objective("effort", new Metric.Effort(), Direction.MINIMIZE, BigDecimal.ZERO)));
    }

    // --- G1: scope isolation --------------------------------------------------------------------------

    private static ForgeGate isolationGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int hitCount = 0;
        for (ForgeFixture.Query q : fixture.queries()) {
            hitCount += s.hits.get(q.id()).size();
            violations.addAll(leakageViolations(fixture, q, s.hits.get(q.id())));
        }
        int probes = 0;
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ExecutionMemory memory = s.memories.get(scopeKey);
            for (String otherKey : fixture.scopeIds().keySet()) {
                if (otherKey.equals(scopeKey)) {
                    continue;
                }
                ScopeId other = ForgeFixtureRecorder.scope(fixture, otherKey);
                probes += 3;
                violations.addAll(mustReject(scopeKey + " memory, recall of " + otherKey,
                        () -> memory.recall(other, "invoice totals", 5)));
                violations.addAll(mustReject(scopeKey + " memory, export of " + otherKey,
                        () -> memory.exportSamples(ExportRequest.firstPage(other, 5))));
                violations.addAll(mustReject(scopeKey + " memory, load of " + otherKey,
                        () -> memory.loadExecution(other, ExecutionId.of(fixture.executionsOf(otherKey).get(0).id()))));
            }
            for (ExperienceSample sample : s.exports.get(scopeKey).samples().stream().map(AnnotatedSample::sample)
                    .toList()) {
                if (!sample.execution().scope().equals(ForgeFixtureRecorder.scope(fixture, scopeKey))
                        || !fixture.execution(sample.executionId().value()).scopeKey().equals(scopeKey)) {
                    violations.add(scopeKey + ": export holds a foreign execution " + sample.executionId().value());
                }
            }
            Set<String> exported = new TreeSet<>();
            s.exports.get(scopeKey).samples().forEach(a -> exported.add(a.sample().executionId().value()));
            Set<String> expected = new TreeSet<>(fixture.executionsOf(scopeKey).stream()
                    .map(ForgeFixture.Execution::id).toList());
            if (!exported.equals(expected)) {
                violations.add(scopeKey + ": exported executions " + exported + " differ from " + expected);
            }
        }
        List<String> evidence = List.of("foreign-scope leakage violations: " + violations.size(),
                "hits checked: " + hitCount + " over " + fixture.queries().size() + " queries; cross-scope probes "
                        + "rejected: " + probes);
        return ForgeGate.of(ForgeGate.SCOPE_ISOLATION, "zero foreign-scope leakage in recall, load and export",
                violations, evidence);
    }

    /**
     * Violations of the isolation contract for the hits one query received: every hit must belong to the queried
     * scope and to an execution of the queried project; a blank query must have no hit at all.
     */
    static List<String> leakageViolations(ForgeFixture fixture, ForgeFixture.Query q, List<ExperienceHit> hits) {
        List<String> violations = new ArrayList<>();
        ScopeId scope = ForgeFixtureRecorder.scope(fixture, q.scopeKey());
        for (ExperienceHit h : hits) {
            if (q.kind() == ForgeFixture.QueryKind.BLANK) {
                violations.add(q.id() + ": a blank query returned hits");
            }
            if (!h.ref().scope().equals(scope)) {
                violations.add(q.id() + ": foreign-scope hit " + h.ref().scope().value());
            }
            String exec = h.ref().execution().value();
            if (!fixture.execution(exec).scopeKey().equals(q.scopeKey())) {
                violations.add(q.id() + ": hit on an execution of another project: " + exec);
            }
        }
        return violations;
    }

    private static List<String> mustReject(String what, Runnable call) {
        try {
            call.run();
            return List.of(what + " was served instead of being rejected");
        } catch (IllegalArgumentException expected) {
            return List.of();
        }
    }

    // --- G2: no invalid success labels ----------------------------------------------------------------

    private static ForgeGate successGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int successes = 0;
        int checked = 0;
        for (String scopeKey : fixture.scopeIds().keySet()) {
            for (AnnotatedSample annotated : s.exports.get(scopeKey).samples()) {
                ExperienceSample sample = annotated.sample();
                String exec = sample.executionId().value();
                ForgeFixture.Execution expected = fixture.execution(exec);
                checked++;
                if (!sample.label().name().equals(expected.expectedLabel())) {
                    violations.add(exec + ": label " + sample.label() + " but judged " + expected.expectedLabel());
                }
                if (!sample.acceptance().name().equals(expected.expectedAcceptance())) {
                    violations.add(exec + ": acceptance " + sample.acceptance() + " but judged "
                            + expected.expectedAcceptance());
                }
                if (sample.label() == ExperienceSample.Label.SUCCESS) {
                    successes++;
                    if (sample.acceptance() != DerivedAcceptance.Status.VALIDATED_ACCEPTED) {
                        violations.add(exec + ": SUCCESS without a validated acceptance (" + sample.acceptance() + ")");
                    }
                    if (!sample.eligibility().eligible()) {
                        violations.add(exec + ": SUCCESS on an ineligible sample");
                    }
                    for (ExperienceSample.Gate g : sample.gates()) {
                        if (g.required() && g.state() != ExperienceSample.GateState.PASS
                                && g.state() != ExperienceSample.GateState.NOT_APPLICABLE) {
                            violations.add(exec + ": SUCCESS although required gate " + g.dimension() + " is "
                                    + g.state());
                        }
                    }
                    Integer ordinal = fixture.expectations().successOrdinals().get(exec);
                    if (ordinal != null && (sample.successOrdinal().isEmpty()
                            || sample.successOrdinal().getAsInt() != ordinal)) {
                        violations.add(exec + ": success ordinal " + sample.successOrdinal() + " but judged " + ordinal);
                    }
                } else if (annotated.cost().status() != CostToAcceptedOutcome.Status.UNAVAILABLE) {
                    violations.add(exec + ": non-success sample carries a cost to an accepted outcome");
                }
            }
        }
        long expectedSuccesses = fixture.executions().stream().filter(e -> e.expectedLabel().equals("SUCCESS")).count();
        if (successes != expectedSuccesses) {
            violations.add("SUCCESS labels: " + successes + " but judged " + expectedSuccesses);
        }
        // Recall may return a failed or unvalidated experience; it must then be labelled as such.
        for (ForgeQueryResult q : s.results.values()) {
            for (RankedHit h : q.ranked()) {
                String exec = h.experienceId().substring(0, h.experienceId().indexOf('/'));
                if (h.label().equals("SUCCESS") && !fixture.execution(exec).expectedLabel().equals("SUCCESS")) {
                    violations.add(q.query().id() + ": hit " + h.experienceId() + " is shown as SUCCESS");
                }
            }
        }
        List<String> evidence = List.of("samples checked: " + checked + "; SUCCESS labels: " + successes
                + " (judged " + expectedSuccesses + "); invalid success labels: " + violations.size());
        return ForgeGate.of(ForgeGate.NO_INVALID_SUCCESS, "no invalid success label; labels equal the judged labels",
                violations, evidence);
    }

    // --- G3: exact reference consistency --------------------------------------------------------------

    private static ForgeGate referenceGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int hitsChecked = 0;
        int refsChecked = 0;
        for (ForgeFixture.Query q : fixture.queries()) {
            ScopeId scope = ForgeFixtureRecorder.scope(fixture, q.scopeKey());
            ExecutionMemory memory = s.memories.get(q.scopeKey());
            for (ExperienceHit h : s.hits.get(q.id())) {
                hitsChecked++;
                ExperienceRef ref = h.ref();
                Optional<HistoryEntry> exact = memory.loadEvent(scope, ref.eventId(), ref.revision());
                if (exact.isEmpty() || !exact.get().equals(h.event())) {
                    violations.add(q.id() + ": hit " + ref.eventId().value() + " r" + ref.revision()
                            + " does not equal the exact ledger load");
                }
                AttemptId attempt = ref.attempt().orElse(null);
                if (attempt == null) {
                    violations.add(q.id() + ": hit without an attempt");
                    continue;
                }
                String id = ForgeFixtureRecorder.experienceId(ref.execution().value(), attempt);
                ForgeFixture.Attempt fixtureAttempt = fixture.attempts().stream()
                        .filter(a -> a.experienceId().equals(id)).findFirst().orElse(null);
                if (fixtureAttempt == null) {
                    violations.add(q.id() + ": hit " + id + " is not in the fixture");
                    continue;
                }
                int expectedRevision = fixtureAttempt.revised() ? 2 : 1;
                if (ref.revision() != expectedRevision) {
                    violations.add(q.id() + ": " + id + " recalled at revision " + ref.revision() + ", latest is "
                            + expectedRevision);
                }
                Optional<AttemptView> view = memory.loadAttempt(scope, ref.execution(), attempt);
                String expectedSolution = fixtureAttempt.revised() ? fixtureAttempt.revisedSolution()
                        : fixtureAttempt.solution();
                if (view.isEmpty() || !view.get().solutionSummary().equals(Optional.of(expectedSolution))) {
                    violations.add(q.id() + ": " + id + " loads a different solution than its latest revision");
                }
            }
        }
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ScopeId scope = ForgeFixtureRecorder.scope(fixture, scopeKey);
            ExecutionMemory memory = s.memories.get(scopeKey);
            for (AnnotatedSample annotated : s.exports.get(scopeKey).samples()) {
                ExperienceSample sample = annotated.sample();
                List<ExperienceRef> refs = new ArrayList<>();
                refs.add(sample.execution());
                for (ExperienceSample.SampleAttempt a : sample.attempts()) {
                    refs.add(a.start());
                    a.finish().ifPresent(refs::add);
                    a.stages().forEach(st -> refs.add(st.ref()));
                    a.evidence().forEach(ev -> refs.add(ev.ref()));
                }
                for (ExperienceRef ref : refs) {
                    refsChecked++;
                    Optional<HistoryEntry> exact = memory.loadEvent(scope, ref.eventId(), ref.revision());
                    if (exact.isEmpty()) {
                        violations.add(sample.executionId().value() + ": export ref " + ref.eventId().value() + " r"
                                + ref.revision() + " does not resolve");
                    } else if (!exact.get().event().envelope().executionId().equals(sample.executionId())) {
                        violations.add(sample.executionId().value() + ": export ref " + ref.eventId().value()
                                + " resolves to another execution");
                    }
                }
                for (ForgeFixture.Attempt fa : fixture.attemptsOf(sample.executionId().value())) {
                    sample.attempts().stream().filter(a -> a.ordinal() == fa.ordinal()).findFirst().ifPresentOrElse(a -> {
                        int expected = fa.revised() ? 2 : 1;
                        if (a.finish().isEmpty() || a.finish().get().revision() != expected) {
                            violations.add(fa.experienceId() + ": exported finish is not at revision " + expected);
                        }
                    }, () -> violations.add(fa.experienceId() + ": attempt missing from the export"));
                }
            }
        }
        List<String> evidence = List.of("recalled hits resolved exactly: " + hitsChecked + "; export references "
                + "resolved: " + refsChecked + "; inconsistent references: " + violations.size());
        return ForgeGate.of(ForgeGate.EXACT_REFERENCES,
                "every recalled and exported reference loads the exact ledger event at its latest revision",
                violations, evidence);
    }

    // --- G4: replay and idempotency -------------------------------------------------------------------

    private static ForgeGate replayGate(ForgeFixture fixture, Snapshot a, Snapshot b, int replayed,
                                        List<String> recordViolations, List<String> reconcileViolations,
                                        List<String> replayViolations, List<String> reconcileEvidence) {
        List<String> violations = new ArrayList<>(recordViolations);
        violations.addAll(reconcileViolations);
        violations.addAll(replayViolations);
        try {
            for (String scopeKey : fixture.scopeIds().keySet()) {
                if (!a.views.get(scopeKey).equals(b.views.get(scopeKey))) {
                    violations.add(scopeKey + ": execution views changed after replay");
                }
                if (a.status.get(scopeKey).ledgerSequence() != b.status.get(scopeKey).ledgerSequence()) {
                    violations.add(scopeKey + ": ledger sequence moved from " + a.status.get(scopeKey).ledgerSequence()
                            + " to " + b.status.get(scopeKey).ledgerSequence());
                }
                if (!a.exports.get(scopeKey).exportDigest().equals(b.exports.get(scopeKey).exportDigest())) {
                    violations.add(scopeKey + ": export digest changed after replay and a second rebuild");
                }
            }
            if (!a.fingerprint().equals(b.fingerprint())) {
                violations.add("recall rankings or similarities changed after replay and a second rebuild");
            }
        } finally {
            b.close();
        }
        List<String> evidence = new ArrayList<>(reconcileEvidence);
        evidence.add("events replayed through record(): " + replayed + ", all must be IDEMPOTENT");
        evidence.add("export digests: " + digests(a));
        return ForgeGate.of(ForgeGate.REPLAY_IDEMPOTENCY,
                "record is idempotent; reopen, replay and rebuild leave views, exports and rankings unchanged",
                violations, evidence);
    }

    private static String digests(Snapshot s) {
        List<String> out = new ArrayList<>();
        s.exports.forEach((scope, e) -> out.add(scope + "=" + e.exportDigest().substring(0, 16)));
        return String.join(", ", out);
    }

    // --- G5: resource accounting ----------------------------------------------------------------------

    private static ForgeGate accountingGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (ForgeFixture.Execution e : fixture.executions()) {
            ExecutionSummary summary = s.summaries.get(e.id());
            ForgeFixture.ExpectedChain expected = fixture.expectations().chains().get(e.id());
            CostToAcceptedOutcome cost = summary.cost();
            if (expected == null) {
                if (!fixture.expectations().unavailableCosts().contains(e.id())) {
                    violations.add(e.id() + ": no hand-verified expectation");
                } else if (cost.status() != CostToAcceptedOutcome.Status.UNAVAILABLE || !cost.amounts().isEmpty()) {
                    violations.add(e.id() + ": cost " + cost.status() + " but judged UNAVAILABLE (never zero)");
                }
                continue;
            }
            if (cost.status() != CostToAcceptedOutcome.Status.AVAILABLE || cost.amounts().size() != 1) {
                violations.add(e.id() + ": cost " + cost.status() + " " + cost.reasons());
                continue;
            }
            long in = reported(summary, UsageKind.INPUT_TOKENS);
            long out = reported(summary, UsageKind.OUTPUT_TOKENS);
            BigDecimal actual = cost.amounts().get(0).unrounded();
            if (in != expected.inputTokens() || out != expected.outputTokens()) {
                violations.add(e.id() + ": chain tokens " + in + "/" + out + " but hand-verified "
                        + expected.inputTokens() + "/" + expected.outputTokens());
            }
            if (actual.compareTo(expected.cost()) != 0) {
                violations.add(e.id() + ": chain cost " + actual.toPlainString() + " but hand-verified "
                        + expected.cost().toPlainString());
            }
            if (summary.postAcceptanceStages().size() != 0) {
                violations.add(e.id() + ": unexpected post-acceptance stages");
            }
            evidence.add(String.format(java.util.Locale.ROOT, "%-9s chain tokens %d/%d, cost %s USD (stages %d)",
                    e.id(), in, out, cost.amounts().get(0).amount().toPlainString(), summary.chainStages().size()));
        }
        // The export carries the same numbers.
        for (ExperienceExportReport report : s.exports.values()) {
            for (AnnotatedSample annotated : report.samples()) {
                String exec = annotated.sample().executionId().value();
                CostToAcceptedOutcome viaSummary = s.summaries.get(exec).cost();
                if (!annotated.cost().equals(viaSummary)) {
                    violations.add(exec + ": export cost differs from the summary cost");
                }
            }
        }
        evidence.add(0, "chains checked against hand-verified totals: " + fixture.executions().size());
        return ForgeGate.of(ForgeGate.RESOURCE_ACCOUNTING,
                "complete attempt-chain usage and hypothetical CostToAcceptedOutcome equal the hand-verified values",
                violations, evidence);
    }

    private static long reported(ExecutionSummary summary, UsageKind kind) {
        UsageTotals.KindUsage usage = summary.chainUsage().byKind().get(kind);
        return usage == null ? -1 : usage.reported();
    }

    // --- G6: comparison -------------------------------------------------------------------------------

    private static ForgeGate comparisonGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        ComparisonPolicy policy = policy(fixture);
        int number = 0;
        for (ForgeFixture.ExpectedComparison c : fixture.expectations().comparisons()) {
            number++;
            ComparisonCandidate left = candidate(fixture, s, c.leftScope(), c.left());
            ComparisonCandidate right = candidate(fixture, s, c.rightScope(), c.right());
            PairwiseComparison result = ExecutionComparator.compare(left, right, policy);
            Set<String> fields = new TreeSet<>();
            for (Mismatch m : result.comparability().mismatches()) {
                fields.add(m.field().name());
            }
            if (!result.conclusion().name().equals(c.conclusion())) {
                violations.add("comparison " + number + " " + c.left() + " vs " + c.right() + ": "
                        + result.conclusion() + " but judged " + c.conclusion());
            }
            if (!fields.equals(new TreeSet<>(c.mismatchFields()))) {
                violations.add("comparison " + number + ": mismatching fields " + fields + " but judged "
                        + new TreeSet<>(c.mismatchFields()));
            }
            if (result.conclusion() == PairwiseComparison.Conclusion.NOT_COMPARABLE && !result.informationalOnly()) {
                violations.add("comparison " + number + ": an incomparable pair must be informational only");
            }
            evidence.add(String.format(java.util.Locale.ROOT, "%d. %s:%s vs %s:%s -> %s %s", number, c.leftScope(),
                    c.left(), c.rightScope(), c.right(), result.conclusion(), fields.isEmpty() ? "" : fields));
        }
        return ForgeGate.of(ForgeGate.COMPARISON,
                "comparable and incomparable executions get the judged conclusions", violations, evidence);
    }

    private static ComparisonCandidate candidate(ForgeFixture fixture, Snapshot s, String scopeKey, String exec) {
        return ComparisonCandidate.of(ForgeFixtureRecorder.scope(fixture, scopeKey),
                s.views.get(scopeKey).get(exec), s.prices, List.of());
    }

    // --- G7: determinism ------------------------------------------------------------------------------

    private static ForgeGate determinismGate(Run first, Run second) {
        List<String> violations = new ArrayList<>();
        if (!first.fingerprint.equals(second.fingerprint)) {
            violations.add("two fixed-input runs produced different exports or rankings");
        }
        for (int i = 0; i < first.gates.size(); i++) {
            if (!first.gates.get(i).equals(second.gates.get(i))) {
                violations.add("gate " + first.gates.get(i).id() + " differs between runs");
            }
        }
        if (!first.queries.equals(second.queries)) {
            violations.add("per-query rankings or metrics differ between runs");
        }
        List<String> evidence = List.of("two fresh stores, same fixture: fingerprint " + Integer.toHexString(
                first.fingerprint.hashCode()) + " vs " + Integer.toHexString(second.fingerprint.hashCode())
                + "; elapsed timing is excluded");
        return ForgeGate.of(ForgeGate.DETERMINISM, "repeated fixed-input runs produce identical content and exports",
                violations, evidence);
    }

    // --- corpus listing -------------------------------------------------------------------------------

    private static List<ForgeCorpusLine> corpus(ForgeFixture fixture, Snapshot s) {
        List<ForgeCorpusLine> lines = new ArrayList<>();
        Map<String, String> results = attemptResults(s);
        for (ForgeFixture.Attempt a : fixture.attempts()) {
            ForgeFixture.Execution e = fixture.execution(a.execution());
            ExecutionSummary summary = s.summaries.get(e.id());
            String cost = summary.cost().status() == CostToAcceptedOutcome.Status.AVAILABLE
                    ? summary.cost().amounts().get(0).amount().toPlainString() + " USD" : "unavailable";
            lines.add(new ForgeCorpusLine(a.experienceId(), e.scopeKey(), results.getOrDefault(a.experienceId(),
                    "UNKNOWN"), e.expectedLabel(), e.expectedAcceptance(), cost));
        }
        return lines;
    }
}
