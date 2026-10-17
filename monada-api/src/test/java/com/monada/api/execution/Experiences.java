package com.monada.api.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.storage.execution.ExperienceRefCodec;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Scope-parametric experience fixtures for projection and recall tests. Event IDs are derived from the
 * execution ID ({@code <exec>-start}, {@code <exec>-<attempt>-start}, {@code <exec>-<attempt>-fin}); tasks
 * are {@code task-<exec>}; timestamps are fixed.
 */
final class Experiences {
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
    static final SourceProvenance PROVENANCE = new SourceProvenance("rev-1", "ctx-1", "constraints-1");
    static final EvaluationPolicy POLICY = new EvaluationPolicy("policy", "1", Set.of(QualityDimension.TESTS));

    private Experiences() {
    }

    static ExecutionEvent start(ScopeId scope, String exec, String taskSummary) {
        return ExecutionEvent.executionStarted(EventId.of(exec + "-start"), scope, TaskId.of("task-" + exec),
                ExecutionId.of(exec), PROVENANCE, POLICY, taskSummary, T0);
    }

    static ExecutionEvent attempt(ScopeId scope, String exec, String attempt, int ordinal, String previous) {
        EventEnvelope env = EventEnvelope.original(EventId.of(exec + "-" + attempt + "-start"), scope,
                TaskId.of("task-" + exec), ExecutionId.of(exec), AttemptId.of(attempt), T0, T0);
        return new AttemptStarted(env, ordinal, Optional.ofNullable(previous).map(AttemptId::of),
                ordinal == 1 ? AttemptReason.INITIAL : AttemptReason.RETRY);
    }

    static ExecutionEvent finish(ScopeId scope, String exec, String attempt, String solution, String lesson) {
        EventEnvelope env = EventEnvelope.original(EventId.of(exec + "-" + attempt + "-fin"), scope,
                TaskId.of("task-" + exec), ExecutionId.of(exec), AttemptId.of(attempt), T1, T1);
        return new AttemptFinished(env, AttemptResult.COMPLETED, Optional.ofNullable(solution),
                Optional.ofNullable(lesson));
    }

    /** Correction (revision {@code revision}) of the finish {@code target} with new summaries. */
    static ExecutionEvent correctFinish(ScopeId scope, String exec, String attempt, String id, String target,
                                        int revision, String solution, String lesson) {
        EventEnvelope outer = new EventEnvelope(EventId.of(id), scope, TaskId.of("task-" + exec), ExecutionId.of(exec),
                Optional.of(AttemptId.of(attempt)), revision, Optional.of(EventId.of(target)), T1, T1);
        EventEnvelope inner = EventEnvelope.original(EventId.of(id), scope, TaskId.of("task-" + exec),
                ExecutionId.of(exec), AttemptId.of(attempt), T1, T1);
        return new Correction(outer, new AttemptFinished(inner, AttemptResult.COMPLETED,
                Optional.ofNullable(solution), Optional.ofNullable(lesson)), "summary corrected");
    }

    /** Correction of the execution start with a new task summary. */
    static ExecutionEvent correctStart(ScopeId scope, String exec, String id, int revision, String taskSummary) {
        EventEnvelope outer = new EventEnvelope(EventId.of(id), scope, TaskId.of("task-" + exec), ExecutionId.of(exec),
                Optional.empty(), revision, Optional.of(EventId.of(exec + "-start")), T0, T0);
        EventEnvelope inner = EventEnvelope.original(EventId.of(id), scope, TaskId.of("task-" + exec),
                ExecutionId.of(exec), T0, T0);
        return new Correction(outer, new ExecutionStarted(inner, taskSummary, PROVENANCE, POLICY),
                "task summary corrected");
    }

    /** Records a one-attempt execution and asserts every event was appended. */
    static void experience(ExecutionMemory m, ScopeId scope, String exec, String task, String solution,
                           String lesson) {
        appended(m, start(scope, exec, task));
        appended(m, attempt(scope, exec, "a1", 1, null));
        appended(m, finish(scope, exec, "a1", solution, lesson));
    }

    static void appended(ExecutionMemory m, ExecutionEvent event) {
        assertEquals(RecordResult.Status.APPENDED, m.record(event).status(), event.eventId().value());
    }

    /** Compact, comparable rendering of a recall: {@code canonicalRef@similarity}. */
    static List<String> render(ExperienceRecall recall) {
        return recall.hits().stream()
                .map(h -> ExperienceRefCodec.canonical(h.ref()) + "@" + h.similarity()).toList();
    }

    /** Current projection generation directory, resolved through the pointer. */
    static Path projectionDir(Path root, ScopeId scope) {
        try {
            return ProjectionLayout.of(root, scope).currentDir()
                    .orElseThrow(() -> new IllegalStateException("scope has no published projection"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the pointer and every generation: the state of a scope recorded before projections existed. */
    static void removeProjection(Path root, ScopeId scope) {
        try {
            ProjectionLayout layout = ProjectionLayout.of(root, scope);
            Files.deleteIfExists(layout.pointer());
            for (Path generation : layout.generationDirs()) {
                try (var walk = Files.walk(generation)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path scopeDir(Path root, ScopeId scope) {
        try {
            return ProjectionLayout.of(root, scope).scopeDir();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every entry under {@code dir}, keyed by relative path: directories as {@code "dir"}, the writer lock as
     * {@code "lock"} (its presence matters, and a held lock may not be readable on every platform), files as
     * their SHA-256. Used to assert that nothing was created, deleted or changed.
     */
    static Map<String, String> treeDigests(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> entries = Files.walk(dir)) {
            for (Path p : entries.toList()) {
                String value = Files.isDirectory(p) ? "dir"
                        : p.getFileName().toString().equals("write.lock") ? "lock"
                        : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
                out.put(dir.relativize(p).toString(), value);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    static Path checkpoint(Path root, ScopeId scope) {
        return projectionDir(root, scope).resolve("projection-checkpoint.log");
    }

    static byte[] checkpointBytes(Path root, ScopeId scope) {
        try {
            return Files.readAllBytes(checkpoint(root, scope));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
