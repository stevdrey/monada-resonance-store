package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ForgeTestSupport.sampleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageKind;
import com.monada.evaluation.execution.ForgeTestSupport.ProjectStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the exporter rules that {@link ForgeSampleChecks} mirrors (numbered as in its class javadoc) to the real
 * exporter. If one of these fails, the exporter's contract changed: decide whether the change is intended and, if
 * so, update {@code ForgeSampleChecks} (and the fixture) for that assumption. The gates themselves are not the
 * place to discover it.
 */
class ForgeSampleContractTest {
    private final ForgeFixture fixture = ForgeFixture.load();

    private static String changed(int n, String what) {
        return "contract assumption " + n + " changed: update ForgeSampleChecks (" + what + ")";
    }

    @Test
    void assumption1_onlyTheAttemptOfAnAcceptedOutcomeIsMarkedAcceptedAnImportedClaimIncluded(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample imported = sampleOf(store.samples(), "a-import");
            assertEquals(OutcomeOrigin.IMPORTED_CLAIM, imported.outcome().orElseThrow().outcome().origin());
            assertTrue(imported.attempts().get(0).accepted(), changed(1, "an imported claim marks its attempt"));
            ExperienceSample page = sampleOf(store.samples(), "a-page"); // accepted attempt is the third
            assertEquals(List.of(false, false, true), page.attempts().stream()
                    .map(ExperienceSample.SampleAttempt::accepted).toList(), changed(1, "validated acceptance"));
            assertTrue(sampleOf(store.samples(), "a-export").attempts().stream()
                    .noneMatch(ExperienceSample.SampleAttempt::accepted), changed(1, "a rejected outcome marks none"));
        }
    }

    @Test
    void assumption2_aGateStateFollowsItsObservationAndWithoutOneItIsMissing(@TempDir Path root) {
        try (ProjectStore beta = ProjectStore.open(root, fixture, "beta")) {
            ExperienceSample backup = sampleOf(beta.samples(), "b-backup"); // SECURITY failed
            ExperienceSample.Gate security = backup.gates().stream()
                    .filter(g -> g.dimension() == QualityDimension.SECURITY).findFirst().orElseThrow();
            assertEquals(ObservationState.FAIL, security.observation().orElseThrow().state());
            assertEquals(ExperienceSample.GateState.FAIL, security.state(), changed(2, "FAIL observation, FAIL gate"));
        }
        try (ProjectStore alpha = ProjectStore.open(root.resolve("alpha"), fixture)) {
            ExperienceSample tokens = sampleOf(alpha.samples(), "a-tokens"); // no TESTS evidence at all
            ExperienceSample.Gate tests = tokens.gates().stream()
                    .filter(g -> g.dimension() == QualityDimension.TESTS).findFirst().orElseThrow();
            assertTrue(tests.observation().isEmpty());
            assertEquals(ExperienceSample.GateState.MISSING, tests.state(), changed(2, "no observation, MISSING"));
        }
    }

    @Test
    void assumption3_theAdapterViewFiltersObservationsByThePolicyAndTheDiagnosticViewKeepsThem(@TempDir Path root) {
        ScopeId scope = ScopeId.of("contract-scope");
        TaskId task = TaskId.of("contract-task");
        ExecutionId exec = ExecutionId.of("contract-exec");
        AttemptId attempt = AttemptId.of("a1");
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        QualityObservation otherPolicy = new QualityObservation(QualityDimension.TESTS, ObservationState.PASS,
                OptionalDouble.empty(), Optional.empty(), "contract", "1", "forge-gates", "2", ObservationSource.TOOL,
                List.of(ArtifactRef.of("report", "x")), Optional.empty());
        try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
            memory.record(ExecutionEvent.executionStarted(EventId.of("c.start"), scope, task, exec,
                    new SourceProvenance("r", "c", "k"),
                    new EvaluationPolicy("forge-gates", "1", Set.of(QualityDimension.TESTS)), "contract", t));
            memory.record(new AttemptStarted(EventEnvelope.original(EventId.of("c.att"), scope, task, exec, attempt,
                    t, t), 1, Optional.empty(), AttemptReason.INITIAL));
            memory.record(new EvidenceRecorded(EventEnvelope.original(EventId.of("c.ev"), scope, task, exec, attempt,
                    t, t), List.of(otherPolicy), List.of()));
            memory.record(new AttemptFinished(EventEnvelope.original(EventId.of("c.fin"), scope, task, exec, attempt,
                    t, t), AttemptResult.COMPLETED, Optional.of("s"), Optional.of("l")));
            memory.record(ExecutionEvent.outcomeRecorded(EventId.of("c.out"), scope, task, exec,
                    Outcome.accepted(OutcomeOrigin.VALIDATED, attempt), t));

            ExperienceSample adapter = memory.exportSamples(ExportRequest.firstPage(scope, 10)).samples().get(0);
            assertTrue(adapter.attempts().get(0).evidence().isEmpty(),
                    changed(3, "the adapter view drops observations of another policy version"));
            assertEquals(ExperienceSample.GateState.MISSING, adapter.gates().get(0).state(),
                    changed(3, "evidence of another policy does not satisfy the gate"));
            assertEquals(ExperienceSample.Label.UNLABELED, adapter.label());

            ExperienceSample diagnostic = memory.exportSamples(ExportRequest.first(scope, ExportCutoff.latest(), 10,
                    ExportView.DIAGNOSTIC, ExportEvidencePolicy.executionPolicyOnly())).samples().get(0);
            var evidence = diagnostic.attempts().get(0).evidence();
            assertEquals(1, evidence.size(), changed(3, "the diagnostic view keeps the observation"));
            assertFalse(evidence.get(0).observations().get(0).policyMatches(), changed(3, "policyMatches flag"));
            assertEquals(1, diagnostic.diagnostics().orElseThrow().ignoredObservations().size(),
                    changed(3, "ignored observations are listed"));
        }
    }

    @Test
    void assumption4_everyStageExportsOneUsageEntryPerKindAndUnrecordedCountersAreNotMeasured(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            for (ExperienceSample sample : store.samples()) {
                for (var attempt : sample.attempts()) {
                    for (var stage : attempt.stages()) {
                        assertEquals(List.of(UsageKind.values()), stage.usage().stream()
                                .map(ExperienceSample.SampleUsage::kind).toList(),
                                changed(4, "one entry per kind, in declaration order"));
                        stage.usage().stream().filter(u -> u.kind() != UsageKind.INPUT_TOKENS
                                && u.kind() != UsageKind.OUTPUT_TOKENS).forEach(u -> {
                            assertEquals(ExperienceSample.UsageState.NOT_MEASURED, u.state(),
                                    changed(4, "unrecorded counters are NOT_MEASURED"));
                            assertTrue(u.value().isEmpty(), changed(4, "and carry no value"));
                        });
                    }
                }
            }
        }
    }

    @Test
    void assumption5_anIneligibleSampleExplainsItselfWithTheDocumentedReasons(@TempDir Path root) {
        try (ProjectStore alpha = ProjectStore.open(root, fixture)) {
            assertEquals(Set.of("GATE_MISSING(TESTS)"), reasons(sampleOf(alpha.samples(), "a-tokens")),
                    changed(5, "a mandatory gate without evidence"));
            assertEquals(Set.of("OUTCOME_IMPORTED_CLAIM"), reasons(sampleOf(alpha.samples(), "a-import")),
                    changed(5, "an imported claim and nothing else"));
            for (ExperienceSample s : alpha.samples()) {
                boolean learnable = s.label() == ExperienceSample.Label.SUCCESS
                        || s.label() == ExperienceSample.Label.FAILURE;
                assertEquals(learnable, s.eligibility().eligible(), changed(5, "eligible exactly for SUCCESS/FAILURE"));
            }
        }
        try (ProjectStore beta = ProjectStore.open(root.resolve("beta"), fixture, "beta")) {
            assertEquals(Set.of("GATE_FAIL(SECURITY)"), reasons(sampleOf(beta.samples(), "b-backup")),
                    changed(5, "a failed mandatory gate"));
        }
    }

    private static Set<String> reasons(ExperienceSample s) {
        return s.eligibility().reasons().stream()
                .map(r -> r.code().name() + r.dimension().map(d -> "(" + d + ")").orElse(""))
                .collect(Collectors.toSet());
    }

    @Test
    void assumption6_everyReferenceAddressesTheKindOfEventOfItsOrigin(@TempDir Path root) {
        for (String scopeKey : fixture.scopeIds().keySet()) {
            try (ProjectStore store = ProjectStore.open(root.resolve(scopeKey), fixture, scopeKey)) {
                for (ExperienceSample sample : store.samples()) {
                    assertEquals(List.of(), ForgeExperienceEvaluator.referenceViolations(store.memory(), store.scope(),
                            sample), changed(6, "reference kinds and coordinates of " + sample.executionId().value()));
                }
            }
        }
    }

    @Test
    void assumption7_aCorrectedFactIsExportedAtItsLatestRevisionWithEveryEarlierOneSuperseded(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample yaml = sampleOf(store.samples(), "a-yaml");
            assertEquals(1, yaml.revisions().size(), changed(7, "one revision per corrected fact"));
            var revision = yaml.revisions().get(0);
            assertEquals(2, revision.effective().revision(), changed(7, "the effective revision"));
            assertEquals(1, revision.superseded().size(), changed(7, "revision 1 is the only predecessor"));
            assertEquals(1, revision.superseded().get(0).revision());
            assertEquals(revision.effective(), yaml.attempts().get(0).finish().orElseThrow(),
                    changed(7, "the finish is exported at its latest revision"));
        }
    }

    @Test
    void assumption8_theGroupAndTheSuccessOrdinalFollowTheDocumentedKey(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample inv1 = sampleOf(store.samples(), "a-inv1");
            ExperienceSample inv2 = sampleOf(store.samples(), "a-inv2");
            ExperienceSample inv3 = sampleOf(store.samples(), "a-inv3"); // same task, another context SHA
            assertEquals(inv1.group(), inv2.group(), changed(8, "same key, same group"));
            assertFalse(inv1.group().equals(inv3.group()), changed(8, "the context SHA is part of the key"));
            assertEquals(1, inv1.successOrdinal().getAsInt(), changed(8, "ordinals follow recording order"));
            assertEquals(2, inv2.successOrdinal().getAsInt());
            assertEquals(1, inv3.successOrdinal().getAsInt());
            assertEquals("forge-gates", inv1.group().policyId());
        }
    }

    @Test
    void assumption9_theSequenceBoundsAreTheFirstAndLastLedgerSequenceOfTheExecution(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            for (ExperienceSample sample : store.samples()) {
                var history = store.memory().loadExecution(store.scope(), sample.executionId()).orElseThrow().history();
                assertEquals(history.get(0).sequence(), sample.firstSequence(), changed(9, "first sequence"));
                assertEquals(history.get(history.size() - 1).sequence(), sample.lastSequence(),
                        changed(9, "last sequence"));
            }
        }
    }

    @Test
    void assumption4b_aRecordedCounterKeepsItsValueSourceAndProvenanceAndAbsentOnesDependOnTheDeclaration(
            @TempDir Path root) {
        ScopeId scope = ScopeId.of("usage-scope");
        TaskId task = TaskId.of("usage-task");
        ExecutionId exec = ExecutionId.of("usage-exec");
        AttemptId attempt = AttemptId.of("a1");
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        var route = new com.monada.core.execution.RouteDescriptor("worker", Optional.empty(), Optional.empty(),
                Optional.empty(), com.monada.core.execution.BillingMode.UNKNOWN);
        try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
            memory.record(ExecutionEvent.executionStarted(EventId.of("u.start"), scope, task, exec,
                    new SourceProvenance("r", "c", "k"),
                    new EvaluationPolicy("forge-gates", "1", Set.of(QualityDimension.TESTS)), "usage contract", t));
            memory.record(new AttemptStarted(EventEnvelope.original(EventId.of("u.att"), scope, task, exec, attempt,
                    t, t), 1, Optional.empty(), AttemptReason.INITIAL));
            memory.record(ExecutionEvent.StageRecorded.measured(EventEnvelope.original(EventId.of("u.measured"), scope,
                    task, exec, attempt, t, t), "measured", route, t, t, List.of(
                    com.monada.core.execution.UsageCounter.reported(UsageKind.INPUT_TOKENS, 5, "provider-a"),
                    com.monada.core.execution.UsageCounter.estimated(UsageKind.OUTPUT_TOKENS, 7, "tokenizer"),
                    com.monada.core.execution.UsageCounter.unknown(UsageKind.REQUESTS, "not-reported")), List.of()));
            memory.record(ExecutionEvent.StageRecorded.noBillableUsage(EventEnvelope.original(EventId.of("u.local"),
                    scope, task, exec, attempt, t, t), "local", route, t, t, "deterministic local check", List.of()));

            var stages = memory.exportSamples(ExportRequest.firstPage(scope, 10)).samples().get(0).attempts().get(0)
                    .stages();
            var measured = stages.get(0).usage();
            assertEquals(List.of(UsageKind.values()), measured.stream().map(ExperienceSample.SampleUsage::kind).toList(),
                    changed(4, "declaration order"));
            assertEquals(new ExperienceSample.SampleUsage(UsageKind.INPUT_TOKENS, java.util.OptionalLong.of(5),
                    ExperienceSample.UsageState.REPORTED, Optional.of("provider-a")), measured.get(0),
                    changed(4, "a reported counter keeps value and source"));
            assertEquals(new ExperienceSample.SampleUsage(UsageKind.OUTPUT_TOKENS, java.util.OptionalLong.of(7),
                    ExperienceSample.UsageState.ESTIMATED, Optional.of("tokenizer")), measured.get(2),
                    changed(4, "an estimated counter keeps its provenance"));
            assertEquals(new ExperienceSample.SampleUsage(UsageKind.REQUESTS, java.util.OptionalLong.empty(),
                    ExperienceSample.UsageState.UNKNOWN, Optional.of("not-reported")), measured.get(4),
                    changed(4, "an unknown counter has no value but keeps its source"));
            assertEquals(new ExperienceSample.SampleUsage(UsageKind.CACHED_INPUT_TOKENS, java.util.OptionalLong.empty(),
                    ExperienceSample.UsageState.NOT_MEASURED, Optional.empty()), measured.get(1),
                    changed(4, "an absent counter of a measured stage is NOT_MEASURED"));
            assertTrue(stages.get(1).usage().stream().allMatch(u -> u.state()
                    == ExperienceSample.UsageState.DECLARED_NO_BILLABLE_USAGE && u.value().isEmpty()
                    && u.source().isEmpty()), changed(4, "a no-billable stage declares it for every kind"));
            // the evaluator's expectation is the same list the exporter produced
            assertEquals(List.of(measured, stages.get(1).usage()), stages.stream().map(st -> st.usage()).toList());
        }
    }

    @Test
    void assumption10_theTaskSummaryProvenanceAndPolicyAreThoseOfTheExecutionStartedEvent(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            for (ExperienceSample sample : store.samples()) {
                var event = store.memory().loadEvent(store.scope(), sample.execution().eventId(),
                        sample.execution().revision()).orElseThrow().event();
                var started = (ExecutionEvent.ExecutionStarted) ForgeSampleChecks.effective(event);
                assertEquals(started.taskSummary(), sample.taskSummary(), changed(10, "task summary"));
                assertEquals(started.provenance(), sample.provenance(), changed(10, "source provenance"));
                assertEquals(started.policy(), sample.policy(), changed(10, "evaluation policy"));
            }
        }
    }
}
