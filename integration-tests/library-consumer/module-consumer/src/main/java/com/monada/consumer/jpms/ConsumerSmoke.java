package com.monada.consumer.jpms;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.RecordResult;
import com.monada.api.execution.ExperienceHit;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ProjectionStatus;
import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import java.util.Optional;
import java.util.OptionalDouble;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.KnowledgeAtom;
import com.monada.core.MonadaRecall;
import com.monada.encoder.TextNormalizer;
import com.monada.storage.feedback.FeedbackSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * JPMS (module-path) consumer of the published Monada library artifacts.
 *
 * <p>Runs open, remember, recall, feedback and reopen against a temporary store using
 * only the published {@code com.monada:monada-api} coordinate and its transitive metadata.
 */
public final class ConsumerSmoke {

    private static final String QUERY = "database with graph and document model";

    private ConsumerSmoke() {
    }

    public static void main(String[] args) throws IOException {
        printEvidence();

        Path root = Files.createTempDirectory("monada-consumer-jpms-");
        try {
            runSmoke(root);
            runExecutionSmoke(root.resolve("execution"));
            runForgeScenario(root.resolve("forge"));
        } catch (Throwable failure) {
            // Keep the smoke failure primary; a cleanup error is attached instead of masking it.
            try {
                deleteRecursively(root);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        deleteRecursively(root);
    }

    private static void runSmoke(Path root) {
        String rememberedId;
        double scoreBeforeFeedback;
        {
            MonadaMemory memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
            KnowledgeAtom graph = memory.remember(
                    "OrientDB is a multi-model database that combines graph and document models.");
            memory.remember("Redis is an in-memory data structure store often used as a cache.");
            rememberedId = graph.id();

            MonadaRecall recall = memory.resonate(QUERY).topK(3).threshold(0.0).execute();
            scoreBeforeFeedback = expectTop(recall, rememberedId, "initial recall");

            memory.feedback(QUERY, rememberedId, FeedbackSignal.POSITIVE);
            expectInvalidTopKRejected(memory);
        }

        MonadaMemory reopened = MonadaMemory.open(root);
        MonadaRecall recall = reopened.resonate(QUERY).topK(3).threshold(0.0).execute();
        double scoreAfterReopen = expectTop(recall, rememberedId, "recall after reopen");
        expectFeedbackPersisted(scoreBeforeFeedback, scoreAfterReopen);
        System.out.println("[smoke] open -> remember -> recall -> feedback -> reopen: OK");
    }

    private static void runExecutionSmoke(Path root) {
        ScopeId scope = ScopeId.of("consumer-scope");
        ExecutionId execution = ExecutionId.of("consumer-exec");
        ExecutionEvent started = ExecutionEvent.executionStarted(EventId.of("ev-1"), scope, TaskId.of("task-1"),
                execution, new SourceProvenance("rev-1", "ctx-1", "constraints-1"),
                new EvaluationPolicy("policy", "1", Set.of(QualityDimension.TESTS)), "consumer smoke",
                Instant.parse("2026-01-01T00:00:00Z"));
        try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
            expect(memory.record(started).status() == RecordResult.Status.APPENDED, "execution append");
            expect(memory.record(started).status() == RecordResult.Status.IDEMPOTENT, "execution retry");
            expect(memory.history(scope).entries().size() == 1, "execution history before close");
        }
        try (ExecutionMemory reopened = ExecutionMemory.open(root, scope)) {
            expect(reopened.loadExecution(scope, execution).isPresent(), "execution load after reopen");
            expect(reopened.loadAttempt(scope, execution, AttemptId.of("none")).isEmpty(), "unknown attempt");
        }
        System.out.println("[smoke] execution record -> idempotent retry -> history -> close -> reopen -> load: OK");
    }

    /**
     * A small synthetic Forge-style scenario against the published artifacts only: two opaque projects, record,
     * close, reopen, reconcile the projection, scoped recall, exact load and export. It mirrors the hard gates of
     * the in-repository evaluation (no foreign-scope leakage, no success label without validated evidence).
     */
    private static void runForgeScenario(Path root) {
        ScopeId alpha = ScopeId.of("project-alpha");
        ScopeId beta = ScopeId.of("project-beta");
        String summary = "Fix null pointer in invoice totals calculation";
        try (ExecutionMemory memory = ExecutionMemory.open(root, alpha)) {
            recordExecution(memory, alpha, "ok", summary, true, true);
            recordExecution(memory, alpha, "noev", summary, false, true);
        }
        try (ExecutionMemory memory = ExecutionMemory.open(root, beta)) {
            recordExecution(memory, beta, "other", summary, true, false);
        }

        try (ExecutionMemory reopened = ExecutionMemory.open(root, alpha)) { // reopen and reconcile
            ProjectionStatus status = reopened.rebuildProjection(alpha);
            expectForge(status.isCurrent(), "projection current after rebuild");
            List<ExperienceHit> hits = reopened.recall(alpha, summary, 10).hits();
            expectForge(!hits.isEmpty(), "recall returns experiences");
            for (ExperienceHit hit : hits) {
                expectForge(hit.ref().scope().equals(alpha), "no foreign-scope hit");
                expectForge(reopened.loadEvent(alpha, hit.ref().eventId(), hit.ref().revision())
                        .filter(hit.event()::equals).isPresent(), "hit loads the exact ledger event");
            }
            expectForge(rejected(() -> reopened.recall(beta, summary, 10)), "foreign scope recall rejected");

            List<ExperienceSample> samples = reopened.exportSamples(ExportRequest.firstPage(alpha, 10)).samples();
            expectForge(samples.size() == 2, "export holds only the scope's executions");
            for (ExperienceSample sample : samples) {
                expectForge(sample.execution().scope().equals(alpha), "export sample scope");
                boolean validated = sample.executionId().value().equals("exec-ok");
                expectForge(validated == (sample.label() == ExperienceSample.Label.SUCCESS),
                        "SUCCESS only with validated evidence: " + sample.executionId().value() + " " + sample.label());
            }
        }
        System.out.println("[smoke] forge scenario: record -> reopen -> reconcile -> scoped recall -> export: OK");
    }

    private static void recordExecution(ExecutionMemory memory, ScopeId scope, String name, String summary,
                                        boolean withEvidence, boolean validatedOutcome) {
        TaskId task = TaskId.of("task-" + name);
        ExecutionId exec = ExecutionId.of("exec-" + name);
        AttemptId attempt = AttemptId.of("a1");
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        memory.record(ExecutionEvent.executionStarted(EventId.of(name + ".start"), scope, task, exec,
                new SourceProvenance("rev-1", "ctx-1", "constraints-1"),
                new EvaluationPolicy("forge-gates", "1", Set.of(QualityDimension.TESTS)), summary, at));
        memory.record(new AttemptStarted(EventEnvelope.original(EventId.of(name + ".att"), scope, task, exec, attempt,
                at, at), 1, Optional.empty(), AttemptReason.INITIAL));
        memory.record(StageRecorded.measured(EventEnvelope.original(EventId.of(name + ".stage"), scope, task, exec,
                attempt, at.plusSeconds(10), at.plusSeconds(10)), "implement",
                new RouteDescriptor("worker", Optional.empty(), Optional.empty(), Optional.empty(),
                        com.monada.core.execution.BillingMode.UNKNOWN), at, at.plusSeconds(10),
                List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, 1000, "smoke")), List.of()));
        if (withEvidence) {
            memory.record(new EvidenceRecorded(EventEnvelope.original(EventId.of(name + ".ev"), scope, task, exec,
                    attempt, at.plusSeconds(10), at.plusSeconds(10)), List.of(new QualityObservation(
                    QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty(), "smoke",
                    "1", "forge-gates", "1", ObservationSource.TOOL, List.of(ArtifactRef.of("report", "r-" + name)),
                    Optional.empty())), List.of()));
        }
        memory.record(new AttemptFinished(EventEnvelope.original(EventId.of(name + ".fin"), scope, task, exec,
                attempt, at.plusSeconds(10), at.plusSeconds(10)), AttemptResult.COMPLETED,
                Optional.of("Guarded the invoice line list before summing totals"),
                Optional.of("Cover optional lines when totals are computed")));
        memory.record(ExecutionEvent.outcomeRecorded(EventId.of(name + ".out"), scope, task, exec,
                Outcome.accepted(validatedOutcome ? OutcomeOrigin.VALIDATED : OutcomeOrigin.IMPORTED_CLAIM, attempt),
                at.plusSeconds(10)));
    }

    private static boolean rejected(Runnable call) {
        try {
            call.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void expectForge(boolean condition, String label) {
        if (!condition) {
            throw new IllegalStateException("forge scenario failed: " + label);
        }
    }

    private static void expect(boolean condition, String label) {
        if (!condition) {
            throw new IllegalStateException("execution smoke failed: " + label);
        }
    }

    private static void printEvidence() {
        System.out.println("[evidence] java.version=" + System.getProperty("java.version"));
        System.out.println("[evidence] jdk.module.path=" + System.getProperty("jdk.module.path", ""));
        System.out.println("[evidence] java.class.path=" + System.getProperty("java.class.path", ""));
        List<Class<?>> exposed = List.of(
                MonadaMemory.class, KnowledgeAtom.class, TextNormalizer.class, FeedbackSignal.class);
        for (Class<?> type : exposed) {
            Module module = type.getModule();
            System.out.println("[evidence] " + type.getName() + " -> module "
                    + (module.isNamed() ? module.getName() : "<unnamed>"));
        }
        Module self = ConsumerSmoke.class.getModule();
        System.out.println("[evidence] consumer -> module " + (self.isNamed() ? self.getName() : "<unnamed>"));
        expectModule(MonadaMemory.class, "com.monada.api");
        expectModule(KnowledgeAtom.class, "com.monada.core");
        expectModule(TextNormalizer.class, "com.monada.encoder");
        expectModule(FeedbackSignal.class, "com.monada.storage");
        expectModule(ConsumerSmoke.class, "com.monada.consumer.jpms");
        for (String name : List.of("com.monada.index", "com.monada.learning")) {
            if (ModuleLayer.boot().findModule(name).isEmpty()) {
                throw new IllegalStateException("Runtime module not resolved: " + name);
            }
            System.out.println("[evidence] boot layer resolved " + name);
        }
    }

    private static double expectTop(MonadaRecall recall, String expectedId, String label) {
        if (recall.results().isEmpty()) {
            throw new IllegalStateException(label + ": no results");
        }
        String topId = recall.results().getFirst().atom().id();
        if (!expectedId.equals(topId)) {
            throw new IllegalStateException(label + ": expected top " + expectedId + " but was " + topId);
        }
        System.out.println("[smoke] " + label + ": top=" + topId
                + " score=" + recall.results().getFirst().score()
                + " results=" + recall.results().size());
        return recall.results().getFirst().score();
    }

    private static void expectFeedbackPersisted(double scoreBeforeFeedback, double scoreAfterReopen) {
        // Feedback-aware ranking is the default, so the persisted POSITIVE event must
        // raise the reopened score above the score observed before feedback was recorded.
        if (!(scoreAfterReopen > scoreBeforeFeedback)) {
            throw new IllegalStateException("feedback not persisted across reopen: score before feedback "
                    + scoreBeforeFeedback + ", after reopen " + scoreAfterReopen);
        }
        System.out.println("[smoke] feedback persisted across reopen: score "
                + scoreBeforeFeedback + " -> " + scoreAfterReopen);
    }

    private static void expectInvalidTopKRejected(MonadaMemory memory) {
        try {
            memory.resonate(QUERY).topK(0);
        } catch (IllegalArgumentException expected) {
            System.out.println("[smoke] invalid topK rejected: " + expected.getMessage());
            return;
        }
        throw new IllegalStateException("topK(0) was not rejected");
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void expectModule(Class<?> type, String expected) {
        Module module = type.getModule();
        if (!module.isNamed() || !expected.equals(module.getName())) {
            throw new IllegalStateException(type.getName() + " expected in module " + expected
                    + " but was " + (module.isNamed() ? module.getName() : "<unnamed>"));
        }
    }
}
