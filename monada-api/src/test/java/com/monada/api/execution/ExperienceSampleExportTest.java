package com.monada.api.execution;

import static com.monada.api.execution.SampleLedger.A1;
import static com.monada.api.execution.SampleLedger.A2;
import static com.monada.api.execution.SampleLedger.TESTS;
import static org.junit.jupiter.api.Assertions.*;

import com.monada.api.execution.ExperienceSample.EligibilityCode;
import com.monada.api.execution.ExperienceSample.GateState;
import com.monada.api.execution.ExperienceSample.Label;
import com.monada.api.execution.ExperienceSample.SampleAttempt;
import com.monada.api.execution.ExperienceSample.UsageState;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.UsageKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperienceSampleExportTest {
    static final ScopeId S1 = ScopeId.of("scope-1");
    static final ScopeId S2 = ScopeId.of("scope-2");
    @TempDir Path root;

    /** Every page of an export, following cursors. */
    static List<ExperienceSamplePage> exportAll(ExecutionMemory m, ExportRequest first) {
        List<ExperienceSamplePage> pages = new ArrayList<>();
        ExperienceSamplePage page = m.exportSamples(first);
        pages.add(page);
        while (page.hasMore()) {
            page = m.exportSamples(ExportRequest.resume(page.next().get(), first.pageSize()));
            pages.add(page);
        }
        return pages;
    }

    static List<ExperienceSample> samples(List<ExperienceSamplePage> pages) {
        return pages.stream().flatMap(p -> p.samples().stream()).toList();
    }

    static ExperienceSample byExecution(List<ExperienceSample> all, String execution) {
        return all.stream().filter(s -> s.executionId().equals(ExecutionId.of(execution))).findFirst().orElseThrow();
    }

    static String digestOf(List<ExperienceSamplePage> pages) {
        return pages.stream().map(ExperienceSamplePage::digest).collect(Collectors.joining(","));
    }

    /** SHA-256 of every file under the root, keyed by relative path. */
    static Map<String, String> storageDigests(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    @Test
    void sameScopeCutoffAndOptionsGiveIdenticalBoundedExportsAfterReopen() {
        List<ExperienceSamplePage> before;
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            before = exportAll(m, ExportRequest.firstPage(S1, 3));
        }
        assertEquals(List.of(3, 3, 1), before.stream().map(p -> p.samples().size()).toList());
        assertTrue(before.stream().allMatch(p -> p.samples().size() <= 3));
        assertEquals(List.of(true, true, false), before.stream().map(ExperienceSamplePage::hasMore).toList());

        try (ExecutionMemory reopened = ExecutionMemory.open(root, S1);
             ExecutionMemory readOnly = ExecutionMemory.openReadOnly(root, S1)) {
            ExportRequest request = ExportRequest.firstPage(S1, 3);
            assertEquals(digestOf(before), digestOf(exportAll(reopened, request)));
            assertEquals(digestOf(before), digestOf(exportAll(readOnly, request)));
            assertEquals(before.stream().map(ExperienceSampleJson::toJson).toList(),
                    exportAll(reopened, request).stream().map(ExperienceSampleJson::toJson).toList());
        }
        assertTrue(before.stream().allMatch(p -> p.schema().equals("execution-sample/1")));
        // page size changes the pagination, never a sample
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, S1)) {
            assertEquals(samples(before), samples(exportAll(m, ExportRequest.firstPage(S1, 500))));
        }
    }

    @Test
    void exportLeavesLedgerProjectionAndFeedbackFilesByteIdentical() throws Exception {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(S1).state());
        }
        Map<String, String> before = storageDigests(root);
        assertTrue(before.keySet().stream().anyMatch(f -> f.endsWith("events-000001.log")), before.keySet().toString());
        assertTrue(before.keySet().stream().anyMatch(f -> f.endsWith("projection.current")), before.keySet().toString());
        assertTrue(before.keySet().stream().anyMatch(f -> f.endsWith("projection-checkpoint.log")));
        assertTrue(before.keySet().stream().anyMatch(f -> f.contains("feedback")), before.keySet().toString());
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            List<ExperienceSamplePage> pages = exportAll(m, ExportRequest.first(S1, ExportCutoff.latest(), 2,
                    ExportView.DIAGNOSTIC, ExportEvidencePolicy.requiring(QualityDimension.SECURITY)));
            // a current projection is reported by version, and the export did not need it
            assertTrue(pages.stream().allMatch(p -> p.projection().state() == ProjectionStatus.State.CURRENT
                    && p.projection().coversCutoff() && p.projection().formatVersion() == 1
                    && p.projection().textScheme().equals("summary-text-v1")));
            assertEquals(before, storageDigests(root)); // also while the writer is still open
        }
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, S1)) {
            exportAll(m, ExportRequest.firstPage(S1, 1));
        }
        assertEquals(before, storageDigests(root));
    }

    @Test
    void exportDoesNotDependOnTheProjectionButReportsIt() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
        }
        Experiences.removeProjection(root, S1); // a scope recorded before projections existed
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            ExperienceSamplePage missing = m.exportSamples(ExportRequest.firstPage(S1, 100));
            assertEquals(ProjectionStatus.State.MISSING, missing.projection().state());
            assertFalse(missing.projection().coversCutoff());
            assertEquals(7, missing.samples().size());
            m.rebuildProjection(S1);
            ExperienceSamplePage current = m.exportSamples(ExportRequest.firstPage(S1, 100));
            assertEquals(ProjectionStatus.State.CURRENT, current.projection().state());
            assertTrue(current.projection().coversCutoff());
            // samples come from the ledger alone: only the projection stamp differs
            assertEquals(missing.samples(), current.samples());
            assertEquals(missing.ledger(), current.ledger());
            assertNotEquals(missing.digest(), current.digest());
        }
    }

    @Test
    void labelsKeepNegativeIncompleteAndUnknownSamplesWithReasons() {
        List<ExperienceSample> all;
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            all = samples(exportAll(m, ExportRequest.firstPage(S1, 500)));
        }
        assertEquals(7, all.size());
        assertEquals(List.of("x-ok", "x-rej", "x-pending", "x-unk", "x-claim", "x-ok2", "x-run"),
                all.stream().map(s -> s.executionId().value()).toList());

        ExperienceSample ok = byExecution(all, "x-ok");
        assertEquals(Label.SUCCESS, ok.label());
        assertTrue(ok.eligibility().eligible());
        assertEquals(DerivedAcceptance.Status.VALIDATED_ACCEPTED, ok.acceptance());
        // the failed first attempt is visible but is not a success
        assertEquals(List.of(false, true), ok.attempts().stream().map(SampleAttempt::accepted).toList());
        assertEquals(Optional.of(AttemptResult.FAILED), ok.attempts().get(0).result());
        assertEquals(1, all.stream().filter(s -> s.execution().task().value().equals("t-ok") && s.label() == Label.SUCCESS
                && s.executionId().value().equals("x-ok")).count());

        ExperienceSample rejected = byExecution(all, "x-rej");
        assertEquals(Label.FAILURE, rejected.label());
        assertTrue(rejected.eligibility().eligible());
        assertTrue(rejected.gates().isEmpty());

        ExperienceSample pending = byExecution(all, "x-pending");
        assertEquals(Label.UNLABELED, pending.label());
        assertEquals(EligibilityCode.OUTCOME_PENDING, pending.eligibility().reasons().get(0).code());
        assertTrue(pending.outcome().isEmpty());
        assertTrue(pending.attempts().get(0).isInProgress());

        ExperienceSample unknown = byExecution(all, "x-unk");
        assertEquals(Label.UNLABELED, unknown.label());
        assertEquals(DerivedAcceptance.Status.ACCEPTED_UNVALIDATED, unknown.acceptance());
        assertEquals(EligibilityCode.GATE_UNKNOWN, unknown.eligibility().reasons().get(0).code());
        assertEquals(Optional.of(QualityDimension.TESTS), unknown.eligibility().reasons().get(0).dimension());
        assertEquals(GateState.UNKNOWN, unknown.gates().get(0).state());

        ExperienceSample claim = byExecution(all, "x-claim");
        assertEquals(Label.UNLABELED, claim.label());
        assertEquals(EligibilityCode.OUTCOME_IMPORTED_CLAIM, claim.eligibility().reasons().get(0).code());
        assertEquals(OutcomeOrigin.IMPORTED_CLAIM, claim.outcome().get().outcome().origin());

        assertEquals(Label.UNLABELED, byExecution(all, "x-run").label());
        // a second validated execution of the same task is a different, independent fact
        assertEquals(Label.SUCCESS, byExecution(all, "x-ok2").label());
    }

    @Test
    void unknownMandatoryGateIsNeverSuccessInEitherViewButDiagnosticExplainsIt() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            for (ExportView view : ExportView.values()) {
                ExperienceSample s = byExecution(samples(exportAll(m, ExportRequest.first(S1, ExportCutoff.latest(),
                        100, view, ExportEvidencePolicy.executionPolicyOnly()))), "x-unk");
                assertEquals(Label.UNLABELED, s.label(), view.name());
                assertEquals(s.diagnostics().isPresent(), view == ExportView.DIAGNOSTIC);
                assertEquals(DerivedAcceptance.Status.ACCEPTED_UNVALIDATED, s.acceptance());
                // the claim is visible in both views
                assertEquals(Optional.of(com.monada.core.execution.OutcomeStatus.ACCEPTED),
                        s.outcome().map(o -> o.outcome().status()));
            }
            ExperienceSample diagnostic = byExecution(samples(exportAll(m, ExportRequest.first(S1,
                    ExportCutoff.latest(), 100, ExportView.DIAGNOSTIC, ExportEvidencePolicy.executionPolicyOnly()))),
                    "x-unk");
            assertEquals(List.of("TESTS: UNKNOWN"), diagnostic.diagnostics().get().acceptanceReasons());
            assertEquals(1, diagnostic.diagnostics().get().outcomeHistory().size());
        }
    }

    @Test
    void additionalGatesOnlyTightenALabel() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            List<ExperienceSample> strict = samples(exportAll(m, ExportRequest.first(S1, ExportCutoff.latest(), 100,
                    ExportView.ADAPTER_READY, ExportEvidencePolicy.requiring(QualityDimension.SECURITY))));
            ExperienceSample ok = byExecution(strict, "x-ok");
            assertEquals(Label.UNLABELED, ok.label());
            assertEquals(EligibilityCode.GATE_MISSING, ok.eligibility().reasons().get(0).code());
            assertEquals(Optional.of(QualityDimension.SECURITY), ok.eligibility().reasons().get(0).dimension());
            assertEquals(List.of(QualityDimension.TESTS, QualityDimension.SECURITY).stream().sorted().toList(),
                    ok.requiredGates());
            // a failure stays a failure, however strict the gates
            assertEquals(Label.FAILURE, byExecution(strict, "x-rej").label());
        }
    }

    @Test
    void correctionsReplaceFactsAndNeverCountTwice() {
        long beforeCorrection;
        EventId evidence;
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            SampleLedger ledger = new SampleLedger(m, S1, "p");
            SampleLedger.Exec e = ledger.exec("t", "x", TESTS).attempt(A1, 1, Optional.empty());
            e.stage(A1, SampleLedger.CHEAP, SampleLedger.in(100));
            evidence = e.evidence(A1, QualityDimension.TESTS, ObservationState.PASS, "1");
            e.finish(A1, AttemptResult.COMPLETED);
            e.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            beforeCorrection = m.history(S1).highWatermark();
            EventId c1 = e.correctEvidence(evidence, 2, A1, QualityDimension.TESTS, ObservationState.FAIL, "1");
            e.correctEvidence(c1, 3, A1, QualityDimension.TESTS, ObservationState.PASS, "1");
        }
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, S1)) {
            ExperienceSample now = byExecution(samples(exportAll(m, ExportRequest.firstPage(S1, 10))), "x");
            ExperienceSample then = byExecution(samples(exportAll(m, ExportRequest.first(S1,
                    ExportCutoff.atSequence(beforeCorrection), 10, ExportView.ADAPTER_READY,
                    ExportEvidencePolicy.executionPolicyOnly()))), "x");
            ExperienceSample middle = byExecution(samples(exportAll(m, ExportRequest.first(S1,
                    ExportCutoff.atSequence(beforeCorrection + 1), 10, ExportView.ADAPTER_READY,
                    ExportEvidencePolicy.executionPolicyOnly()))), "x");

            assertEquals(Label.SUCCESS, then.label());
            assertTrue(then.revisions().isEmpty());
            assertEquals(Label.UNLABELED, middle.label()); // revision 2 says FAIL
            assertEquals(EligibilityCode.GATE_FAIL, middle.eligibility().reasons().get(0).code());

            assertEquals(Label.SUCCESS, now.label());
            // one effective evidence event: revision 3; the two older revisions are named, not counted
            assertEquals(1, now.attempts().stream().filter(SampleAttempt::accepted).findFirst().get()
                    .evidence().size());
            var effective = now.attempts().get(0).evidence().get(0).ref();
            assertEquals(3, effective.revision());
            assertEquals(1, now.revisions().size());
            assertEquals(3, now.revisions().get(0).effective().revision());
            assertEquals(List.of(1, 2), now.revisions().get(0).superseded().stream()
                    .map(r -> r.revision()).toList());
            assertEquals(evidence, now.revisions().get(0).superseded().get(0).eventId());
            assertEquals(GateState.PASS, now.gates().get(0).state());
            assertEquals(effective, now.gates().get(0).ref().get());
        }
    }

    @Test
    void retryingRecordDoesNotChangeTheExport() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            SampleLedger ledger = new SampleLedger(m, S1, "p");
            ledger.standardRun();
            String before = digestOf(exportAll(m, ExportRequest.firstPage(S1, 100)));
            m.history(S1).entries().forEach(h -> assertEquals(RecordResult.Status.IDEMPOTENT,
                    m.record(h.event()).status()));
            assertEquals(before, digestOf(exportAll(m, ExportRequest.firstPage(S1, 100))));
        }
    }

    @Test
    void foreignScopeEventsNeverAppearIncludingAtPageBoundaries() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S2)) {
            SampleLedger l = new SampleLedger(m, S2, "q");
            for (int i = 0; i < 4; i++) {
                SampleLedger.Exec e = l.exec("t" + i, "foreign-" + i, TESTS).attempt(A1, 1, Optional.empty());
                e.evidence(A1, QualityDimension.TESTS, ObservationState.PASS, "1");
                e.finish(A1, AttemptResult.COMPLETED);
                e.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            }
        }
        List<ExperienceSamplePage> s1pages;
        String cursorOfS2;
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            for (int size = 1; size <= 7; size++) {
                List<ExperienceSample> got = samples(exportAll(m, ExportRequest.firstPage(S1, size)));
                assertEquals(7, got.size(), "page size " + size);
                assertTrue(got.stream().allMatch(s -> s.execution().scope().equals(S1)
                        && !s.executionId().value().startsWith("foreign")));
            }
            s1pages = exportAll(m, ExportRequest.firstPage(S1, 2));
            assertThrows(IllegalArgumentException.class, () -> m.exportSamples(ExportRequest.firstPage(S2, 2)));
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, S2)) {
            List<ExperienceSamplePage> pages = exportAll(m, ExportRequest.firstPage(S2, 3));
            assertEquals(4, samples(pages).size());
            assertTrue(samples(pages).stream().allMatch(s -> s.execution().scope().equals(S2)));
            ExportCursor foreign = s1pages.get(0).next().get();
            // an S1 cursor can neither resume nor be smuggled into an S2 request
            assertThrows(IllegalArgumentException.class, () -> m.exportSamples(ExportRequest.resume(foreign, 2)));
            assertThrows(IllegalArgumentException.class, () -> new ExportRequest(S2, Optional.empty(), 2,
                    ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly(), Optional.of(foreign)));
        }
    }

    @Test
    void usageProvenanceIsExplicitForUnknownNotMeasuredAndDeclaredNone() {
        List<ExperienceSample> all;
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            all = samples(exportAll(m, ExportRequest.firstPage(S1, 100)));
        }
        var repair = byExecution(all, "x-ok").attempts().get(1).stages().get(0);
        Map<UsageKind, ExperienceSample.SampleUsage> usage = new TreeMap<>();
        repair.usage().forEach(u -> usage.put(u.kind(), u));
        assertEquals(UsageKind.values().length, usage.size());
        assertEquals(UsageState.REPORTED, usage.get(UsageKind.INPUT_TOKENS).state());
        assertEquals(5000, usage.get(UsageKind.INPUT_TOKENS).value().getAsLong());
        assertEquals(UsageState.UNKNOWN, usage.get(UsageKind.OUTPUT_TOKENS).state());
        assertTrue(usage.get(UsageKind.OUTPUT_TOKENS).value().isEmpty());
        assertEquals(UsageState.NOT_MEASURED, usage.get(UsageKind.REQUESTS).state());
        assertTrue(usage.get(UsageKind.REQUESTS).value().isEmpty());
        var first = byExecution(all, "x-ok").attempts().get(0).stages().get(0);
        assertEquals(UsageState.ESTIMATED, first.usage().stream()
                .filter(u -> u.kind() == UsageKind.OUTPUT_TOKENS).findFirst().get().state());
        var declared = byExecution(all, "x-unk").attempts().get(0).stages().get(0);
        assertTrue(declared.usage().stream().allMatch(u -> u.state() == UsageState.DECLARED_NO_BILLABLE_USAGE));
        assertEquals(STRONG_WORKER, repair.route().worker());
        // exact refs: each stage points at its ledger event and revision
        assertEquals(1, repair.ref().revision());
        assertEquals(Optional.of(A2), repair.ref().attempt());
    }

    private static final String STRONG_WORKER = "worker-b";

    @Test
    void cutoffsAreExplicitAndAPinnedCutoffIgnoresLaterAppends() {
        long cutoff;
        String pinned;
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            SampleLedger ledger = new SampleLedger(m, S1, "p");
            ledger.standardRun();
            cutoff = m.history(S1).highWatermark();
            ExportRequest request = ExportRequest.first(S1, ExportCutoff.atSequence(cutoff), 3,
                    ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly());
            pinned = digestOf(exportAll(m, request));

            // later work: a new execution and a correction of x-unk's evidence
            SampleLedger.Exec late = ledger.exec("t-late", "x-late", TESTS).attempt(A1, 1, Optional.empty());
            late.finish(A1, AttemptResult.COMPLETED);
            assertEquals(pinned, digestOf(exportAll(m, request)));
            assertEquals(7, samples(exportAll(m, request)).size());
            assertEquals(8, samples(exportAll(m, ExportRequest.firstPage(S1, 3))).size());

            // an open cursor keeps its snapshot too
            ExperienceSamplePage first = m.exportSamples(request);
            late.outcome(Outcome.cancelled(OutcomeOrigin.VALIDATED));
            assertEquals(first.next().get(), m.exportSamples(request).next().get());
            assertEquals(3, m.exportSamples(ExportRequest.resume(first.next().get(), 3)).samples().size());

            assertThrows(IllegalArgumentException.class, () -> m.exportSamples(ExportRequest.first(S1,
                    ExportCutoff.atSequence(m.history(S1).highWatermark() + 1), 3, ExportView.ADAPTER_READY,
                    ExportEvidencePolicy.executionPolicyOnly())));
            ExperienceSamplePage empty = m.exportSamples(ExportRequest.first(S1, ExportCutoff.atSequence(0), 3,
                    ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly()));
            assertTrue(empty.samples().isEmpty());
            assertFalse(empty.hasMore());
        }
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, S1)) {
            // a recordedAt cutoff resolves to the longest prefix recorded at or before the instant
            ExperienceSamplePage byTime = m.exportSamples(ExportRequest.first(S1,
                    ExportCutoff.recordedUpTo(SampleLedger.T0.plusSeconds(1)), 50, ExportView.ADAPTER_READY,
                    ExportEvidencePolicy.executionPolicyOnly()));
            assertEquals(1, byTime.ledger().cutoffSequence());
            assertEquals(1, byTime.samples().size());
            assertEquals(Label.UNLABELED, byTime.samples().get(0).label());
            ExperienceSamplePage before = m.exportSamples(ExportRequest.first(S1,
                    ExportCutoff.recordedUpTo(Instant.parse("2025-12-31T00:00:00Z")), 50, ExportView.ADAPTER_READY,
                    ExportEvidencePolicy.executionPolicyOnly()));
            assertEquals(0, before.ledger().cutoffSequence());
            assertTrue(before.samples().isEmpty());
            // the ledger checkpoint identifies the exact prefix
            ExperienceSamplePage atCutoff = m.exportSamples(ExportRequest.first(S1, ExportCutoff.atSequence(cutoff),
                    50, ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly()));
            ExperienceSamplePage atCutoffAgain = m.exportSamples(ExportRequest.first(S1,
                    ExportCutoff.atSequence(cutoff), 1, ExportView.ADAPTER_READY,
                    ExportEvidencePolicy.executionPolicyOnly()));
            assertEquals(atCutoff.ledger(), atCutoffAgain.ledger());
            assertNotEquals(atCutoff.ledger().prefixDigest(), byTime.ledger().prefixDigest());
            assertEquals(64, atCutoff.ledger().prefixDigest().length());
        }
    }

    @Test
    void cursorsAreStatelessOpaqueAndRejectTampering() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            new SampleLedger(m, S1, "p").standardRun();
            ExperienceSamplePage first = m.exportSamples(ExportRequest.first(S1, ExportCutoff.latest(), 2,
                    ExportView.DIAGNOSTIC, ExportEvidencePolicy.requiring(QualityDimension.SECURITY)));
            ExportCursor cursor = first.next().get();
            assertEquals(cursor, ExportCursor.parse(cursor.token()));
            assertEquals(ExportView.DIAGNOSTIC, cursor.view());
            // reuse returns the same page
            ExperienceSamplePage a = m.exportSamples(ExportRequest.resume(cursor, 2));
            ExperienceSamplePage b = m.exportSamples(ExportRequest.resume(ExportCursor.parse(cursor.token()), 2));
            assertEquals(a, b);
            assertEquals(ExportView.DIAGNOSTIC, a.view());
            assertEquals(List.of(QualityDimension.SECURITY), a.additionalGates());
            // options cannot drift under a cursor
            assertThrows(IllegalArgumentException.class, () -> new ExportRequest(S1, Optional.empty(), 2,
                    ExportView.ADAPTER_READY, new ExportEvidencePolicy(cursor.additionalGates()),
                    Optional.of(cursor)));
            assertThrows(IllegalArgumentException.class, () -> new ExportRequest(S1,
                    Optional.of(ExportCutoff.latest()), 2, cursor.view(),
                    new ExportEvidencePolicy(cursor.additionalGates()), Optional.of(cursor)));
            assertThrows(IllegalArgumentException.class, () -> ExportCursor.parse("not-a-cursor"));
            assertThrows(IllegalArgumentException.class, () -> ExportCursor.parse(
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("x9|1|0|ADAPTER_READY|-|s".getBytes())));
            // a cursor beyond this ledger is rejected
            assertThrows(IllegalArgumentException.class, () -> m.exportSamples(ExportRequest.resume(
                    new ExportCursor(S1, 10_000, 0, ExportView.ADAPTER_READY, java.util.Set.of()), 2)));
            // bounded pages
            assertThrows(IllegalArgumentException.class, () -> ExportRequest.firstPage(S1, 0));
            assertThrows(IllegalArgumentException.class, () -> m.exportSamples(ExportRequest.firstPage(S1, 501)));
            // pass: history cursors are a different token
            assertThrows(IllegalArgumentException.class, () -> ExportCursor.parse(
                    new HistoryCursor(S1, 3, 1).token()));
        }
    }

    @Test
    void diagnosticViewListsIgnoredEvidenceWhileAdapterReadyDropsOtherPolicies() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            SampleLedger ledger = new SampleLedger(m, S1, "p");
            SampleLedger.Exec e = ledger.exec("t", "x", TESTS).attempt(A1, 1, Optional.empty());
            e.evidence(A1, QualityDimension.TESTS, ObservationState.PASS, "2"); // another policy version
            e.finish(A1, AttemptResult.COMPLETED);
            e.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExperienceSample adapter = byExecution(samples(exportAll(m, ExportRequest.firstPage(S1, 5))), "x");
            ExperienceSample diagnostic = byExecution(samples(exportAll(m, ExportRequest.first(S1,
                    ExportCutoff.latest(), 5, ExportView.DIAGNOSTIC, ExportEvidencePolicy.executionPolicyOnly()))),
                    "x");
            assertEquals(Label.UNLABELED, adapter.label());
            assertEquals(adapter.label(), diagnostic.label());
            assertEquals(EligibilityCode.GATE_MISSING, adapter.eligibility().reasons().get(0).code());
            assertEquals(GateState.MISSING, adapter.gates().get(0).state());
            assertTrue(adapter.attempts().get(0).evidence().isEmpty());
            assertTrue(adapter.diagnostics().isEmpty());
            assertEquals(1, diagnostic.attempts().get(0).evidence().size());
            assertFalse(diagnostic.attempts().get(0).evidence().get(0).observations().get(0).policyMatches());
            assertEquals(1, diagnostic.diagnostics().get().ignoredObservations().size());
            assertTrue(diagnostic.diagnostics().get().ignoredObservations().get(0).reason().contains("policy gates v2"));
        }
    }

    @Test
    void canonicalJsonIsStableInspectableAndEscaped() {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            SampleLedger ledger = new SampleLedger(m, S1, "p");
            ledger.standardRun();
            ExperienceSamplePage page = m.exportSamples(ExportRequest.firstPage(S1, 1));
            String json = ExperienceSampleJson.toJson(page);
            assertEquals(json, ExperienceSampleJson.toJson(m.exportSamples(ExportRequest.firstPage(S1, 1))));
            assertTrue(json.startsWith("{\n  \"schema\": \"execution-sample/1\",\n  \"scope\": \"scope-1\","), json);
            assertTrue(json.endsWith("}\n"));
            assertFalse(json.contains("\r"));
            assertTrue(json.contains("\"label\": \"SUCCESS\""));
            assertTrue(json.contains("\"state\": \"UNKNOWN\""));
            assertTrue(json.contains("\"nextCursor\": \"" + page.next().get().token() + "\""));
            assertTrue(json.contains("\"revision\": 1"));
            assertFalse(json.contains("similarity"));
            assertEquals(64, page.digest().length());
            assertEquals(page.digest(), ExperienceSampleJson.digest(page));
            // no line ends in whitespace
            json.lines().forEach(l -> assertEquals(l.stripTrailing(), l));
        }
    }

    @Test
    void exportOnAClosedInstanceAndNullRequestsFailCleanly() {
        ExecutionMemory m = ExecutionMemory.open(root, S1);
        assertThrows(NullPointerException.class, () -> m.exportSamples(null));
        m.close();
        assertThrows(IllegalStateException.class, () -> m.exportSamples(ExportRequest.firstPage(S1, 1)));
        assertThrows(UncheckedIOException.class, () -> ExecutionMemory.openReadOnly(root.resolve("missing"), S1)
                .exportSamples(ExportRequest.firstPage(S1, 1)));
    }

    @Test
    void emptyScopeExportsAnEmptyPage() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, S1)) {
            ExperienceSamplePage page = m.exportSamples(ExportRequest.firstPage(S1, 5));
            assertTrue(page.samples().isEmpty());
            assertFalse(page.hasMore());
            assertEquals(0, page.ledger().cutoffSequence());
        }
    }
}
