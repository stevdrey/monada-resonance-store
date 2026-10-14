package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportCursor;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;

/**
 * Shared test helpers of the Forge evaluation tests: an open store of the alpha project, builders that copy an
 * exported sample (or one of its attempts) with a few fields replaced, and small forging helpers. Having one copy
 * builder means a new field of {@link ExperienceSample} is added in one place, not in every test helper.
 */
final class ForgeTestSupport {
    private ForgeTestSupport() {
    }

    /**
     * One project of the fixture, recorded, reconciled and exported once. Closing it closes the memory, so a test
     * cannot leak the handle: {@code try (ProjectStore store = ProjectStore.open(root, fixture)) { ... }}.
     */
    record ProjectStore(ExecutionMemory memory, ScopeId scope, List<ExperienceSample> samples) implements AutoCloseable {
        /** The alpha project. */
        static ProjectStore open(Path root, ForgeFixture fixture) {
            return open(root, fixture, "alpha");
        }

        static ProjectStore open(Path root, ForgeFixture fixture, String scopeKey) {
            ScopeId scope = ForgeFixtureRecorder.scope(fixture, scopeKey);
            ExecutionMemory memory = ExecutionMemory.open(root, scope);
            try {
                ForgeFixtureRecorder.recordScope(memory, fixture, scopeKey);
                memory.rebuildProjection(scope);
                return new ProjectStore(memory, scope, memory.exportSamples(ExportRequest.firstPage(scope, 50)).samples());
            } catch (RuntimeException e) {
                memory.close();
                throw e;
            }
        }

        @Override
        public void close() {
            memory.close();
        }
    }

    /** A real snapshot of the whole fixture (both projects recorded and reconciled) under {@code root}. */
    static ForgeExperienceEvaluator.Snapshot openSnapshot(Path root, ForgeFixture fixture) {
        for (String scopeKey : fixture.scopeIds().keySet()) {
            try (ProjectStore store = ProjectStore.open(root, fixture, scopeKey)) {
                store.memory(); // recorded and reconciled by open()
            }
        }
        return ForgeExperienceEvaluator.snapshot(fixture, root, new java.util.LinkedHashMap<>());
    }

    // --- copying a sample ------------------------------------------------------------------------------------

    /** A copy of an {@link ExperienceSample} with chosen fields replaced; {@link #build()} is the only constructor call. */
    static final class SampleCopy {
        private ExperienceRef execution;
        private String taskSummary;
        private SourceProvenance provenance;
        private EvaluationPolicy policy;
        private List<QualityDimension> requiredGates;
        private Optional<ExperienceSample.OutcomeFact> outcome;
        private DerivedAcceptance.Status acceptance;
        private ExperienceSample.Label label;
        private OptionalInt successOrdinal;
        private ExperienceSample.Group group;
        private ExperienceSample.Eligibility eligibility;
        private List<ExperienceSample.Gate> gates;
        private List<ExperienceSample.SampleAttempt> attempts;
        private List<ExperienceSample.Revision> revisions;
        private long firstSequence;
        private long lastSequence;
        private Optional<ExperienceSample.Diagnostics> diagnostics;

        private SampleCopy(ExperienceSample x) {
            execution = x.execution();
            taskSummary = x.taskSummary();
            provenance = x.provenance();
            policy = x.policy();
            requiredGates = x.requiredGates();
            outcome = x.outcome();
            acceptance = x.acceptance();
            label = x.label();
            successOrdinal = x.successOrdinal();
            group = x.group();
            eligibility = x.eligibility();
            gates = x.gates();
            attempts = x.attempts();
            revisions = x.revisions();
            firstSequence = x.firstSequence();
            lastSequence = x.lastSequence();
            diagnostics = x.diagnostics();
        }

        static SampleCopy of(ExperienceSample x) {
            return new SampleCopy(x);
        }

        SampleCopy execution(ExperienceRef v) {
            execution = v;
            return this;
        }

        SampleCopy taskSummary(String v) {
            taskSummary = v;
            return this;
        }

        SampleCopy provenance(SourceProvenance v) {
            provenance = v;
            return this;
        }

        SampleCopy policy(EvaluationPolicy v) {
            policy = v;
            return this;
        }

        SampleCopy requiredGates(List<QualityDimension> v) {
            requiredGates = v;
            return this;
        }

        SampleCopy outcome(Optional<ExperienceSample.OutcomeFact> v) {
            outcome = v;
            return this;
        }

        SampleCopy acceptance(DerivedAcceptance.Status v) {
            acceptance = v;
            return this;
        }

        SampleCopy label(ExperienceSample.Label v) {
            label = v;
            return this;
        }

        SampleCopy successOrdinal(OptionalInt v) {
            successOrdinal = v;
            return this;
        }

        SampleCopy group(ExperienceSample.Group v) {
            group = v;
            return this;
        }

        SampleCopy eligibility(ExperienceSample.Eligibility v) {
            eligibility = v;
            return this;
        }

        SampleCopy gates(List<ExperienceSample.Gate> v) {
            gates = v;
            return this;
        }

        SampleCopy attempts(List<ExperienceSample.SampleAttempt> v) {
            attempts = v;
            return this;
        }

        SampleCopy revisions(List<ExperienceSample.Revision> v) {
            revisions = v;
            return this;
        }

        SampleCopy bounds(long first, long last) {
            firstSequence = first;
            lastSequence = last;
            return this;
        }

        ExperienceSample build() {
            return new ExperienceSample(execution, taskSummary, provenance, policy, requiredGates, outcome,
                    acceptance, label, successOrdinal, group, eligibility, gates, attempts, revisions, firstSequence,
                    lastSequence, diagnostics);
        }
    }

    /** A copy of one exported attempt with chosen fields replaced. */
    static final class AttemptCopy {
        private final ExperienceSample.SampleAttempt a;
        private ExperienceRef start;
        private int ordinal;
        private Optional<AttemptId> previous;
        private boolean accepted;
        private Optional<ExperienceRef> finish;
        private Optional<String> solution;
        private Optional<String> lesson;
        private List<ExperienceSample.SampleStage> stages;
        private List<ExperienceSample.SampleEvidence> evidence;

        private AttemptCopy(ExperienceSample.SampleAttempt a) {
            this.a = a;
            start = a.start();
            ordinal = a.ordinal();
            previous = a.previousAttempt();
            accepted = a.accepted();
            finish = a.finish();
            solution = a.solutionSummary();
            lesson = a.lessonSummary();
            stages = a.stages();
            evidence = a.evidence();
        }

        static AttemptCopy of(ExperienceSample.SampleAttempt a) {
            return new AttemptCopy(a);
        }

        AttemptCopy start(ExperienceRef v) {
            start = v;
            return this;
        }

        AttemptCopy ordinal(int v) {
            ordinal = v;
            return this;
        }

        AttemptCopy previous(Optional<AttemptId> v) {
            previous = v;
            return this;
        }

        AttemptCopy accepted(boolean v) {
            accepted = v;
            return this;
        }

        AttemptCopy finish(ExperienceRef v) {
            finish = Optional.of(v);
            return this;
        }

        AttemptCopy summaries(Optional<String> solutionSummary, Optional<String> lessonSummary) {
            solution = solutionSummary;
            lesson = lessonSummary;
            return this;
        }

        AttemptCopy stages(List<ExperienceSample.SampleStage> v) {
            stages = v;
            return this;
        }

        AttemptCopy evidence(List<ExperienceSample.SampleEvidence> v) {
            evidence = v;
            return this;
        }

        ExperienceSample.SampleAttempt build() {
            return new ExperienceSample.SampleAttempt(a.id(), start, ordinal, a.reason(), previous, accepted,
                    a.result(), finish, solution, lesson, stages, evidence);
        }
    }

    // --- sample-level helpers ----------------------------------------------------------------------------------

    static ExperienceSample sampleOf(List<ExperienceSample> samples, String execution) {
        return samples.stream().filter(x -> x.executionId().value().equals(execution)).findFirst().orElseThrow();
    }

    static ExperienceSample withOutcome(ExperienceSample x, Optional<ExperienceSample.OutcomeFact> outcome) {
        return SampleCopy.of(x).outcome(outcome).build();
    }

    static ExperienceSample withRevisions(ExperienceSample x, List<ExperienceSample.Revision> revisions) {
        return SampleCopy.of(x).revisions(revisions).build();
    }

    static ExperienceSample withAttempts(ExperienceSample x, List<ExperienceSample.SampleAttempt> attempts) {
        return SampleCopy.of(x).attempts(attempts).build();
    }

    static ExperienceSample withGates(ExperienceSample x, List<ExperienceSample.Gate> gates) {
        return SampleCopy.of(x).gates(gates).build();
    }

    static ExperienceSample withGroup(ExperienceSample x, ExperienceSample.Group group, OptionalInt ordinal) {
        return SampleCopy.of(x).group(group).successOrdinal(ordinal).build();
    }

    static ExperienceSample withEligibility(ExperienceSample x, ExperienceSample.Eligibility eligibility) {
        return SampleCopy.of(x).eligibility(eligibility).build();
    }

    static ExperienceSample withBounds(ExperienceSample x, long first, long last) {
        return SampleCopy.of(x).bounds(first, last).build();
    }

    /** The sample with attempt {@code index} replaced by {@code change} applied to it. */
    static ExperienceSample withAttempt(ExperienceSample x, int index, UnaryOperator<ExperienceSample.SampleAttempt> change) {
        List<ExperienceSample.SampleAttempt> attempts = new ArrayList<>(x.attempts());
        attempts.set(index, change.apply(attempts.get(index)));
        return withAttempts(x, attempts);
    }

    static ExperienceSample.SampleAttempt withStages(ExperienceSample.SampleAttempt a,
                                                     List<ExperienceSample.SampleStage> stages) {
        return AttemptCopy.of(a).stages(stages).build();
    }

    static ExperienceSample.SampleAttempt withStart(ExperienceSample.SampleAttempt a, ExperienceRef start) {
        return AttemptCopy.of(a).start(start).build();
    }

    static ExperienceSample.SampleAttempt withFinish(ExperienceSample.SampleAttempt a, ExperienceRef finish) {
        return AttemptCopy.of(a).finish(finish).build();
    }

    static ExperienceSample.SampleAttempt withEvidenceRef(ExperienceSample.SampleAttempt a, ExperienceRef ref) {
        ExperienceSample.SampleEvidence e = a.evidence().get(0);
        return AttemptCopy.of(a).evidence(List.of(new ExperienceSample.SampleEvidence(ref, e.sequence(),
                e.observations(), e.artifacts()))).build();
    }

    static ExperienceSample.SampleAttempt copyAttempt(ExperienceSample.SampleAttempt a, boolean accepted,
            Optional<AttemptId> previous, Optional<String> solution, Optional<String> lesson,
            List<ExperienceSample.SampleEvidence> evidence) {
        return AttemptCopy.of(a).accepted(accepted).previous(previous).summaries(solution, lesson)
                .evidence(evidence).build();
    }

    static ExperienceSample withStageRef(ExperienceSample x, int attempt, int stage, ExperienceRef ref) {
        ExperienceSample.SampleAttempt a = x.attempts().get(attempt);
        ExperienceSample.SampleStage st = a.stages().get(stage);
        ExperienceSample.SampleStage forged = new ExperienceSample.SampleStage(ref, st.sequence(), st.stage(),
                st.route(), st.startedAt(), st.endedAt(), st.usageDeclaration(), st.justification(), st.usage(),
                st.artifacts());
        return withAttempt(x, attempt, at -> withStages(at, List.of(forged)));
    }

    static ExperienceSample withStageUsage(ExperienceSample x, List<ExperienceSample.SampleUsage> usage) {
        return withStageUsage(x, 1, usage);
    }

    /** The sample with the usage entries of the first stage of attempt {@code attempt} replaced. */
    static ExperienceSample withStageUsage(ExperienceSample x, int attempt, List<ExperienceSample.SampleUsage> usage) {
        return withAttempt(x, attempt, a -> {
            ExperienceSample.SampleStage st = a.stages().get(0);
            return withStages(a, List.of(new ExperienceSample.SampleStage(st.ref(), st.sequence(), st.stage(),
                    st.route(), st.startedAt(), st.endedAt(), st.usageDeclaration(), st.justification(), usage,
                    st.artifacts())));
        });
    }

    static ExperienceSample withGateRef(ExperienceSample x, QualityDimension dimension, ExperienceRef ref) {
        List<ExperienceSample.Gate> gates = new ArrayList<>();
        for (ExperienceSample.Gate g : x.gates()) {
            gates.add(g.dimension() != dimension ? g : new ExperienceSample.Gate(g.dimension(), g.required(),
                    g.state(), g.observation(), Optional.of(ref), g.sequence()));
        }
        return withGates(x, gates);
    }

    // --- references and observations -----------------------------------------------------------------------------

    static ExperienceRef ref(ExperienceRef like, String eventId, int revision) {
        return new ExperienceRef(like.scope(), like.task(), like.execution(), like.attempt(), EventId.of(eventId),
                revision);
    }

    static ExperienceRef withCoordinates(ExperienceRef r, ScopeId scope, TaskId task, ExecutionId execution,
                                         Optional<AttemptId> attempt) {
        return new ExperienceRef(scope, task, execution, attempt, r.eventId(), r.revision());
    }

    static QualityObservation withState(QualityObservation o, ObservationState state) {
        return new QualityObservation(o.dimension(), state, o.measuredValue(), o.unit(), o.evaluatorId(),
                o.evaluatorVersion(), o.policyId(), o.policyVersion(), o.source(), o.evidence(),
                state == ObservationState.NOT_APPLICABLE ? Optional.of("n/a") : o.justification());
    }

    static ExperienceSamplePage page(ExperienceSamplePage p, String schema, ExportView view,
                                     List<QualityDimension> gates, ExperienceSamplePage.LedgerCheckpoint ledger,
                                     ExperienceSamplePage.ProjectionStamp projection, boolean hasMore,
                                     Optional<ExportCursor> next) {
        return new ExperienceSamplePage(schema, p.scope(), view, gates, ledger, projection, p.samples(), hasMore, next);
    }

    /** Asserts that the reference checks report {@code expectedText} for the forged sample. */
    static void expectViolation(ExecutionMemory memory, ScopeId scope, ExperienceSample forged, String expectedText) {
        List<String> violations = ForgeExperienceEvaluator.referenceViolations(memory, scope, forged);
        assertTrue(violations.stream().anyMatch(v -> v.contains(expectedText)),
                "expected '" + expectedText + "' in " + violations);
    }

    /** The gate with that id of a report. */
    static ForgeGate gate(ForgeEvaluationReport report, String id) {
        return report.gates().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }
}
