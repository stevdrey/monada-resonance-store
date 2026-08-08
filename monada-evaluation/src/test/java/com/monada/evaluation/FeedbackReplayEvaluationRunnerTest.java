package com.monada.evaluation;

import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import com.monada.storage.feedback.FeedbackSignal;
import com.monada.storage.feedback.FeedbackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackReplayEvaluationRunnerTest {

    @Test
    void mainFixtureImprovesRecallAndIsDeterministicAcrossFreshStores(
            @TempDir Path firstBase,
            @TempDir Path secondBase) throws IOException {
        var events = FeedbackReplayMain.loadFixture();
        var dataset = ExpandedTechnologyDataset.get();

        var first = new FeedbackReplayEvaluationRunner()
                .run(dataset, events, firstBase.resolve("run"));
        var second = new FeedbackReplayEvaluationRunner()
                .run(dataset, events, secondBase.resolve("run"));

        assertEquals(RankingChange.IMPROVED, first.replayedVsBaseline().aggregate());
        assertTrue(first.replayedPersisted().averageRecallByK().get(3)
                > first.baseline().averageRecallByK().get(3));
        assertTrue(first.replayedPersisted().averageRecallByK().get(5)
                > first.baseline().averageRecallByK().get(5));
        assertEquals(5, first.replayDiagnostics().size());
        assertTrue(first.replayDiagnostics().get(0).matchesEvaluationQuery());
        assertFalse(first.replayDiagnostics().get(4).matchesEvaluationQuery());
        assertEquals(first, second);
        assertEquals(first.render(), second.render());

        Path persistedLog = firstBase.resolve("run")
                .resolve("replayed-persisted")
                .resolve(FeedbackStore.DEFAULT_SEGMENT);
        List<String> persistedLines = Files.readAllLines(persistedLog, StandardCharsets.UTF_8);
        assertEquals(5, persistedLines.size());
        assertTrue(persistedLines.get(4).contains(
                "\"queryKey\":\"unmatched:memory recall improved by user signals\""));
        assertTrue(persistedLines.get(4).contains("\"createdAt\":\"2026-01-01T00:00:04Z\""));

        String rendered = first.render();
        assertTrue(rendered.contains("BASELINE"));
        assertTrue(rendered.contains("SEEDED_SYNTHETIC"));
        assertTrue(rendered.contains("REPLAYED_PERSISTED"));
        assertTrue(rendered.contains("delta +"));
        assertTrue(rendered.contains("unmatched:memory recall improved by user signals"));
        assertTrue(rendered.contains("Matched evaluation queries: (none)"));
    }

    @Test
    void unmatchedQueryKeyIsAnExactNoOp(@TempDir Path basePath) {
        var event = new FeedbackReplayEvent(
                "sql relational transactions database",
                "unmatched:sql relational transactions database",
                "ka_postgresql",
                FeedbackSignal.POSITIVE,
                10.0,
                Instant.EPOCH,
                FeedbackReplayExpectedScope.UNMATCHED_EVALUATION_QUERY);

        var report = new FeedbackReplayEvaluationRunner().run(
                DefaultDatabasesDataset.get(), List.of(event), basePath.resolve("run"));

        assertEquals(report.baseline(), report.replayedPersisted());
        assertEquals(RankingChange.MAINTAINED, report.replayedVsBaseline().aggregate());
        assertFalse(report.replayDiagnostics().getFirst().matchesEvaluationQuery());
    }

    @Test
    void unknownTargetLabelFailsBeforeAnyFeedbackIsAppended(@TempDir Path basePath) throws IOException {
        var valid = matchingEvent("ka_postgresql");
        var invalid = new FeedbackReplayEvent(
                valid.queryText(),
                valid.queryKey(),
                "ka_missing",
                valid.signal(),
                valid.delta(),
                valid.createdAt(),
                valid.expectedScope());
        Path runPath = basePath.resolve("run");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new FeedbackReplayEvaluationRunner().run(
                        DefaultDatabasesDataset.get(), List.of(valid, invalid), runPath));

        assertTrue(exception.getMessage().contains("unknown target label"), exception.getMessage());
        assertFeedbackLogEmpty(runPath);
    }

    @Test
    void incorrectExpectedScopeFailsBeforeAnyFeedbackIsAppended(@TempDir Path basePath) throws IOException {
        var event = new FeedbackReplayEvent(
                "sql relational transactions database",
                "unmatched:key",
                "ka_postgresql",
                FeedbackSignal.POSITIVE,
                0.05,
                Instant.EPOCH,
                FeedbackReplayExpectedScope.MATCHING_EVALUATION_QUERY);
        Path runPath = basePath.resolve("run");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new FeedbackReplayEvaluationRunner().run(
                        DefaultDatabasesDataset.get(), List.of(event), runPath));

        assertTrue(exception.getMessage().contains("expected scope"), exception.getMessage());
        assertFeedbackLogEmpty(runPath);
    }

    @Test
    void rejectsReusingModeDirectories(@TempDir Path basePath) throws IOException {
        Path runPath = basePath.resolve("run");
        Files.createDirectories(runPath.resolve("replayed-persisted"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new FeedbackReplayEvaluationRunner().run(
                        DefaultDatabasesDataset.get(), List.of(matchingEvent("ka_postgresql")), runPath));

        assertTrue(exception.getMessage().contains("already exists"), exception.getMessage());
    }

    private FeedbackReplayEvent matchingEvent(String targetLabel) {
        return new FeedbackReplayEvent(
                "sql relational transactions database",
                "sql relational transactions database",
                targetLabel,
                FeedbackSignal.POSITIVE,
                0.05,
                Instant.EPOCH,
                FeedbackReplayExpectedScope.MATCHING_EVALUATION_QUERY);
    }

    private void assertFeedbackLogEmpty(Path runPath) throws IOException {
        Path log = runPath.resolve("replayed-persisted").resolve(FeedbackStore.DEFAULT_SEGMENT);
        assertTrue(Files.isRegularFile(log));
        assertEquals("", Files.readString(log, StandardCharsets.UTF_8));
    }
}
