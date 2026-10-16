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
import com.monada.core.execution.EventKind;
import com.monada.evaluation.execution.ForgeSampleChecks.AttemptRule;
import com.monada.evaluation.execution.ForgeSampleChecks.Expectation;
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
import java.time.Instant;
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
    /** Below the nine executions of each project, so every export must traverse continuation pages. */
    private static final int EXPORT_PAGE_SIZE = 4;

    private ForgeExperienceEvaluator() {
    }

    /**
     * Evaluates {@code fixture} using two fresh stores created below {@code workDir} (which is created if missing).
     * Every call gets its own {@code run-*} directories, so calling it again with the same {@code workDir} never
     * sees the ledgers of an earlier call; the caller owns {@code workDir} and removes it.
     */
    public static ForgeEvaluationReport evaluate(ForgeFixture fixture, Path workDir) {
        return evaluate(fixture, workDir, ForgeExperienceEvaluator::allGates);
    }

    /** Everything the replay phase found, handed to the gates (the gates themselves never touch the stores). */
    record ReplayFindings(int replayed, List<String> record, List<String> reconcile, List<String> replay,
                          List<String> evidence) {
    }

    /** Computes the six gates that need the two snapshots; replaceable so that failure handling can be tested. */
    @FunctionalInterface
    interface GateRunner {
        List<ForgeGate> run(ForgeFixture fixture, Snapshot first, Snapshot second, ReplayFindings replay);
    }

    static ForgeEvaluationReport evaluate(ForgeFixture fixture, Path workDir, GateRunner gateRunner) {
        Run first = runOnce(fixture, freshRunDirectory(workDir), gateRunner);
        try {
            Run second = runOnce(fixture, freshRunDirectory(workDir), gateRunner);
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

    private static Path freshRunDirectory(Path workDir) {
        try {
            Files.createDirectories(workDir);
            return Files.createTempDirectory(workDir, "run-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- one complete run -----------------------------------------------------------------------------

    private record Run(Snapshot snapshot, List<ForgeQueryResult> queries, List<ForgeGate> gates,
                       Map<String, Long> elapsed, String fingerprint) {
    }

    private static Run runOnce(ForgeFixture fixture, Path root, GateRunner gateRunner) {
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
                reconcileViolations.addAll(projectionViolations(scopeKey + " first rebuild", after));
            }
        }
        elapsed.put("reopen+reconcile", System.nanoTime() - t);

        Snapshot a = snapshot(fixture, root, elapsed);
        Snapshot b = null;
        try {
            // Replay: reopen for writing, record everything again, reconcile again, take a second snapshot.
            t = System.nanoTime();
            List<String> replayViolations = new ArrayList<>();
            List<String> replayEvidence = new ArrayList<>();
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
                    ProjectionStatus rebuilt = memory.rebuildProjection(scope);
                    replayEvidence.add(scopeKey + ": second rebuild " + rebuilt.state() + " covering "
                            + rebuilt.coveredSequence() + "/" + rebuilt.ledgerSequence());
                    replayViolations.addAll(projectionViolations(scopeKey + " second rebuild", rebuilt));
                }
            }
            elapsed.put("replay", System.nanoTime() - t);
            b = snapshot(fixture, root, new LinkedHashMap<>());

            List<String> evidence = new ArrayList<>(reconcileEvidence);
            evidence.addAll(replayEvidence);
            List<ForgeGate> gates = gateRunner.run(fixture, a, b, new ReplayFindings(replayed, recordViolations,
                    reconcileViolations, replayViolations, evidence));
            return new Run(a, new ArrayList<>(a.results.values()), gates, elapsed, a.fingerprint());
        } catch (RuntimeException | Error e) {
            a.close(); // the Run that would have owned it is never returned
            throw e;
        } finally {
            if (b != null) {
                b.close();
            }
        }
    }

    static List<ForgeGate> allGates(ForgeFixture fixture, Snapshot a, Snapshot b, ReplayFindings replay) {
        List<ForgeGate> gates = new ArrayList<>();
        gates.add(isolationGate(fixture, a));
        gates.add(successGate(fixture, a));
        gates.add(referenceGate(fixture, a));
        gates.add(replayGate(fixture, a, b, replay.replayed(), replay.record(), replay.reconcile(), replay.replay(),
                replay.evidence()));
        gates.add(accountingGate(fixture, a));
        gates.add(comparisonGate(fixture, a));
        return gates;
    }

    // --- snapshot of a stable store -------------------------------------------------------------------

    /** Everything observed through public read APIs on one stable store. */
    static final class Snapshot {
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
            status.forEach((scope, st) -> out.append(scope).append(' ').append(st.state()).append(' ')
                    .append(st.coveredSequence()).append('/').append(st.ledgerSequence()).append('\n'));
            exports.forEach((scope, e) -> out.append(scope).append(' ').append(e.exportDigest()).append('\n'));
            results.forEach((id, r) -> {
                out.append(id);
                hits.get(id).forEach(h -> out.append(' ').append(experienceId(h.ref())).append('@')
                        .append(h.ref().revision()).append(':').append(h.similarity()));
                out.append('\n');
            });
            return out.toString();
        }

        void close() {
            memories.values().forEach(ExecutionMemory::close);
        }
    }

    static Snapshot snapshot(ForgeFixture fixture, Path root, Map<String, Long> elapsed) {
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
                List<RankedHit> ranked = rankedHits(q, recall.hits(), labels, results);
                s.results.put(q.id(), ForgeQueryResult.of(q, recall.status().state().name(), ranked, fixture.ks()));
            }
            elapsed.put("recall", System.nanoTime() - t);
            return s;
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /**
     * The experience id of a reference for reports and fingerprints: {@code <execution>/<attempt>}, or
     * {@code <execution>/<no-attempt>} for an execution-level or outcome reference. It never throws: a malformed
     * reference must reach the gate that rejects it.
     */
    static String experienceId(ExperienceRef ref) {
        String execution = ref.execution().value();
        return ref.attempt().map(a -> ForgeFixtureRecorder.experienceId(execution, a))
                .orElse(execution + "/<no-attempt>");
    }

    /**
     * The ranked view of a query's hits for the report. A hit without an attempt (an execution-level or outcome
     * reference) gets the diagnostic id {@code <execution>/<no-attempt>} instead of aborting the run: building the
     * report must never throw, because the failed gate that diagnoses the malformed reference is what the report is
     * for ({@link #hitViolations} rejects it).
     */
    static List<RankedHit> rankedHits(ForgeFixture.Query q, List<ExperienceHit> hits, Map<String, String> labels,
                                      Map<String, String> attemptResults) {
        List<RankedHit> ranked = new ArrayList<>();
        int rank = 0;
        for (ExperienceHit h : hits) {
            rank++;
            String execution = h.ref().execution().value();
            String id = experienceId(h.ref());
            ranked.add(new RankedHit(rank, id, h.similarity(), attemptResults.getOrDefault(id, "UNKNOWN"),
                    labels.getOrDefault(execution, "UNKNOWN"), h.ref().revision(), q.relevant().contains(id)));
        }
        return ranked;
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

    static ForgeGate isolationGate(ForgeFixture fixture, Snapshot s) {
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
                // an execution the fixture does not know is reported by the membership check below
                if (!sample.execution().scope().equals(ForgeFixtureRecorder.scope(fixture, scopeKey))
                        || fixture.findExecution(sample.executionId().value())
                        .filter(e -> !e.scopeKey().equals(scopeKey)).isPresent()) {
                    violations.add(scopeKey + ": export holds a foreign execution " + sample.executionId().value());
                }
            }
            violations.addAll(ForgeSampleChecks.exportMembershipViolations(fixture, scopeKey,
                    s.exports.get(scopeKey).samples().stream().map(AnnotatedSample::sample).toList()));
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
            Optional<ForgeFixture.Execution> known = fixture.findExecution(exec);
            if (known.isEmpty()) {
                violations.add(q.id() + ": hit on an execution unknown to the fixture: " + exec);
            } else if (!known.get().scopeKey().equals(q.scopeKey())) {
                violations.add(q.id() + ": hit on an execution of another project: " + exec);
            }
        }
        return violations;
    }

    static List<String> mustReject(String what, Runnable call) {
        try {
            call.run();
            return List.of(what + " was served instead of being rejected");
        } catch (IllegalArgumentException expected) {
            return List.of();
        }
    }

    // --- G2: no invalid success labels ----------------------------------------------------------------

    static ForgeGate successGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int successes = 0;
        int checked = 0;
        for (String scopeKey : fixture.scopeIds().keySet()) {
            Map<String, Integer> ordinals = ForgeSampleChecks.expectedOrdinals(fixture, scopeKey);
            for (AnnotatedSample annotated : s.exports.get(scopeKey).samples()) {
                ExperienceSample sample = annotated.sample();
                String exec = sample.executionId().value();
                Optional<ForgeFixture.Execution> known = fixture.findExecution(exec);
                checked++;
                if (known.isEmpty()) {
                    violations.add(exec + ": exported execution is unknown to the fixture");
                    continue;
                }
                ForgeFixture.Execution expected = known.get();
                if (!sample.label().name().equals(expected.expectedLabel())) {
                    violations.add(exec + ": label " + sample.label() + " but judged " + expected.expectedLabel());
                }
                if (!sample.acceptance().name().equals(expected.expectedAcceptance())) {
                    violations.add(exec + ": acceptance " + sample.acceptance() + " but judged "
                            + expected.expectedAcceptance());
                }
                violations.addAll(ForgeSampleChecks.successViolations(fixture, sample, ordinals));
                if (sample.label() == ExperienceSample.Label.SUCCESS) {
                    successes++;
                    if (sample.acceptance() != DerivedAcceptance.Status.VALIDATED_ACCEPTED) {
                        violations.add(exec + ": SUCCESS without a validated acceptance (" + sample.acceptance() + ")");
                    }
                    // the hand-listed ordinals stay as an independent contrast to the derived ones
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
                boolean judgedSuccess = fixture.findExecution(exec).map(e -> e.expectedLabel().equals("SUCCESS"))
                        .orElse(false);
                if (h.label().equals("SUCCESS") && !judgedSuccess) {
                    violations.add(q.query().id() + ": hit " + h.experienceId() + " is shown as SUCCESS");
                }
            }
        }
        List<String> evidence = List.of("samples checked: " + checked + "; SUCCESS labels: " + successes
                + " (judged " + expectedSuccesses + "); invalid success labels: " + violations.size(),
                "group, success ordinal and one passing gate per mandatory dimension derived from the fixture and "
                        + "checked for every sample");
        return ForgeGate.of(ForgeGate.NO_INVALID_SUCCESS, "no invalid success label; labels equal the judged labels",
                violations, evidence);
    }

    // --- G3: exact reference consistency --------------------------------------------------------------

    /**
     * What a recalled hit must be (section 18.1): the exact ledger event of one {@code ATTEMPT_FINISHED} fact, of
     * the right attempt, at the latest revision of the fixture, carrying the attempt's result and latest solution.
     * The hit's own event is validated, so a projection that maps an atom to another valid event of the same
     * attempt (a stage, the start) is a violation even though that event loads and its coordinates match.
     */
    static List<String> hitViolations(ForgeFixture fixture, ExecutionMemory memory, ScopeId scope, String queryId,
                                      ExperienceHit h) {
        List<String> violations = new ArrayList<>();
        ExperienceRef ref = h.ref();
        Optional<HistoryEntry> exact = memory.loadEvent(scope, ref.eventId(), ref.revision());
        if (exact.isEmpty() || !exact.get().equals(h.event())) {
            violations.add(queryId + ": hit " + ref.eventId().value() + " r" + ref.revision()
                    + " does not equal the exact ledger load");
        }
        exact.ifPresent(entry -> violations.addAll(coordinateViolations(queryId + ": hit", scope, ref, entry)));
        AttemptId attempt = ref.attempt().orElse(null);
        if (attempt == null) {
            violations.add(queryId + ": hit without an attempt");
            return violations;
        }
        String id = ForgeFixtureRecorder.experienceId(ref.execution().value(), attempt);
        ForgeFixture.Attempt fixtureAttempt = fixture.attempts().stream()
                .filter(a -> a.experienceId().equals(id)).findFirst().orElse(null);
        if (fixtureAttempt == null) {
            violations.add(queryId + ": hit " + id + " is not in the fixture");
            return violations;
        }
        int expectedRevision = fixtureAttempt.revised() ? 2 : 1;
        if (ref.revision() != expectedRevision) {
            violations.add(queryId + ": " + id + " recalled at revision " + ref.revision() + ", latest is "
                    + expectedRevision);
        }
        String expectedSolution = fixtureAttempt.revised() ? fixtureAttempt.revisedSolution()
                : fixtureAttempt.solution();
        violations.addAll(ForgeSampleChecks.factViolations(queryId + ": hit " + id, Expectation.of(
                EventKind.ATTEMPT_FINISHED, AttemptRule.of(attempt), ForgeSampleChecks.finishedWithSolution(
                        com.monada.core.execution.AttemptResult.valueOf(fixtureAttempt.result()), expectedSolution),
                "result " + fixtureAttempt.result() + " and the latest solution"), h.event()));
        Optional<AttemptView> view = memory.loadAttempt(scope, ref.execution(), attempt);
        if (view.isEmpty() || !view.get().solutionSummary().equals(Optional.of(expectedSolution))) {
            violations.add(queryId + ": " + id + " loads a different solution than its latest revision");
        }
        return violations;
    }

    static ForgeGate referenceGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int hitsChecked = 0;
        int refsChecked = 0;
        for (ForgeFixture.Query q : fixture.queries()) {
            ScopeId scope = ForgeFixtureRecorder.scope(fixture, q.scopeKey());
            ExecutionMemory memory = s.memories.get(q.scopeKey());
            for (ExperienceHit h : s.hits.get(q.id())) {
                hitsChecked++;
                violations.addAll(hitViolations(fixture, memory, scope, q.id(), h));
            }
        }
        Map<String, Integer> categories = new java.util.TreeMap<>();
        List<String> pages = new ArrayList<>();
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ScopeId scope = ForgeFixtureRecorder.scope(fixture, scopeKey);
            ExecutionMemory memory = s.memories.get(scopeKey);
            violations.addAll(paginationViolations(memory, scope, s.exports.get(scopeKey), EXPORT_PAGE_SIZE,
                    fixture.executionsOf(scopeKey).size()));
            pages.add(scopeKey + "=" + s.exports.get(scopeKey).pages().size());
            for (AnnotatedSample annotated : s.exports.get(scopeKey).samples()) {
                ExperienceSample sample = annotated.sample();
                List<LabelledRef> references = exportedReferences(sample);
                for (LabelledRef labelled : references) {
                    refsChecked++;
                    categories.merge(labelled.origin(), 1, Integer::sum);
                }
                violations.addAll(referenceViolations(memory, scope, sample, references));
                violations.addAll(chronologyViolations(fixture, memory, scope, sample, references));
                violations.addAll(ForgeSampleChecks.sequenceBoundsViolations(memory, scope, sample));
                violations.addAll(ForgeSampleChecks.evidenceStateViolations(fixture, sample));
                String exec = sample.executionId().value();
                violations.addAll(ForgeSampleChecks.revisionCountViolations(fixture, sample));
                if (fixture.findExecution(exec).map(e -> e.outcome() != ForgeFixture.OutcomeKind.NONE).orElse(false)
                        && sample.outcome().isEmpty()) {
                    violations.add(exec + ": the recorded outcome is missing from the export");
                }
                for (ForgeFixture.Attempt fa : fixture.attemptsOf(exec)) {
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
                + "resolved: " + refsChecked + " " + categories + "; export pages at size " + EXPORT_PAGE_SIZE + ": " + pages
                + " (paged export equals the unpaged export); inconsistent references: " + violations.size());
        return ForgeGate.of(ForgeGate.EXACT_REFERENCES,
                "every recalled and exported reference loads the exact ledger event at its latest revision",
                violations, evidence);
    }

    /** One provenance reference of an exported sample and where in the sample it appears. */
    record LabelledRef(String origin, ExperienceRef ref, ForgeSampleChecks.Expectation expectation) {
    }

    /**
     * Every provenance reference an exported sample carries: the execution, each attempt start and finish, stage
     * and evidence, the outcome, the gates' effective observations, every revision (effective and superseded) and,
     * in the diagnostic view, the outcome history and ignored observations.
     */
    static List<LabelledRef> exportedReferences(ExperienceSample sample) {
        List<LabelledRef> refs = new ArrayList<>();
        refs.add(new LabelledRef("execution", sample.execution(), Expectation.of(EventKind.EXECUTION_STARTED,
                AttemptRule.none(), ForgeSampleChecks.executionStartedAs(sample),
                "the exported task summary, provenance and policy")));
        for (ExperienceSample.SampleAttempt a : sample.attempts()) {
            AttemptRule inAttempt = AttemptRule.of(a.id());
            refs.add(new LabelledRef("attempt.start", a.start(), Expectation.of(EventKind.ATTEMPT_STARTED, inAttempt,
                    ForgeSampleChecks.startedAs(a), "ordinal " + a.ordinal() + ", reason " + a.reason()
                            + " and previous attempt " + a.previousAttempt().map(AttemptId::value).orElse("<none>"))));
            a.finish().ifPresent(r -> refs.add(new LabelledRef("attempt.finish", r, Expectation.of(
                    EventKind.ATTEMPT_FINISHED, inAttempt, ForgeSampleChecks.finishedAs(a),
                    "result " + a.result().map(Enum::name).orElse("<none>") + " and the exported solution and "
                            + "lesson summaries"))));
            a.stages().forEach(st -> refs.add(new LabelledRef("stage", st.ref(), Expectation.of(
                    EventKind.STAGE_RECORDED, inAttempt, ForgeSampleChecks.stageAs(st),
                    "stage " + st.stage() + " with the exported route, interval, usage declaration, artifacts and "
                            + "usage counters (value, state and source)")
                    .at(st.sequence()))));
            a.evidence().forEach(ev -> refs.add(new LabelledRef("evidence", ev.ref(), Expectation.of(
                    EventKind.EVIDENCE_RECORDED, inAttempt, ForgeSampleChecks.evidenceAs(ev, sample.policy(),
                            sample.diagnostics().isPresent()), "the exported observations and artifacts")
                    .at(ev.sequence()))));
        }
        sample.outcome().ifPresent(o -> refs.add(new LabelledRef("outcome", o.ref(), Expectation.of(
                EventKind.OUTCOME_RECORDED, AttemptRule.none(), ForgeSampleChecks.recordedOutcome(o.outcome()),
                "the exported outcome " + o.outcome()).at(o.sequence()))));
        // gate evidence comes from the accepted attempt and must observe the gate's own dimension
        AttemptRule accepted = sample.attempts().stream().filter(ExperienceSample.SampleAttempt::accepted).findFirst()
                .map(a -> AttemptRule.of(a.id())).orElse(AttemptRule.any());
        sample.gates().forEach(g -> g.ref().ifPresent(r -> refs.add(new LabelledRef("gate", r, Expectation.of(
                EventKind.EVIDENCE_RECORDED, accepted, ForgeSampleChecks.gateEvidenceAs(g),
                "an observation of " + g.dimension() + " equal to the exported one").at(g.sequence())))));
        for (ExperienceSample.Revision r : sample.revisions()) {
            refs.add(new LabelledRef("revision.effective", r.effective(),
                    Expectation.correction(AttemptRule.any()).at(r.sequence())));
            r.superseded().forEach(sup -> refs.add(new LabelledRef("revision.superseded", sup,
                    Expectation.of(sup.revision() == 1 ? kindOfSuperseded(sample, r) : EventKind.CORRECTION,
                            AttemptRule.any()))));
        }
        sample.diagnostics().ifPresent(d -> {
            d.outcomeHistory().forEach(o -> refs.add(new LabelledRef("diagnostics.outcome", o.ref(), Expectation.of(
                    EventKind.OUTCOME_RECORDED, AttemptRule.none(), ForgeSampleChecks.recordedOutcome(o.outcome()),
                    "the exported outcome " + o.outcome()).at(o.sequence()))));
            d.ignoredObservations().forEach(i -> refs.add(new LabelledRef("diagnostics.ignored", i.ref(),
                    Expectation.of(EventKind.EVIDENCE_RECORDED, AttemptRule.any()))));
        });
        return refs;
    }

    /**
     * The kind of the original (revision 1) fact a revision replaced: the kind of the exported fact the effective
     * revision corrects. Falls back to the effective ref's own origin when the fact is not otherwise exported.
     */
    private static EventKind kindOfSuperseded(ExperienceSample sample, ExperienceSample.Revision revision) {
        ExperienceRef effective = revision.effective();
        for (ExperienceSample.SampleAttempt a : sample.attempts()) {
            if (a.finish().filter(effective::equals).isPresent()) {
                return EventKind.ATTEMPT_FINISHED;
            }
            if (a.start().equals(effective)) {
                return EventKind.ATTEMPT_STARTED;
            }
            if (a.stages().stream().anyMatch(st -> st.ref().equals(effective))) {
                return EventKind.STAGE_RECORDED;
            }
            if (a.evidence().stream().anyMatch(ev -> ev.ref().equals(effective))) {
                return EventKind.EVIDENCE_RECORDED;
            }
        }
        if (sample.outcome().filter(o -> o.ref().equals(effective)).isPresent()) {
            return EventKind.OUTCOME_RECORDED;
        }
        return sample.execution().equals(effective) ? EventKind.EXECUTION_STARTED : EventKind.CORRECTION;
    }

    /**
     * Violations of the exact-reference contract for one sample: every reference loads its exact ledger event,
     * that event belongs to the sample's execution, the outcome reference addresses an outcome, and every
     * superseded revision is older than the revision that replaced it.
     */
    static List<String> referenceViolations(ExecutionMemory memory, ScopeId scope, ExperienceSample sample) {
        return referenceViolations(memory, scope, sample, exportedReferences(sample));
    }

    /** As above, with the sample's references already collected (the gate collects them once per sample). */
    static List<String> referenceViolations(ExecutionMemory memory, ScopeId scope, ExperienceSample sample,
                                            List<LabelledRef> references) {
        return referenceViolations(memory, scope, sample, references, Long.MAX_VALUE);
    }

    /**
     * As above, for a sample exported as of a cutoff: every reference must also resolve to a ledger event at a
     * sequence no later than {@code maxSequence}, so a historical sample never references a later fact.
     */
    static List<String> referenceViolations(ExecutionMemory memory, ScopeId scope, ExperienceSample sample,
                                            List<LabelledRef> references, long maxSequence) {
        List<String> violations = new ArrayList<>();
        String exec = sample.executionId().value();
        for (LabelledRef labelled : references) {
            ExperienceRef ref = labelled.ref();
            Optional<HistoryEntry> exact = memory.loadEvent(scope, ref.eventId(), ref.revision());
            String what = exec + ": " + labelled.origin() + " ref " + ref.eventId().value() + " r" + ref.revision();
            if (exact.isEmpty()) {
                violations.add(what + " does not resolve");
            } else {
                if (exact.get().sequence() > maxSequence) {
                    violations.add(what + " lies at sequence " + exact.get().sequence() + " beyond the cutoff "
                            + maxSequence);
                }
                List<String> coordinates = new ArrayList<>(coordinateViolations(what, scope, ref, exact.get()));
                // a reference can match its own event perfectly and still belong to another execution than the sample
                if (!ref.execution().equals(sample.executionId())) {
                    coordinates.add(what + " resolves to another execution (" + ref.execution().value() + ")");
                }
                violations.addAll(coordinates);
                if (coordinates.isEmpty()) {
                    violations.addAll(ForgeSampleChecks.factViolations(what, labelled.expectation(), exact.get()));
                }
            }
        }
        violations.addAll(revisionViolations(memory, scope, sample, references));
        return violations;
    }

    /**
     * Revisions keep their whole history: each revision must correct a fact the sample exports, replace the same
     * kind of event, and retain every predecessor, in order, down to revision 1. The chain is walked through the
     * ledger ({@code supersedes} of each event), so a dropped, reordered or foreign predecessor is a violation and
     * an empty {@code superseded} list is not skipped.
     */
    static List<String> revisionViolations(ExecutionMemory memory, ScopeId scope, ExperienceSample sample) {
        return revisionViolations(memory, scope, sample, exportedReferences(sample));
    }

    static List<String> revisionViolations(ExecutionMemory memory, ScopeId scope, ExperienceSample sample,
                                           List<LabelledRef> references) {
        List<String> violations = new ArrayList<>();
        String exec = sample.executionId().value();
        Set<ExperienceRef> exportedFacts = new java.util.HashSet<>();
        for (LabelledRef labelled : references) {
            if (!labelled.origin().startsWith("revision.")) {
                exportedFacts.add(labelled.ref());
            }
        }
        for (ExperienceSample.Revision r : sample.revisions()) {
            ExperienceRef effective = r.effective();
            if (!exportedFacts.contains(effective)) {
                violations.add(exec + ": revision " + effective.eventId().value() + " r" + effective.revision()
                        + " corrects a fact the sample does not export");
            }
            int predecessors = effective.revision() - 1;
            if (r.superseded().size() != predecessors) {
                violations.add(exec + ": revision " + effective.eventId().value() + " r" + effective.revision()
                        + " retains " + r.superseded().size() + " of its " + predecessors + " predecessors");
            }
            Optional<HistoryEntry> head = memory.loadEvent(scope, effective.eventId(), effective.revision());
            if (head.isPresent() && head.get().event() instanceof com.monada.core.execution.ExecutionEvent.Correction c) {
                if (!c.justification().equals(r.justification())) {
                    violations.add(exec + ": revision " + effective.eventId().value() + " r" + effective.revision()
                            + " states the justification \"" + r.justification() + "\" but the correction says \""
                            + c.justification() + "\"");
                }
                EventKind replaced = ForgeSampleChecks.kindOf(c);
                EventKind expected = kindOfSuperseded(sample, r);
                if (replaced != expected) {
                    violations.add(exec + ": revision replaces a " + replaced + " but corrects a " + expected);
                }
                Optional<com.monada.core.execution.EventId> named = c.envelope().supersedes();
                int revision = effective.revision() - 1;
                for (int i = 0; i < r.superseded().size(); i++) {
                    ExperienceRef sup = r.superseded().get(i);
                    if (named.isEmpty() || !named.get().equals(sup.eventId()) || sup.revision() != revision) {
                        violations.add(exec + ": predecessor " + (i + 1) + " of " + effective.eventId().value()
                                + " is " + sup.eventId().value() + " r" + sup.revision() + " but the ledger chain has "
                                + named.map(com.monada.core.execution.EventId::value).orElse("<none>") + " r"
                                + revision);
                        break;
                    }
                    Optional<HistoryEntry> previous = memory.loadEvent(scope, sup.eventId(), sup.revision());
                    named = previous.flatMap(h -> h.event().envelope().supersedes());
                    revision--;
                }
            }
            for (ExperienceRef superseded : r.superseded()) {
                if (superseded.revision() >= effective.revision()) {
                    violations.add(exec + ": superseded " + superseded.eventId().value() + " r" + superseded.revision()
                            + " is not older than the effective r" + effective.revision());
                }
            }
        }
        return violations;
    }

    /**
     * Compares every coordinate of {@code ref} (scope, task, execution, attempt, event id, revision) with the
     * envelope of the ledger event it resolved to. Loading by event id and revision alone would accept a reference
     * whose other coordinates are wrong, so none of them is taken on trust.
     */
    static List<String> coordinateViolations(String what, ScopeId queriedScope, ExperienceRef ref, HistoryEntry entry) {
        var envelope = entry.event().envelope();
        List<String> violations = new ArrayList<>();
        String label = what + " " + ref.eventId().value() + " r" + ref.revision();
        if (!ref.scope().equals(queriedScope)) {
            violations.add(label + ": scope " + ref.scope().value() + " is not the scope " + queriedScope.value());
        }
        if (!ref.scope().equals(envelope.scopeId())) {
            violations.add(label + ": scope " + ref.scope().value() + " but the ledger event is in "
                    + envelope.scopeId().value());
        }
        if (!ref.task().equals(envelope.taskId())) {
            violations.add(label + ": task " + ref.task().value() + " but the ledger event is of "
                    + envelope.taskId().value());
        }
        if (!ref.execution().equals(envelope.executionId())) {
            violations.add(label + ": execution " + ref.execution().value() + " but the ledger event is of "
                    + envelope.executionId().value());
        }
        if (!ref.attempt().equals(envelope.attemptId())) {
            violations.add(label + ": attempt " + ref.attempt().map(AttemptId::value).orElse("<none>")
                    + " but the ledger event is of " + envelope.attemptId().map(AttemptId::value).orElse("<none>"));
        }
        if (!ref.eventId().equals(envelope.eventId())) {
            violations.add(label + ": event id differs from the ledger event " + envelope.eventId().value());
        }
        if (ref.revision() != envelope.revision()) {
            violations.add(label + ": revision " + ref.revision() + " but the ledger event is r"
                    + envelope.revision());
        }
        return violations;
    }

    /**
     * The persisted chronology must be the fixture's for every event the sample references, not only the attempt
     * start: attempt starts at their declared start; stages, evidence, finishes, gate evidence and corrections at the
     * end of their attempt (both {@code occurredAt} and {@code recordedAt}, on the stored envelope and, for a
     * correction, on its replacement); the execution start at T0; the outcome at the last attempt end. The exported
     * stage interval is compared as well.
     */
    static List<String> chronologyViolations(ForgeFixture fixture, ExecutionMemory memory, ScopeId scope,
                                             ExperienceSample sample) {
        return chronologyViolations(fixture, memory, scope, sample, exportedReferences(sample));
    }

    static List<String> chronologyViolations(ForgeFixture fixture, ExecutionMemory memory, ScopeId scope,
                                             ExperienceSample sample, List<LabelledRef> references) {
        List<String> violations = new ArrayList<>();
        String exec = sample.executionId().value();
        long lastEnd = fixture.attemptsOf(exec).stream().mapToLong(ForgeFixture.Attempt::endSeconds).max().orElse(0);
        for (LabelledRef labelled : references) {
            ExperienceRef ref = labelled.ref();
            Instant expected = expectedInstant(fixture, exec, labelled, lastEnd);
            if (expected == null) {
                violations.add(exec + ": " + labelled.origin() + " ref " + ref.eventId().value()
                        + " names an attempt the fixture does not declare");
                continue;
            }
            memory.loadEvent(scope, ref.eventId(), ref.revision()).ifPresent(entry -> {
                var envelope = entry.event().envelope();
                List<com.monada.core.execution.EventEnvelope> envelopes = new ArrayList<>(List.of(envelope));
                if (entry.event() instanceof com.monada.core.execution.ExecutionEvent.Correction c) {
                    envelopes.add(c.replacement().envelope());
                }
                for (var e : envelopes) {
                    if (!e.occurredAt().equals(expected) || !e.recordedAt().equals(expected)) {
                        violations.add(exec + ": " + labelled.origin() + " " + ref.eventId().value() + " r"
                                + ref.revision() + " stored at " + e.occurredAt() + "/" + e.recordedAt()
                                + " but declared " + expected);
                        break;
                    }
                }
            });
        }
        for (ForgeFixture.Attempt fa : fixture.attemptsOf(exec)) {
            sample.attempts().stream().filter(a -> a.ordinal() == fa.ordinal()).findFirst().ifPresent(a -> {
                Instant begin = ForgeFixtureRecorder.T0.plusSeconds(fa.startSeconds());
                Instant end = ForgeFixtureRecorder.T0.plusSeconds(fa.endSeconds());
                for (ExperienceSample.SampleStage stage : a.stages()) {
                    if (!stage.startedAt().equals(begin) || !stage.endedAt().equals(end)) {
                        violations.add(fa.experienceId() + ": stage " + stage.startedAt() + ".." + stage.endedAt()
                                + " but declared " + begin + ".." + end);
                    }
                }
            });
        }
        return violations;
    }

    private static Instant expectedInstant(ForgeFixture fixture, String exec, LabelledRef labelled, long lastEnd) {
        Instant t0 = ForgeFixtureRecorder.T0;
        switch (labelled.origin()) {
            case "execution":
                return t0;
            case "outcome":
            case "diagnostics.outcome":
                return t0.plusSeconds(lastEnd);
            default:
                break;
        }
        Optional<AttemptId> attempt = labelled.ref().attempt();
        if (attempt.isEmpty()) {
            return null;
        }
        return fixture.attemptsOf(exec).stream()
                .filter(a -> ForgeFixtureRecorder.attemptId(a.ordinal()).equals(attempt.get())).findFirst()
                .map(a -> t0.plusSeconds(labelled.origin().equals("attempt.start") ? a.startSeconds()
                        : a.endSeconds()))
                .orElse(null);
    }

    /**
     * A paged export must traverse as many pages as the page size demands (so a continuation is really exercised)
     * and must carry exactly the samples, in the same order, as one unpaged export of the same scope and cutoff.
     */
    static List<String> paginationViolations(ExecutionMemory memory, ScopeId scope, ExperienceExportReport report,
                                             int pageSize, int executions) {
        return pagedExportViolations(scope, "", report, memory.exportSamples(ExportRequest.firstPage(scope, 50)),
                pageSize, executions, memory.history(scope).highWatermark());
    }

    /**
     * The pagination rules of {@link #paginationViolations} for any export: {@code reference} is one unpaged export
     * of the same scope and cutoff, {@code expectedCutoff} the ledger sequence every page must be cut at and
     * {@code what} a prefix naming the export in messages ({@code ""} for the latest export, {@code "cutoff "} for an
     * export at an explicit cutoff).
     */
    static List<String> pagedExportViolations(ScopeId scope, String what, ExperienceExportReport report,
                                              com.monada.api.execution.ExperienceSamplePage reference, int pageSize,
                                              int executions, long expectedCutoff) {
        List<String> violations = new ArrayList<>();
        int expectedPages = (executions + pageSize - 1) / pageSize;
        if (report.pages().size() != expectedPages) {
            violations.add(scope.value() + ": " + what + "export used " + report.pages().size() + " pages, expected "
                    + expectedPages + " for " + executions + " executions at page size " + pageSize);
        }
        if (report.pages().size() < 2) {
            violations.add(scope.value() + ": the " + what + "export never traversed a continuation page");
        }
        violations.addAll(pageMetadataViolations(scope, what, report.pages(), reference, expectedCutoff));
        List<ExperienceSample> unpaged = reference.samples();
        List<ExperienceSample> paged = report.samples().stream().map(AnnotatedSample::sample).toList();
        if (!unpaged.equals(paged)) {
            violations.add(scope.value() + ": the paged " + what + "export differs from the unpaged " + what
                    + "export in content or order");
        }
        return violations;
    }

    /**
     * Every page must describe the same frozen snapshot: schema, scope, view, additional gates, ledger checkpoint and
     * projection stamp equal those of the first page and of the unpaged export, the checkpoint cutoff is the end of
     * the scope's history, the projection is current and covers it, and the continuation flags are consistent
     * ({@code hasMore} on every page but the last, {@code next} present exactly when {@code hasMore}).
     */
    static List<String> pageMetadataViolations(ExecutionMemory memory, ScopeId scope, ExperienceExportReport report) {
        return pageMetadataViolations(memory, scope, report, memory.exportSamples(ExportRequest.firstPage(scope, 50)));
    }

    /** As above, against an unpaged export that the caller already read (one export per project, not two). */
    static List<String> pageMetadataViolations(ExecutionMemory memory, ScopeId scope, ExperienceExportReport report,
                                               com.monada.api.execution.ExperienceSamplePage reference) {
        return pageMetadataViolations(scope, "", report.pages(), reference, memory.history(scope).highWatermark());
    }

    /**
     * As above, for pages that must be cut at {@code expectedCutoff}; {@code what} as in
     * {@link #pagedExportViolations}.
     */
    static List<String> pageMetadataViolations(ScopeId scope, String what,
                                               List<com.monada.api.execution.ExperienceSamplePage> pages,
                                               com.monada.api.execution.ExperienceSamplePage reference,
                                               long expectedCutoff) {
        List<String> violations = new ArrayList<>();
        String unpaged = "the unpaged " + what + "export";
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            String label = scope.value() + " " + what + "page " + (i + 1) + "/" + pages.size();
            if (!com.monada.api.execution.ExperienceSamplePage.SCHEMA.equals(page.schema())) {
                violations.add(label + ": schema " + page.schema());
            }
            if (!page.schema().equals(reference.schema()) || !page.scope().equals(reference.scope())
                    || page.view() != reference.view() || !page.additionalGates().equals(reference.additionalGates())) {
                violations.add(label + ": schema/scope/view/gates " + page.schema() + "/" + page.scope().value() + "/"
                        + page.view() + "/" + page.additionalGates() + " differ from " + unpaged);
            }
            if (page.view() != ExportView.ADAPTER_READY || !page.additionalGates().isEmpty()) {
                violations.add(label + ": expected the adapter view with no additional gates");
            }
            if (!page.ledger().equals(reference.ledger()) || !page.ledger().equals(pages.get(0).ledger())) {
                violations.add(label + ": ledger checkpoint " + page.ledger() + " differs from the first page or "
                        + unpaged + " " + reference.ledger());
            }
            if (page.ledger().cutoffSequence() != expectedCutoff) {
                violations.add(label + ": checkpoint cutoff " + page.ledger().cutoffSequence() + " but the export "
                        + "must be cut at " + expectedCutoff);
            }
            if (!page.projection().equals(reference.projection())
                    || !page.projection().equals(pages.get(0).projection())) {
                violations.add(label + ": projection stamp " + page.projection() + " differs from the first page or "
                        + unpaged + " " + reference.projection());
            }
            if (page.projection().state() != ProjectionStatus.State.CURRENT || !page.projection().coversCutoff()) {
                violations.add(label + ": projection " + page.projection().state() + ", covers cutoff "
                        + page.projection().coversCutoff());
            }
            boolean last = i == pages.size() - 1;
            if (page.hasMore() == last || page.next().isPresent() != page.hasMore()) {
                violations.add(label + ": hasMore=" + page.hasMore() + " next=" + page.next().isPresent()
                        + (last ? " on the last page" : " on a page that is not the last"));
            }
        }
        return violations;
    }

    /**
     * A stage exports one entry per usage kind, exactly once, so an unmeasured counter stays explicit instead of
     * disappearing (sample contract, {@code SampleBuilder.stage}). A missing or repeated kind is a violation.
     */
    static List<String> usageKindViolations(String exec, ExperienceSample.SampleStage stage) {
        List<String> violations = new ArrayList<>();
        Map<UsageKind, Integer> seen = new java.util.EnumMap<>(UsageKind.class);
        stage.usage().forEach(u -> seen.merge(u.kind(), 1, Integer::sum));
        for (UsageKind kind : UsageKind.values()) {
            int n = seen.getOrDefault(kind, 0);
            if (n == 0) {
                violations.add(exec + ": stage " + stage.stage() + " omits the " + kind + " counter");
            } else if (n > 1) {
                violations.add(exec + ": stage " + stage.stage() + " exports the " + kind + " counter " + n + " times");
            }
        }
        return violations;
    }

    /**
     * Recomputes the chain usage from the usage values the export itself carries. The chain ends at the accepted
     * attempt for a validated acceptance and covers every attempt otherwise. Every counter must be REPORTED.
     */
    static List<String> exportedUsageViolations(ExperienceSample sample, ForgeFixture.ExpectedChain expected,
                                                int expectedStages) {
        List<String> violations = new ArrayList<>();
        String exec = sample.executionId().value();
        int cap = Integer.MAX_VALUE;
        if (sample.acceptance() == DerivedAcceptance.Status.VALIDATED_ACCEPTED) {
            cap = sample.attempts().stream().filter(ExperienceSample.SampleAttempt::accepted)
                    .mapToInt(ExperienceSample.SampleAttempt::ordinal).min().orElse(Integer.MAX_VALUE);
        }
        long input = 0;
        long output = 0;
        int stages = 0;
        for (ExperienceSample.SampleAttempt a : sample.attempts()) {
            if (a.ordinal() > cap) {
                continue;
            }
            for (ExperienceSample.SampleStage stage : a.stages()) {
                stages++;
                violations.addAll(usageKindViolations(exec, stage));
                for (ExperienceSample.SampleUsage u : stage.usage()) {
                    boolean recorded = u.kind() == UsageKind.INPUT_TOKENS || u.kind() == UsageKind.OUTPUT_TOKENS;
                    if (recorded) {
                        if (u.state() != ExperienceSample.UsageState.REPORTED || u.value().isEmpty()) {
                            violations.add(exec + ": exported " + u.kind() + " of stage " + stage.stage() + " is "
                                    + u.state() + " without a reported value");
                        } else if (u.kind() == UsageKind.INPUT_TOKENS) {
                            input += u.value().getAsLong();
                        } else {
                            output += u.value().getAsLong();
                        }
                    } else if (u.state() != ExperienceSample.UsageState.NOT_MEASURED || u.value().isPresent()) {
                        // the fixture records no other counter: it must stay not-measured, never a number or zero
                        violations.add(exec + ": exported " + u.kind() + " of stage " + stage.stage() + " is "
                                + u.state() + " " + u.value() + " but the fixture never recorded it");
                    }
                }
            }
        }
        if (input != expected.inputTokens() || output != expected.outputTokens()) {
            violations.add(exec + ": exported chain usage " + input + "/" + output + " but hand-verified "
                    + expected.inputTokens() + "/" + expected.outputTokens());
        }
        if (stages != expectedStages) {
            violations.add(exec + ": export carries " + stages + " chain stages but the summary has " + expectedStages);
        }
        return violations;
    }

    // --- G4: replay and idempotency -------------------------------------------------------------------

    static ForgeGate replayGate(ForgeFixture fixture, Snapshot a, Snapshot b, int replayed,
                                        List<String> recordViolations, List<String> reconcileViolations,
                                        List<String> replayViolations, List<String> reconcileEvidence) {
        List<String> violations = new ArrayList<>(recordViolations);
        violations.addAll(reconcileViolations);
        violations.addAll(replayViolations);
        for (String scopeKey : fixture.scopeIds().keySet()) {
            if (!a.views.get(scopeKey).equals(b.views.get(scopeKey))) {
                violations.add(scopeKey + ": execution views changed after replay");
            }
            violations.addAll(projectionViolations(scopeKey + " after replay", b.status.get(scopeKey)));
            if (a.status.get(scopeKey).projectionVersion() > b.status.get(scopeKey).projectionVersion()) {
                violations.add(scopeKey + ": projection version went backwards");
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
        List<String> evidence = new ArrayList<>(reconcileEvidence);
        evidence.add("events replayed through record(): " + replayed + ", all must be IDEMPOTENT");
        evidence.add("export digests: " + digests(a));
        return ForgeGate.of(ForgeGate.REPLAY_IDEMPOTENCY,
                "record is idempotent; reopen, replay and rebuild leave views, exports and rankings unchanged",
                violations, evidence);
    }

    /** A projection must be current and cover the whole ledger; a stale one can still serve old rankings. */
    static List<String> projectionViolations(String label, ProjectionStatus status) {
        List<String> violations = new ArrayList<>();
        if (!status.isCurrent()) {
            violations.add(label + ": projection is " + status.state() + " " + status.diagnostics());
        }
        if (status.coveredSequence() != status.ledgerSequence()) {
            violations.add(label + ": projection covers " + status.coveredSequence() + " of "
                    + status.ledgerSequence() + " ledger sequences");
        }
        return violations;
    }

    private static String digests(Snapshot s) {
        List<String> out = new ArrayList<>();
        s.exports.forEach((scope, e) -> out.add(scope + "=" + e.exportDigest().substring(0, 16)));
        return String.join(", ", out);
    }

    // --- G5: resource accounting ----------------------------------------------------------------------

    static ForgeGate accountingGate(ForgeFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        int available = 0;
        int unavailable = 0;
        for (ForgeFixture.Execution e : fixture.executions()) {
            ExecutionSummary summary = s.summaries.get(e.id());
            ForgeFixture.ExpectedChain expected = fixture.expectations().chains().get(e.id());
            if (expected == null) {
                violations.add(e.id() + ": no hand-verified expectation");
                continue;
            }
            CostToAcceptedOutcome cost = summary.cost();
            long in = reported(summary, UsageKind.INPUT_TOKENS);
            long out = reported(summary, UsageKind.OUTPUT_TOKENS);
            // The usage of the chain is checked for every execution, whether or not a cost can be derived from it.
            if (in != expected.inputTokens() || out != expected.outputTokens()) {
                violations.add(e.id() + ": chain tokens " + in + "/" + out + " but hand-verified "
                        + expected.inputTokens() + "/" + expected.outputTokens());
            }
            if (!summary.postAcceptanceStages().isEmpty()) {
                violations.add(e.id() + ": unexpected post-acceptance stages");
            }
            String costText;
            violations.addAll(costViolations(e.id(), expected, cost, fixture.price().currency()));
            if (expected.cost().isEmpty()) {
                unavailable++;
                if (summary.chainScope() != ChainScope.ALL_RECORDED_NO_ACCEPTANCE) {
                    violations.add(e.id() + ": chain scope " + summary.chainScope()
                            + " but a non-validated execution covers every recorded attempt");
                }
                costText = "cost unavailable (scope " + summary.chainScope() + ")";
            } else {
                available++;
                costText = cost.amounts().size() == 1 ? "cost " + cost.amounts().get(0).amount().toPlainString()
                        + " " + cost.amounts().get(0).currency().getCurrencyCode() : "cost " + cost.status();
            }
            evidence.add(String.format(java.util.Locale.ROOT, "%-9s chain tokens %d/%d, %s (stages %d)",
                    e.id(), in, out, costText, summary.chainStages().size()));
        }
        // The export carries the same numbers: its own usage values are recomputed, not just its cost.
        int exportChecked = 0;
        for (ExperienceExportReport report : s.exports.values()) {
            for (AnnotatedSample annotated : report.samples()) {
                String exec = annotated.sample().executionId().value();
                ExecutionSummary summaryOfExport = s.summaries.get(exec);
                if (summaryOfExport == null) { // unknown execution: reported by the isolation and success gates
                    continue;
                }
                CostToAcceptedOutcome viaSummary = summaryOfExport.cost();
                if (!annotated.cost().equals(viaSummary)) {
                    violations.add(exec + ": export cost differs from the summary cost");
                }
                ForgeFixture.ExpectedChain expected = fixture.expectations().chains().get(exec);
                if (expected != null) {
                    exportChecked++;
                    violations.addAll(exportedUsageViolations(annotated.sample(), expected,
                            summaryOfExport.chainStages().size()));
                }
            }
        }
        evidence.add("usage recomputed from the exported samples' own stage counters: " + exportChecked
                + " executions");
        evidence.add(0, "chains with usage checked against hand-verified totals: " + fixture.executions().size()
                + " (" + available + " with a cost, " + unavailable + " with an unavailable cost)");
        return ForgeGate.of(ForgeGate.RESOURCE_ACCOUNTING,
                "complete attempt-chain usage and hypothetical CostToAcceptedOutcome equal the hand-verified values",
                violations, evidence);
    }

    /**
     * The cost to an accepted outcome against the hand-verified expectation: unavailable (never zero, no amounts)
     * when none is expected; otherwise available, in exactly one currency, the fixture's, and equal to the expected
     * amount. A right number in another currency is wrong.
     */
    static List<String> costViolations(String exec, ForgeFixture.ExpectedChain expected, CostToAcceptedOutcome cost,
                                       String currencyCode) {
        List<String> violations = new ArrayList<>();
        if (expected.cost().isEmpty()) {
            if (cost.status() != CostToAcceptedOutcome.Status.UNAVAILABLE || !cost.amounts().isEmpty()) {
                violations.add(exec + ": cost " + cost.status() + " but judged UNAVAILABLE (never zero)");
            }
            return violations;
        }
        if (cost.status() != CostToAcceptedOutcome.Status.AVAILABLE || cost.amounts().size() != 1) {
            violations.add(exec + ": cost " + cost.status() + " " + cost.reasons());
            return violations;
        }
        CurrencyCost amount = cost.amounts().get(0);
        if (!amount.currency().getCurrencyCode().equals(currencyCode)) {
            violations.add(exec + ": cost is in " + amount.currency().getCurrencyCode() + " but the fixture prices "
                    + "in " + currencyCode);
        }
        if (amount.unrounded().compareTo(expected.cost().get()) != 0) {
            violations.add(exec + ": chain cost " + amount.unrounded().toPlainString() + " but hand-verified "
                    + expected.cost().get().toPlainString());
        }
        return violations;
    }

    private static long reported(ExecutionSummary summary, UsageKind kind) {
        UsageTotals.KindUsage usage = summary.chainUsage().byKind().get(kind);
        return usage == null ? -1 : usage.reported();
    }

    // --- G6: comparison -------------------------------------------------------------------------------

    static ForgeGate comparisonGate(ForgeFixture fixture, Snapshot s) {
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
        Map<String, AnnotatedSample> exported = new LinkedHashMap<>();
        s.exports.values().forEach(e -> e.samples().forEach(a -> exported.putIfAbsent(
                a.sample().executionId().value(), a)));
        return corpusLines(fixture, exported, attemptResults(s));
    }

    /**
     * One line per experience built from what the export reports: label, acceptance and cost of the exported
     * sample. The judged value is shown only beside a value that differs from it, and an execution the export
     * lacks is marked MISSING, so a failing gate is never hidden behind rows that display the expected values.
     */
    static List<ForgeCorpusLine> corpusLines(ForgeFixture fixture, Map<String, AnnotatedSample> exported,
                                             Map<String, String> attemptResults) {
        List<ForgeCorpusLine> lines = new ArrayList<>();
        for (ForgeFixture.Attempt a : fixture.attempts()) {
            ForgeFixture.Execution e = fixture.execution(a.execution());
            AnnotatedSample sample = exported.get(e.id());
            String label;
            String acceptance;
            String cost;
            if (sample == null) {
                label = observed("MISSING", e.expectedLabel());
                acceptance = observed("MISSING", e.expectedAcceptance());
                cost = "MISSING";
            } else {
                label = observed(sample.sample().label().name(), e.expectedLabel());
                acceptance = observed(sample.sample().acceptance().name(), e.expectedAcceptance());
                cost = sample.cost().status() == CostToAcceptedOutcome.Status.AVAILABLE
                        ? sample.cost().amounts().get(0).amount().toPlainString() + " "
                        + sample.cost().amounts().get(0).currency().getCurrencyCode()
                        : sample.cost().status() == CostToAcceptedOutcome.Status.UNAVAILABLE ? "unavailable"
                        : sample.cost().status().name().toLowerCase(java.util.Locale.ROOT);
            }
            lines.add(new ForgeCorpusLine(a.experienceId(), e.scopeKey(),
                    attemptResults.getOrDefault(a.experienceId(), "UNKNOWN"), label, acceptance, cost));
        }
        return lines;
    }

    private static String observed(String actual, String judged) {
        return actual.equals(judged) ? actual : actual + " (judged " + judged + ")";
    }
}
