package com.monada.evaluation.execution;

import com.monada.api.execution.AttemptView;
import com.monada.api.execution.ExecutionMemory;
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
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.execution.ForgeSampleChecks.AttemptRule;
import com.monada.evaluation.execution.ForgeSampleChecks.Expectation;
import com.monada.evaluation.execution.MultiAttemptEvaluationReport.AttemptLine;
import com.monada.evaluation.execution.MultiAttemptEvaluationReport.HitLine;
import com.monada.evaluation.execution.MultiAttemptEvaluationReport.QueryLine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs the multi-attempt evaluation (issue #121) through public APIs only: record, close, reopen, reconcile the
 * projection, scoped recall, a paged latest export per project and a paged export of one project at a fixed cutoff.
 * Every recalled hit is resolved to its exact {@code ATTEMPT_FINISHED} event and joined by execution id to the
 * exported sample ({@link AttemptJoin}); the gates then compare execution label, attempt result, validated
 * acceptance and reference provenance with the hand-judged values.
 *
 * <p>The whole flow runs twice in two fresh directories; the second run only feeds the determinism gate. It uses no
 * network, no provider and no Forge installation, and it never touches fixture {@code forge-experience-v1}.
 */
public final class MultiAttemptEvaluator {

    private MultiAttemptEvaluator() {
    }

    /**
     * Evaluates {@code fixture} using two fresh stores created below {@code workDir} (created if missing). Every call
     * gets its own {@code run-*} directories; the caller owns {@code workDir} and removes it.
     */
    public static MultiAttemptEvaluationReport evaluate(MultiAttemptFixture fixture, Path workDir) {
        Run first = runOnce(fixture, freshRunDirectory(workDir));
        Run second = runOnce(fixture, freshRunDirectory(workDir));
        List<ForgeGate> gates = new ArrayList<>(first.gates);
        gates.add(determinismGate(first, second));
        Snapshot s = first.snapshot;
        return new MultiAttemptEvaluationReport(fixture.version(), fixture.provenance(), fixture.attempts().size(),
                fixture.executions().size(), fixture.scopeIds().size(), fixture.pageSize(), fixture.cutoffScope(),
                fixture.cutoffEvent(), s.cutoffSequence, s.latestLines, s.cutoffLines, queryLines(fixture, s), gates,
                first.elapsed);
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

    private record Run(Snapshot snapshot, List<ForgeGate> gates, Map<String, Long> elapsed, String fingerprint) {
    }

    private static Run runOnce(MultiAttemptFixture fixture, Path root) {
        Map<String, Long> elapsed = new LinkedHashMap<>();
        List<String> setup = new ArrayList<>();
        long t = System.nanoTime();
        for (String scopeKey : fixture.scopeIds().keySet()) {
            try (ExecutionMemory memory = ExecutionMemory.open(root, MultiAttemptFixtureRecorder.scope(fixture,
                    scopeKey))) {
                for (RecordResult r : MultiAttemptFixtureRecorder.recordScope(memory, fixture, scopeKey)) {
                    if (r.status() != RecordResult.Status.APPENDED) {
                        setup.add(scopeKey + ": first recording was " + r.status() + " " + r.reason());
                    }
                }
            }
        }
        elapsed.put("record", System.nanoTime() - t);
        t = System.nanoTime();
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ScopeId scope = MultiAttemptFixtureRecorder.scope(fixture, scopeKey);
            try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) { // reopen and reconcile
                setup.addAll(ForgeExperienceEvaluator.projectionViolations(scopeKey + " rebuild",
                        memory.rebuildProjection(scope)));
            }
        }
        elapsed.put("reopen+reconcile", System.nanoTime() - t);
        Snapshot s = snapshot(fixture, root, elapsed);
        try {
            List<ForgeGate> gates = List.of(resolutionGate(fixture, s, setup), joinGate(fixture, s),
                    exportGate(fixture, s), cutoffGate(fixture, s), isolationGate(fixture, s));
            return new Run(s, gates, elapsed, s.fingerprint());
        } finally {
            s.close();
        }
    }

    // --- snapshot of a stable store -------------------------------------------------------------------

    /** Everything observed through public read APIs on one stable store. */
    static final class Snapshot {
        final Map<String, ExecutionMemory> memories = new LinkedHashMap<>();
        final Map<String, ProjectionStatus> status = new LinkedHashMap<>();
        final Map<String, ExperienceExportReport> exports = new LinkedHashMap<>(); // latest, per scope key
        ExperienceExportReport cutoffExport;
        long cutoffSequence;
        final Map<String, List<ExperienceHit>> hits = new LinkedHashMap<>(); // query id -> raw hits
        final Map<String, List<AttemptJoin.Row>> latestRows = new LinkedHashMap<>();
        final Map<String, List<AttemptJoin.Row>> cutoffRows = new LinkedHashMap<>(); // cutoff scope queries only
        final List<AttemptLine> latestLines = new ArrayList<>();
        final List<AttemptLine> cutoffLines = new ArrayList<>();

        String fingerprint() {
            StringBuilder out = new StringBuilder();
            status.forEach((scope, st) -> out.append(scope).append(' ').append(st.state()).append(' ')
                    .append(st.coveredSequence()).append('/').append(st.ledgerSequence()).append('\n'));
            exports.forEach((scope, e) -> out.append(scope).append(' ').append(e.exportDigest()).append('\n'));
            out.append("cutoff ").append(cutoffSequence).append(' ').append(cutoffExport.exportDigest()).append('\n');
            hits.forEach((id, list) -> {
                out.append(id);
                list.forEach(h -> out.append(' ').append(ForgeExperienceEvaluator.experienceId(h.ref())).append('@')
                        .append(h.ref().revision()).append(':').append(h.similarity()));
                out.append('\n');
            });
            latestRows.forEach((id, rows) -> out.append(id).append(" latest ").append(rows).append('\n'));
            cutoffRows.forEach((id, rows) -> out.append(id).append(" cutoff ").append(rows).append('\n'));
            return out.toString();
        }

        void close() {
            memories.values().forEach(ExecutionMemory::close);
        }
    }

    static Snapshot snapshot(MultiAttemptFixture fixture, Path root, Map<String, Long> elapsed) {
        Snapshot s = new Snapshot();
        PriceBook prices = PriceBook.of(List.of(MultiAttemptFixtureRecorder.priceSnapshot(fixture)));
        try {
            for (String scopeKey : fixture.scopeIds().keySet()) {
                ScopeId scope = MultiAttemptFixtureRecorder.scope(fixture, scopeKey);
                s.memories.put(scopeKey, ExecutionMemory.openReadOnly(root, scope));
                s.status.put(scopeKey, s.memories.get(scopeKey).projectionStatus(scope));
            }
            long t = System.nanoTime();
            for (String scopeKey : fixture.scopeIds().keySet()) {
                s.exports.put(scopeKey, export(fixture, s, scopeKey, ExportCutoff.latest(), prices));
            }
            ScopeId cutoffScope = MultiAttemptFixtureRecorder.scope(fixture, fixture.cutoffScope());
            ExecutionMemory cutoffMemory = s.memories.get(fixture.cutoffScope());
            s.cutoffSequence = cutoffMemory.loadEvent(cutoffScope, EventId.of(fixture.cutoffEvent()), 1)
                    .map(HistoryEntry::sequence).orElseThrow(() -> new IllegalStateException(
                            "cutoff event is not in the ledger: " + fixture.cutoffEvent()));
            s.cutoffExport = export(fixture, s, fixture.cutoffScope(), ExportCutoff.atSequence(s.cutoffSequence),
                    prices);
            elapsed.put("export", System.nanoTime() - t);

            t = System.nanoTime();
            List<ExperienceSample> cutoffSamples = samples(s.cutoffExport);
            for (MultiAttemptFixture.Query q : fixture.queries()) {
                ScopeId scope = MultiAttemptFixtureRecorder.scope(fixture, q.scopeKey());
                ExperienceRecall recall = s.memories.get(q.scopeKey()).recall(scope, q.text(), fixture.recallLimit());
                s.hits.put(q.id(), recall.hits());
                ExperienceExportReport latest = s.exports.get(q.scopeKey());
                long latestCutoff = latest.pages().get(0).ledger().cutoffSequence();
                s.latestRows.put(q.id(), recall.hits().stream()
                        .map(h -> AttemptJoin.join(h, samples(latest), latestCutoff)).toList());
                if (q.scopeKey().equals(fixture.cutoffScope())) {
                    s.cutoffRows.put(q.id(), recall.hits().stream()
                            .map(h -> AttemptJoin.join(h, cutoffSamples, s.cutoffSequence)).toList());
                }
            }
            elapsed.put("recall+join", System.nanoTime() - t);

            for (String scopeKey : fixture.scopeIds().keySet()) {
                s.latestLines.addAll(attemptLines(s, scopeKey, s.exports.get(scopeKey)));
            }
            s.cutoffLines.addAll(attemptLines(s, fixture.cutoffScope(), s.cutoffExport));
            return s;
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
    }

    private static ExperienceExportReport export(MultiAttemptFixture fixture, Snapshot s, String scopeKey,
                                                 ExportCutoff cutoff, PriceBook prices) {
        ScopeId scope = MultiAttemptFixtureRecorder.scope(fixture, scopeKey);
        return ExperienceExportReport.build(s.memories.get(scopeKey), ExportRequest.first(scope, cutoff,
                fixture.pageSize(), ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly()), prices,
                Optional.empty());
    }

    private static List<ExperienceSample> samples(ExperienceExportReport report) {
        return report.samples().stream().map(AnnotatedSample::sample).toList();
    }

    /** One line per attempt as the export reports it, in export order. */
    private static List<AttemptLine> attemptLines(Snapshot s, String scopeKey, ExperienceExportReport report) {
        ExecutionMemory memory = s.memories.get(scopeKey);
        List<AttemptLine> lines = new ArrayList<>();
        for (ExperienceSample sample : samples(report)) {
            for (ExperienceSample.SampleAttempt a : sample.attempts()) {
                String finish = a.finish().map(r -> r.eventId().value() + " r" + r.revision() + " @"
                        + memory.loadEvent(sample.execution().scope(), r.eventId(), r.revision())
                        .map(h -> Long.toString(h.sequence())).orElse("?")).orElse(AttemptJoin.NONE);
                lines.add(new AttemptLine(ForgeFixtureRecorder.experienceId(sample.executionId().value(), a.id()),
                        scopeKey, a.result().map(Enum::name).orElse(MultiAttemptFixture.UNFINISHED),
                        a.finish().map(ExperienceRef::revision).orElse(0), a.accepted(), validatedAccepted(sample, a),
                        sample.label().name(), sample.acceptance().name(), finish));
            }
        }
        return lines;
    }

    /** The attempt is the COMPLETED attempt that a validated acceptance names (same rule as {@link AttemptJoin}). */
    static boolean validatedAccepted(ExperienceSample sample, ExperienceSample.SampleAttempt a) {
        return sample.acceptance() == com.monada.api.execution.DerivedAcceptance.Status.VALIDATED_ACCEPTED
                && a.accepted() && a.result().filter(r -> r == AttemptResult.COMPLETED).isPresent();
    }

    private static List<QueryLine> queryLines(MultiAttemptFixture fixture, Snapshot s) {
        List<QueryLine> lines = new ArrayList<>();
        for (MultiAttemptFixture.Query q : fixture.queries()) {
            List<ExperienceHit> hits = s.hits.get(q.id());
            List<HitLine> hitLines = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                AttemptJoin.Row row = s.latestRows.get(q.id()).get(i);
                String cutoff = s.cutoffRows.containsKey(q.id()) ? s.cutoffRows.get(q.id()).get(i).status().name()
                        : "n/a";
                hitLines.add(new HitLine(i + 1, hits.get(i).similarity(), row, cutoff,
                        q.relevant().contains(row.experienceId())));
            }
            lines.add(new QueryLine(q.id(), q.scopeKey(), q.text(), q.relevant(), q.judgment(), hitLines));
        }
        return lines;
    }

    // --- MA1: exact attempt resolution ----------------------------------------------------------------

    /**
     * Every hit loads its exact {@code ATTEMPT_FINISHED} event, with all coordinates equal to the ledger envelope, at
     * the latest revision the fixture declares and with the latest result and solution. An unfinished attempt is
     * never recalled, and every finished attempt is recalled at least once (so the joins below are not vacuous).
     */
    static ForgeGate resolutionGate(MultiAttemptFixture fixture, Snapshot s, List<String> setupViolations) {
        List<String> violations = new ArrayList<>(setupViolations);
        int checked = 0;
        Set<String> recalled = new LinkedHashSet<>();
        for (MultiAttemptFixture.Query q : fixture.queries()) {
            ScopeId scope = MultiAttemptFixtureRecorder.scope(fixture, q.scopeKey());
            ExecutionMemory memory = s.memories.get(q.scopeKey());
            for (ExperienceHit h : s.hits.get(q.id())) {
                checked++;
                recalled.add(ForgeExperienceEvaluator.experienceId(h.ref()));
                violations.addAll(hitViolations(fixture, memory, scope, q.id(), h));
            }
        }
        int finished = 0;
        for (MultiAttemptFixture.Attempt a : fixture.attempts()) {
            if (!a.finished()) {
                if (recalled.contains(a.experienceId())) {
                    violations.add(a.experienceId() + ": an unfinished attempt was recalled");
                }
            } else {
                finished++;
                if (!recalled.contains(a.experienceId())) {
                    violations.add(a.experienceId() + ": a finished attempt was never recalled, so its join is "
                            + "unchecked");
                }
            }
        }
        List<String> evidence = List.of("hits resolved to their exact ATTEMPT_FINISHED event: " + checked + " over "
                + fixture.queries().size() + " queries; finished attempts recalled: " + recalled.size() + "/" + finished
                + "; unfinished attempts recalled: none expected (" + (fixture.attempts().size() - finished)
                + " in the fixture)");
        return ForgeGate.of(MultiAttemptEvaluationReport.EXACT_ATTEMPT_RESOLUTION,
                "every hit is the exact attempt finish at its latest revision; unfinished attempts are never recalled",
                violations, evidence);
    }

    static List<String> hitViolations(MultiAttemptFixture fixture, ExecutionMemory memory, ScopeId scope,
                                      String queryId, ExperienceHit h) {
        List<String> violations = new ArrayList<>();
        ExperienceRef ref = h.ref();
        Optional<HistoryEntry> exact = memory.loadEvent(scope, ref.eventId(), ref.revision());
        if (exact.isEmpty() || !exact.get().equals(h.event())) {
            violations.add(queryId + ": hit " + ref.eventId().value() + " r" + ref.revision()
                    + " does not equal the exact ledger load");
        }
        exact.ifPresent(entry -> violations.addAll(ForgeExperienceEvaluator.coordinateViolations(queryId + ": hit",
                scope, ref, entry)));
        AttemptId attempt = ref.attempt().orElse(null);
        if (attempt == null) {
            violations.add(queryId + ": hit without an attempt");
            return violations;
        }
        String id = ForgeFixtureRecorder.experienceId(ref.execution().value(), attempt);
        Optional<MultiAttemptFixture.Attempt> judged = fixture.findAttempt(id);
        if (judged.isEmpty()) {
            violations.add(queryId + ": hit " + id + " is not in the fixture");
            return violations;
        }
        MultiAttemptFixture.Attempt a = judged.get();
        if (!a.finished()) {
            violations.add(queryId + ": hit " + id + " addresses an attempt that never finished");
            return violations;
        }
        if (ref.revision() != a.latestRevision()) {
            violations.add(queryId + ": " + id + " recalled at revision " + ref.revision() + ", latest is "
                    + a.latestRevision());
        }
        violations.addAll(ForgeSampleChecks.factViolations(queryId + ": hit " + id, Expectation.of(
                EventKind.ATTEMPT_FINISHED, AttemptRule.of(attempt), ForgeSampleChecks.finishedWithSolution(
                        AttemptResult.valueOf(a.latestResult()), a.latestSolution()),
                "result " + a.latestResult() + " and the latest solution"), h.event()));
        Optional<AttemptView> view = memory.loadAttempt(scope, ref.execution(), attempt);
        if (view.isEmpty() || !view.get().solutionSummary().equals(Optional.of(a.latestSolution()))) {
            violations.add(queryId + ": " + id + " loads a different solution than its latest revision");
        }
        return violations;
    }

    // --- MA2: attempt-level join ----------------------------------------------------------------------

    /**
     * Every hit joins, by scope and execution id, to the latest exported sample of its execution, at the attempt whose
     * finish is exactly the hit's reference. The joined dimensions equal the judged ones: the raw execution label and
     * acceptance (unchanged), the attempt result, and whether the attempt is the validated accepted attempt. A failed
     * or cancelled attempt is never reported as accepted, even when its execution is {@code SUCCESS}.
     */
    static ForgeGate joinGate(MultiAttemptFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int joined = 0;
        int notCompletedInSuccess = 0;
        int acceptedHits = 0;
        for (MultiAttemptFixture.Query q : fixture.queries()) {
            for (AttemptJoin.Row r : s.latestRows.get(q.id())) {
                violations.addAll(rowViolations(fixture, q.id(), r));
                if (!r.joined()) {
                    continue;
                }
                joined++;
                if (r.executionLabel().equals("SUCCESS") && !r.attemptResult().equals("COMPLETED")) {
                    notCompletedInSuccess++;
                }
                if (r.validatedAcceptedAttempt()) {
                    acceptedHits++;
                }
            }
        }
        if (notCompletedInSuccess == 0) {
            violations.add("no failed or cancelled attempt of a SUCCESS execution was joined: the scenario of issue "
                    + "#121 was not exercised");
        }
        List<String> evidence = List.of("hits joined by execution id to the latest export: " + joined
                + "; failed/cancelled attempts of SUCCESS executions joined: " + notCompletedInSuccess
                + " (all reported validated-accepted=no, raw label SUCCESS kept); validated accepted attempts joined: "
                + acceptedHits);
        return ForgeGate.of(MultiAttemptEvaluationReport.ATTEMPT_RESULT_JOIN,
                "attempt result, validated acceptance and raw execution label stay distinct and equal the judged values",
                violations, evidence);
    }

    /** Violations of one latest join row against the judged attempt and execution. */
    static List<String> rowViolations(MultiAttemptFixture fixture, String queryId, AttemptJoin.Row r) {
        List<String> violations = new ArrayList<>();
        String what = queryId + ": " + r.experienceId();
        if (!r.joined()) {
            violations.add(what + " did not join its exported sample (" + r.status() + ")");
            return violations;
        }
        if (!r.attemptResult().equals("COMPLETED") && r.validatedAcceptedAttempt()) {
            violations.add(what + " is a " + r.attemptResult() + " attempt reported as the accepted attempt");
        }
        Optional<MultiAttemptFixture.Attempt> judged = fixture.findAttempt(r.experienceId());
        if (judged.isEmpty()) {
            violations.add(what + " is not in the fixture");
            return violations;
        }
        MultiAttemptFixture.Attempt a = judged.get();
        MultiAttemptFixture.Execution e = fixture.execution(a.execution());
        if (!r.attemptResult().equals(a.latestResult())) {
            violations.add(what + ": attempt result " + r.attemptResult() + " but judged " + a.latestResult());
        }
        if (r.validatedAcceptedAttempt() != a.judgedAccepted()) {
            violations.add(what + ": validated accepted attempt " + r.validatedAcceptedAttempt() + " but judged "
                    + a.judgedAccepted());
        }
        if (r.markedAccepted() != e.namesAttempt(a.ordinal())) {
            violations.add(what + ": outcome marker " + r.markedAccepted() + " but the recorded outcome "
                    + (e.namesAttempt(a.ordinal()) ? "names" : "does not name") + " this attempt");
        }
        if (!r.executionLabel().equals(e.expectedLabel())) {
            violations.add(what + ": execution label " + r.executionLabel() + " but judged " + e.expectedLabel());
        }
        if (!r.executionAcceptance().equals(e.expectedAcceptance())) {
            violations.add(what + ": execution acceptance " + r.executionAcceptance() + " but judged "
                    + e.expectedAcceptance());
        }
        if (r.revision() != a.latestRevision()) {
            violations.add(what + ": joined at revision " + r.revision() + " but latest is " + a.latestRevision());
        }
        return violations;
    }

    // --- MA3: exported attempts -----------------------------------------------------------------------

    /**
     * Each latest export holds exactly the fixture's executions of its project, with the judged label, acceptance and
     * eligibility reasons, and every attempt with its judged result, finish revision and accepted markers; every
     * provenance reference resolves exactly, and the paged export equals the unpaged one.
     */
    static ForgeGate exportGate(MultiAttemptFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int samples = 0;
        int attempts = 0;
        List<String> pages = new ArrayList<>();
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ScopeId scope = MultiAttemptFixtureRecorder.scope(fixture, scopeKey);
            ExecutionMemory memory = s.memories.get(scopeKey);
            ExperienceExportReport report = s.exports.get(scopeKey);
            violations.addAll(ForgeExperienceEvaluator.paginationViolations(memory, scope, report, fixture.pageSize(),
                    fixture.executionsOf(scopeKey).size()));
            pages.add(scopeKey + "=" + report.pages().size());
            List<String> exported = new ArrayList<>();
            for (ExperienceSample sample : samples(report)) {
                samples++;
                String exec = sample.executionId().value();
                exported.add(exec);
                Optional<MultiAttemptFixture.Execution> judged = fixture.findExecution(exec)
                        .filter(e -> e.scopeKey().equals(scopeKey));
                if (judged.isEmpty()) {
                    violations.add(scopeKey + ": exported execution " + exec + " is not a fixture execution of "
                            + "this project");
                    continue;
                }
                violations.addAll(sampleViolations(fixture, judged.get(), sample));
                violations.addAll(ForgeExperienceEvaluator.referenceViolations(memory, scope, sample));
                attempts += sample.attempts().size();
            }
            List<String> expected = fixture.executionsOf(scopeKey).stream().map(MultiAttemptFixture.Execution::id)
                    .toList();
            if (!new java.util.TreeSet<>(exported).equals(new java.util.TreeSet<>(expected))
                    || exported.size() != expected.size()) {
                violations.add(scopeKey + ": export holds " + exported + " but the fixture records " + expected);
            }
        }
        List<String> evidence = List.of("samples checked: " + samples + " with " + attempts + " attempts; export "
                + "pages at size " + fixture.pageSize() + ": " + pages + " (paged export equals the unpaged export); "
                + "every provenance reference resolves to its exact ledger event");
        return ForgeGate.of(MultiAttemptEvaluationReport.EXPORT_ATTEMPTS,
                "exported samples keep per-execution labels and per-attempt results, markers and revisions as judged",
                violations, evidence);
    }

    static List<String> sampleViolations(MultiAttemptFixture fixture, MultiAttemptFixture.Execution judged,
                                         ExperienceSample sample) {
        List<String> violations = new ArrayList<>();
        String exec = judged.id();
        if (!sample.label().name().equals(judged.expectedLabel())) {
            violations.add(exec + ": label " + sample.label() + " but judged " + judged.expectedLabel());
        }
        if (!sample.acceptance().name().equals(judged.expectedAcceptance())) {
            violations.add(exec + ": acceptance " + sample.acceptance() + " but judged " + judged.expectedAcceptance());
        }
        List<String> reasons = sample.eligibility().reasons().stream().map(r -> r.code().name()
                + r.dimension().map(d -> "(" + d + ")").orElse("")).toList();
        if (!reasons.equals(judged.expectedReasons())) {
            violations.add(exec + ": eligibility reasons " + reasons + " but judged " + judged.expectedReasons());
        }
        if (sample.eligibility().eligible() != judged.expectedReasons().isEmpty()) {
            violations.add(exec + ": eligible=" + sample.eligibility().eligible() + " contradicts the judged reasons");
        }
        List<MultiAttemptFixture.Attempt> own = fixture.attemptsOf(exec);
        List<Integer> ordinals = sample.attempts().stream().map(ExperienceSample.SampleAttempt::ordinal).toList();
        List<Integer> judgedOrdinals = own.stream().map(MultiAttemptFixture.Attempt::ordinal).toList();
        if (!ordinals.equals(judgedOrdinals)) {
            violations.add(exec + ": exported attempts " + ordinals + " but recorded " + judgedOrdinals);
        }
        long accepted = sample.attempts().stream().filter(a -> validatedAccepted(sample, a)).count();
        if (accepted > 1) {
            violations.add(exec + ": " + accepted + " validated accepted attempts in one execution");
        }
        for (MultiAttemptFixture.Attempt fa : own) {
            sample.attempts().stream().filter(a -> a.ordinal() == fa.ordinal()).findFirst().ifPresent(a -> {
                String id = fa.experienceId();
                String result = a.result().map(Enum::name).orElse(MultiAttemptFixture.UNFINISHED);
                if (!result.equals(fa.latestResult())) {
                    violations.add(id + ": exported result " + result + " but judged " + fa.latestResult());
                }
                int revision = a.finish().map(ExperienceRef::revision).orElse(0);
                if (revision != fa.latestRevision()) {
                    violations.add(id + ": exported finish revision " + revision + " but judged "
                            + fa.latestRevision());
                }
                if (a.accepted() != judged.namesAttempt(fa.ordinal())) {
                    violations.add(id + ": accepted marker " + a.accepted() + " but the recorded outcome "
                            + (judged.namesAttempt(fa.ordinal()) ? "names" : "does not name") + " it");
                }
                if (validatedAccepted(sample, a) != fa.judgedAccepted()) {
                    violations.add(id + ": validated accepted attempt " + validatedAccepted(sample, a)
                            + " but judged " + fa.judgedAccepted());
                }
            });
        }
        return violations;
    }

    // --- MA4: cutoff ----------------------------------------------------------------------------------

    /**
     * The export at the fixture's cutoff equals the hand-judged state at that cutoff (a later correction, attempt or
     * outcome never leaks into it), and joining the latest hits against it leaves every hit recorded after the cutoff
     * unjoined ({@code BEYOND_CUTOFF}) while every earlier hit joins with the judged cutoff values.
     */
    static ForgeGate cutoffGate(MultiAttemptFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        ExperienceExportReport report = s.cutoffExport;
        long checkpoint = report.pages().get(0).ledger().cutoffSequence();
        if (checkpoint != s.cutoffSequence) {
            violations.add("cutoff export reports checkpoint " + checkpoint + " but was requested at "
                    + s.cutoffSequence);
        }
        violations.addAll(cutoffPaginationViolations(s.memories.get(fixture.cutoffScope()),
                MultiAttemptFixtureRecorder.scope(fixture, fixture.cutoffScope()), report, s.cutoffSequence,
                fixture.pageSize()));
        Map<String, MultiAttemptFixture.CutoffAttempt> judged = new TreeMap<>();
        fixture.cutoff().forEach(c -> judged.put(c.experienceId(), c));
        Set<String> exported = new java.util.TreeSet<>();
        for (ExperienceSample sample : samples(report)) {
            if (sample.lastSequence() > s.cutoffSequence) {
                violations.add(sample.executionId().value() + ": uses sequence " + sample.lastSequence()
                        + " beyond the cutoff " + s.cutoffSequence);
            }
            for (ExperienceSample.SampleAttempt a : sample.attempts()) {
                String id = ForgeFixtureRecorder.experienceId(sample.executionId().value(), a.id());
                exported.add(id);
                MultiAttemptFixture.CutoffAttempt c = judged.get(id);
                if (c == null) {
                    violations.add(id + ": present in the cutoff export but judged absent at the cutoff");
                    continue;
                }
                violations.addAll(cutoffViolations(id, c, sample.label().name(), sample.acceptance().name(),
                        a.result().map(Enum::name).orElse(MultiAttemptFixture.UNFINISHED),
                        a.finish().map(ExperienceRef::revision).orElse(0), validatedAccepted(sample, a)));
            }
        }
        for (String id : judged.keySet()) {
            if (!exported.contains(id)) {
                violations.add(id + ": judged present at the cutoff but missing from the cutoff export");
            }
        }
        int beyond = 0;
        int joined = 0;
        for (Map.Entry<String, List<AttemptJoin.Row>> e : s.cutoffRows.entrySet()) {
            for (AttemptJoin.Row r : e.getValue()) {
                String what = e.getKey() + ": " + r.experienceId() + " @" + r.sequence();
                if (r.sequence() > s.cutoffSequence) {
                    beyond++;
                    if (r.status() != AttemptJoin.Status.BEYOND_CUTOFF) {
                        violations.add(what + " lies beyond the cutoff but joined as " + r.status());
                    }
                    continue;
                }
                joined++;
                MultiAttemptFixture.CutoffAttempt c = judged.get(r.experienceId());
                if (!r.joined() || c == null) {
                    violations.add(what + " lies within the cutoff but did not join a judged attempt (" + r.status()
                            + ")");
                    continue;
                }
                violations.addAll(cutoffViolations(what, c, r.executionLabel(), r.executionAcceptance(),
                        r.attemptResult(), r.revision(), r.validatedAcceptedAttempt()));
            }
        }
        if (beyond == 0) {
            violations.add("no hit lies beyond the cutoff: the cutoff scenario was not exercised");
        }
        List<String> evidence = List.of("cutoff " + fixture.cutoffScope() + " after " + fixture.cutoffEvent()
                + " = sequence " + s.cutoffSequence + "; attempts at the cutoff: " + exported.size() + " (judged "
                + judged.size() + "); cutoff export pages at size " + fixture.pageSize() + ": "
                + report.pages().size() + " (paged export equals the unpaged export at the same cutoff; every page "
                + "carries the same checkpoint and projection stamp)",
                "latest hits against the cutoff export: " + joined + " joined with the judged cutoff values, " + beyond
                        + " beyond the cutoff and left unjoined");
        return ForgeGate.of(MultiAttemptEvaluationReport.CUTOFF,
                "the cutoff export equals the judged state at the cutoff; later facts never join an earlier state",
                violations, evidence);
    }

    /**
     * The paged export at an explicit cutoff must equal one unpaged export at the same cutoff, sample for sample and
     * in order (so a duplicated or dropped sample is a violation), traverse as many pages as the page size demands
     * (at least two) and describe one frozen snapshot on every page: schema, scope, view, additional gates and ledger
     * checkpoint equal those of the first page and of the unpaged export, the checkpoint is the requested cutoff, the
     * projection stamp is that of the first page and covers the cutoff, and the continuation flags are consistent.
     */
    static List<String> cutoffPaginationViolations(ExecutionMemory memory, ScopeId scope,
                                                   ExperienceExportReport report, long cutoffSequence, int pageSize) {
        List<String> violations = new ArrayList<>();
        com.monada.api.execution.ExperienceSamplePage reference = memory.exportSamples(ExportRequest.first(scope,
                ExportCutoff.atSequence(cutoffSequence), 50,
                ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly()));
        if (reference.hasMore()) {
            violations.add(scope.value() + ": the unpaged cutoff export does not fit in one page");
        }
        List<ExperienceSample> paged = samples(report);
        if (!reference.samples().equals(paged)) {
            violations.add(scope.value() + ": the paged cutoff export differs from the unpaged cutoff export in "
                    + "content or order");
        }
        int expectedPages = (reference.samples().size() + pageSize - 1) / pageSize;
        List<com.monada.api.execution.ExperienceSamplePage> pages = report.pages();
        if (pages.size() != expectedPages || pages.size() < 2) {
            violations.add(scope.value() + ": cutoff export used " + pages.size() + " pages, expected "
                    + expectedPages + " (at least 2) for " + reference.samples().size() + " executions at page size "
                    + pageSize);
        }
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            String label = scope.value() + " cutoff page " + (i + 1) + "/" + pages.size();
            if (!com.monada.api.execution.ExperienceSamplePage.SCHEMA.equals(page.schema())
                    || !page.schema().equals(reference.schema()) || !page.scope().equals(reference.scope())
                    || page.view() != reference.view() || page.view() != ExportView.ADAPTER_READY
                    || !page.additionalGates().equals(reference.additionalGates())
                    || !page.additionalGates().isEmpty()) {
                violations.add(label + ": schema/scope/view/gates " + page.schema() + "/" + page.scope().value() + "/"
                        + page.view() + "/" + page.additionalGates() + " differ from the unpaged cutoff export");
            }
            if (!page.ledger().equals(reference.ledger()) || !page.ledger().equals(pages.get(0).ledger())
                    || page.ledger().cutoffSequence() != cutoffSequence) {
                violations.add(label + ": ledger checkpoint " + page.ledger() + " but the cutoff is " + cutoffSequence
                        + " and the unpaged export has " + reference.ledger());
            }
            if (!page.projection().equals(pages.get(0).projection()) || !page.projection().coversCutoff()) {
                violations.add(label + ": projection stamp " + page.projection() + " differs from the first page or "
                        + "does not cover the cutoff");
            }
            boolean last = i == pages.size() - 1;
            if (page.hasMore() == last || page.next().isPresent() != page.hasMore()) {
                violations.add(label + ": hasMore=" + page.hasMore() + " next=" + page.next().isPresent()
                        + (last ? " on the last page" : " on a page that is not the last"));
            }
        }
        return violations;
    }

    private static List<String> cutoffViolations(String what, MultiAttemptFixture.CutoffAttempt c, String label,
                                                 String acceptance, String result, int revision, boolean accepted) {
        List<String> violations = new ArrayList<>();
        if (!label.equals(c.label()) || !acceptance.equals(c.acceptance())) {
            violations.add(what + ": at the cutoff " + label + "/" + acceptance + " but judged " + c.label() + "/"
                    + c.acceptance());
        }
        if (!result.equals(c.result()) || revision != c.revision()) {
            violations.add(what + ": at the cutoff " + result + " r" + revision + " but judged " + c.result() + " r"
                    + c.revision());
        }
        if (accepted != c.accepted()) {
            violations.add(what + ": at the cutoff validated accepted attempt " + accepted + " but judged "
                    + c.accepted());
        }
        return violations;
    }

    // --- MA5: scope isolation -------------------------------------------------------------------------

    /**
     * No hit or exported sample of another project, cross-project probes are rejected, the execution with an identical
     * summary in the other project never appears, and independent executions with identical summaries in one project
     * keep distinct references and join to their own samples.
     */
    static ForgeGate isolationGate(MultiAttemptFixture fixture, Snapshot s) {
        List<String> violations = new ArrayList<>();
        int hits = 0;
        for (MultiAttemptFixture.Query q : fixture.queries()) {
            ScopeId scope = MultiAttemptFixtureRecorder.scope(fixture, q.scopeKey());
            List<ExperienceHit> list = s.hits.get(q.id());
            List<AttemptJoin.Row> rows = s.latestRows.get(q.id());
            for (int i = 0; i < list.size(); i++) {
                hits++;
                ExperienceHit h = list.get(i);
                String exec = h.ref().execution().value();
                if (!h.ref().scope().equals(scope)) {
                    violations.add(q.id() + ": foreign-scope hit " + h.ref().scope().value());
                }
                Optional<MultiAttemptFixture.Execution> known = fixture.findExecution(exec);
                if (known.isEmpty() || !known.get().scopeKey().equals(q.scopeKey())) {
                    violations.add(q.id() + ": hit on an execution outside the queried project: " + exec);
                }
                if (!rows.get(i).experienceId().startsWith(exec + "/")) {
                    violations.add(q.id() + ": hit on " + exec + " joined as " + rows.get(i).experienceId());
                }
            }
        }
        int probes = 0;
        for (String scopeKey : fixture.scopeIds().keySet()) {
            ScopeId own = MultiAttemptFixtureRecorder.scope(fixture, scopeKey);
            for (ExperienceSample sample : samples(s.exports.get(scopeKey))) {
                if (!sample.execution().scope().equals(own)) {
                    violations.add(scopeKey + ": export holds a foreign execution " + sample.executionId().value());
                }
            }
            ExecutionMemory memory = s.memories.get(scopeKey);
            for (String otherKey : fixture.scopeIds().keySet()) {
                if (otherKey.equals(scopeKey)) {
                    continue;
                }
                ScopeId other = MultiAttemptFixtureRecorder.scope(fixture, otherKey);
                String foreign = fixture.executionsOf(otherKey).get(0).id();
                probes += 3;
                violations.addAll(mustReject(scopeKey + " memory, recall of " + otherKey,
                        () -> memory.recall(other, "Fix CSV import", 5)));
                violations.addAll(mustReject(scopeKey + " memory, export of " + otherKey,
                        () -> memory.exportSamples(ExportRequest.firstPage(other, 5))));
                violations.addAll(mustReject(scopeKey + " memory, load of " + otherKey,
                        () -> memory.loadExecution(other, ExecutionId.of(foreign))));
            }
        }
        List<String> twins = new ArrayList<>();
        for (int i = 0; i < fixture.executions().size(); i++) {
            for (int j = i + 1; j < fixture.executions().size(); j++) {
                MultiAttemptFixture.Execution a = fixture.executions().get(i);
                MultiAttemptFixture.Execution b = fixture.executions().get(j);
                if (a.summary().equals(b.summary()) && a.scopeKey().equals(b.scopeKey())) {
                    violations.addAll(twinViolations(fixture, s, a, b));
                    twins.add(a.id() + "/" + b.id() + " (shared projected atoms: " + sharedAtoms(fixture, s, a, b)
                            + ")");
                }
            }
        }
        List<String> evidence = List.of("hits checked: " + hits + "; foreign-scope hits or samples: none allowed; "
                + "cross-scope probes rejected: " + probes, "same-project identical-summary executions with distinct "
                + "references and samples: " + twins + "; identical summaries across projects never cross");
        return ForgeGate.of(MultiAttemptEvaluationReport.SCOPE_ISOLATION,
                "no foreign-scope leakage; identical summaries never merge or cross executions", violations, evidence);
    }

    /** Two executions with identical summaries in one project: distinct refs, each joined to its own sample. */
    private static List<String> twinViolations(MultiAttemptFixture fixture, Snapshot s,
                                               MultiAttemptFixture.Execution a, MultiAttemptFixture.Execution b) {
        List<String> violations = new ArrayList<>();
        Map<String, Set<String>> refs = new TreeMap<>();
        for (MultiAttemptFixture.Query q : fixture.queries()) {
            if (!q.scopeKey().equals(a.scopeKey())) {
                continue;
            }
            for (ExperienceHit h : s.hits.get(q.id())) {
                String exec = h.ref().execution().value();
                if (exec.equals(a.id()) || exec.equals(b.id())) {
                    refs.computeIfAbsent(exec, k -> new java.util.TreeSet<>()).add(h.ref().eventId().value());
                }
            }
        }
        Set<String> left = refs.getOrDefault(a.id(), Set.of());
        Set<String> right = refs.getOrDefault(b.id(), Set.of());
        if (left.isEmpty() || right.isEmpty()) {
            violations.add(a.id() + "/" + b.id() + ": both identical-summary executions must be recalled");
        }
        Set<String> shared = new java.util.TreeSet<>(left);
        shared.retainAll(right);
        if (!shared.isEmpty()) {
            violations.add(a.id() + "/" + b.id() + ": identical-summary executions share references " + shared);
        }
        return violations;
    }

    /**
     * How many projected atoms both executions are recalled through: identical attempt texts share one atom while
     * keeping distinct references (the projector's atom identity), which is why the join is by execution id only.
     */
    private static int sharedAtoms(MultiAttemptFixture fixture, Snapshot s, MultiAttemptFixture.Execution a,
                                   MultiAttemptFixture.Execution b) {
        Map<String, Set<String>> atoms = new TreeMap<>();
        fixture.queries().stream().filter(q -> q.scopeKey().equals(a.scopeKey()))
                .flatMap(q -> s.hits.get(q.id()).stream())
                .forEach(h -> atoms.computeIfAbsent(h.ref().execution().value(), k -> new java.util.TreeSet<>())
                        .add(h.atomId()));
        Set<String> shared = new java.util.TreeSet<>(atoms.getOrDefault(a.id(), Set.of()));
        shared.retainAll(atoms.getOrDefault(b.id(), Set.of()));
        return shared.size();
    }

    private static List<String> mustReject(String what, Runnable call) {
        try {
            call.run();
            return List.of(what + " was served instead of being rejected");
        } catch (IllegalArgumentException expected) {
            return List.of();
        }
    }

    // --- MA6: determinism -----------------------------------------------------------------------------

    /**
     * Two runs inside one JVM share its hash-seeded iteration orders, so this gate cannot see an order that differs
     * between JVMs; {@code MultiAttemptCrossJvmDeterminismTest} compares the command's report across separate JVMs.
     */
    private static ForgeGate determinismGate(Run first, Run second) {
        List<String> violations = new ArrayList<>();
        if (!first.fingerprint.equals(second.fingerprint)) {
            violations.add("two fixed-input runs produced different exports, rankings or joins");
        }
        for (int i = 0; i < first.gates.size(); i++) {
            if (!first.gates.get(i).equals(second.gates.get(i))) {
                violations.add("gate " + first.gates.get(i).id() + " differs between runs");
            }
        }
        List<String> evidence = List.of("two fresh stores, same fixture: fingerprint " + Integer.toHexString(
                first.fingerprint.hashCode()) + " vs " + Integer.toHexString(second.fingerprint.hashCode())
                + "; elapsed timing is excluded");
        return ForgeGate.of(MultiAttemptEvaluationReport.DETERMINISM,
                "repeated fixed-input runs produce identical exports, rankings, joins and gates", violations, evidence);
    }
}
