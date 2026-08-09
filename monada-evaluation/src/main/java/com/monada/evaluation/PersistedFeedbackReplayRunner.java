package com.monada.evaluation;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.storage.feedback.FeedbackEvent;
import com.monada.storage.feedback.FileFeedbackStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs one isolated baseline-versus-persisted-feedback replay arm.
 *
 * <p>This package-private runner keeps the real {@link FileFeedbackStore} replay path shared
 * between the original exact-query replay report and strategy-comparison experiments. It also
 * captures full-corpus rankings while the baseline and replayed stores are available, without
 * changing the normal evaluation report's top-K behavior.
 */
final class PersistedFeedbackReplayRunner {

    private final EvaluationRunner evaluationRunner;
    private final EvaluationComparator comparator;

    PersistedFeedbackReplayRunner(
            EvaluationRunner evaluationRunner,
            EvaluationComparator comparator) {
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
        this.comparator = Objects.requireNonNull(comparator, "comparator");
    }

    ReplayArm run(
            EvaluationDataset dataset,
            List<FeedbackReplayEvent> replayEvents,
            Path replayPath,
            EvaluationProfile profile) {
        Objects.requireNonNull(dataset, "dataset");
        replayEvents = List.copyOf(Objects.requireNonNull(replayEvents, "replayEvents"));
        Objects.requireNonNull(replayPath, "replayPath");
        Objects.requireNonNull(profile, "profile");
        if (replayEvents.isEmpty()) {
            throw new IllegalArgumentException("replayEvents must not be empty");
        }
        requireFreshDirectory(replayPath);

        MonadaMemoryOptions options = profile.toMemoryOptions();
        var replayMemory = MonadaMemory.open(replayPath, options);
        var seeding = evaluationRunner.seedAtoms(dataset, replayMemory);
        var baseline = evaluationRunner.evaluate(dataset, replayMemory, seeding.idToLabel(), options);
        var baselineFullCorpus = captureFullCorpusRankings(dataset, replayMemory, seeding.idToLabel());

        Map<String, List<String>> evaluationQueriesByKey = evaluationQueriesByKey(baseline);
        var resolvedEvents = resolveAndValidate(
                replayEvents, seeding.labelToAtomId(), evaluationQueriesByKey);
        appendResolvedEvents(replayPath, resolvedEvents);

        var replayed = evaluationRunner.evaluate(dataset, replayMemory, seeding.idToLabel(), options);
        var replayedFullCorpus = captureFullCorpusRankings(dataset, replayMemory, seeding.idToLabel());
        var replayedVsBaseline = comparator.compare(baseline, replayed);

        return new ReplayArm(
                baseline,
                replayed,
                replayedVsBaseline,
                resolvedEvents.stream().map(ResolvedFeedback::diagnostic).toList(),
                baselineFullCorpus,
                replayedFullCorpus);
    }

    private void requireFreshDirectory(Path path) {
        if (Files.exists(path)) {
            throw new IllegalArgumentException("evaluation mode directory already exists: " + path);
        }
    }

    private Map<String, List<String>> evaluationQueriesByKey(EvaluationReport baseline) {
        var mutable = new LinkedHashMap<String, List<String>>();
        for (QueryEvaluation query : baseline.queryResults()) {
            QueryKeyDiagnostic diagnostic = Objects.requireNonNull(
                    query.queryKeyDiagnostic(),
                    "baseline query-key diagnostic for " + query.queryText());
            mutable.computeIfAbsent(diagnostic.queryKey(), ignored -> new ArrayList<>())
                    .add(query.queryText());
        }

        var result = new LinkedHashMap<String, List<String>>();
        mutable.forEach((key, queries) -> result.put(key, List.copyOf(queries)));
        return result;
    }

    private List<ResolvedFeedback> resolveAndValidate(
            List<FeedbackReplayEvent> replayEvents,
            Map<String, String> labelToAtomId,
            Map<String, List<String>> evaluationQueriesByKey) {
        var resolved = new ArrayList<ResolvedFeedback>(replayEvents.size());
        for (int index = 0; index < replayEvents.size(); index++) {
            FeedbackReplayEvent replayEvent = replayEvents.get(index);
            String atomId = labelToAtomId.get(replayEvent.targetLabel());
            if (atomId == null) {
                throw new IllegalArgumentException(
                        "feedback replay event " + (index + 1)
                                + " references unknown target label: " + replayEvent.targetLabel());
            }

            List<String> matchedQueries = evaluationQueriesByKey.getOrDefault(
                    replayEvent.queryKey(), List.of());
            validateExpectedScope(index + 1, replayEvent, matchedQueries);

            var persistedEvent = new FeedbackEvent(
                    replayEvent.queryText(),
                    replayEvent.queryKey(),
                    atomId,
                    replayEvent.signal(),
                    replayEvent.delta(),
                    replayEvent.createdAt());
            var diagnostic = new FeedbackReplayEventDiagnostic(
                    index + 1, replayEvent, matchedQueries);
            resolved.add(new ResolvedFeedback(persistedEvent, diagnostic));
        }
        return List.copyOf(resolved);
    }

    private void validateExpectedScope(
            int ordinal,
            FeedbackReplayEvent event,
            List<String> matchedQueries) {
        boolean matches = !matchedQueries.isEmpty();
        boolean expectedMatch = event.expectedScope()
                == FeedbackReplayExpectedScope.MATCHING_EVALUATION_QUERY;
        if (matches != expectedMatch) {
            throw new IllegalArgumentException(
                    "feedback replay event " + ordinal + " expected scope "
                            + event.expectedScope() + " but query key '" + event.queryKey()
                            + "' matched evaluation queries " + matchedQueries);
        }
    }

    private void appendResolvedEvents(Path replayPath, List<ResolvedFeedback> resolvedEvents) {
        try {
            var feedbackStore = new FileFeedbackStore(replayPath);
            for (ResolvedFeedback resolved : resolvedEvents) {
                feedbackStore.append(resolved.persistedEvent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, List<FullCorpusRanking>> captureFullCorpusRankings(
            EvaluationDataset dataset,
            MonadaMemory memory,
            Map<String, String> idToLabel) {
        var rankingsByQuery = new LinkedHashMap<String, List<FullCorpusRanking>>();
        for (EvaluationQuery query : dataset.queries()) {
            var recall = memory.resonate(query.text())
                    .topK(dataset.atoms().size())
                    .threshold(Double.NEGATIVE_INFINITY)
                    .execute();
            var ranking = new ArrayList<FullCorpusRanking>(recall.results().size());
            for (int index = 0; index < recall.results().size(); index++) {
                var result = recall.results().get(index);
                String label = idToLabel.getOrDefault(result.atom().id(), result.atom().id());
                ranking.add(new FullCorpusRanking(label, index + 1, result.score()));
            }
            rankingsByQuery.put(query.text(), List.copyOf(ranking));
        }
        return Map.copyOf(rankingsByQuery);
    }

    record ReplayArm(
            EvaluationReport baseline,
            EvaluationReport replayedPersisted,
            EvaluationComparison replayedVsBaseline,
            List<FeedbackReplayEventDiagnostic> replayDiagnostics,
            Map<String, List<FullCorpusRanking>> baselineFullCorpusByQuery,
            Map<String, List<FullCorpusRanking>> replayedFullCorpusByQuery) {
        ReplayArm {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(replayedPersisted, "replayedPersisted");
            Objects.requireNonNull(replayedVsBaseline, "replayedVsBaseline");
            replayDiagnostics = List.copyOf(Objects.requireNonNull(replayDiagnostics, "replayDiagnostics"));
            baselineFullCorpusByQuery = Map.copyOf(
                    Objects.requireNonNull(baselineFullCorpusByQuery, "baselineFullCorpusByQuery"));
            replayedFullCorpusByQuery = Map.copyOf(
                    Objects.requireNonNull(replayedFullCorpusByQuery, "replayedFullCorpusByQuery"));
        }
    }

    record FullCorpusRanking(String label, int rank, double score) {
        FullCorpusRanking {
            Objects.requireNonNull(label, "label");
            if (label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
            if (rank <= 0) {
                throw new IllegalArgumentException("rank must be positive: " + rank);
            }
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("score must be finite: " + score);
            }
        }
    }

    private record ResolvedFeedback(
            FeedbackEvent persistedEvent,
            FeedbackReplayEventDiagnostic diagnostic) {
    }
}
