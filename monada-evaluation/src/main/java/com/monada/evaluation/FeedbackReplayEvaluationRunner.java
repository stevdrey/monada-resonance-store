package com.monada.evaluation;

import com.monada.api.MonadaMemory;
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
 * Compares empty, deterministic synthetic, and persisted replay feedback modes.
 *
 * <p>All three modes use the exact-query, lexical-enriched feedback profile. The
 * replay baseline and candidate share one isolated store: the baseline is measured,
 * the complete validated fixture is appended to its real JSONL feedback log, and the
 * same seeded atoms are evaluated again.
 */
public final class FeedbackReplayEvaluationRunner {

    private static final String REPLAY_DIRECTORY = "replayed-persisted";
    private static final String SYNTHETIC_DIRECTORY = "seeded-synthetic";

    private final EvaluationRunner evaluationRunner;
    private final EvaluationComparator comparator;

    public FeedbackReplayEvaluationRunner() {
        this(new EvaluationRunner(), new EvaluationComparator());
    }

    public FeedbackReplayEvaluationRunner(
            EvaluationRunner evaluationRunner,
            EvaluationComparator comparator) {
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
        this.comparator = Objects.requireNonNull(comparator, "comparator");
    }

    public FeedbackReplayEvaluationReport run(
            EvaluationDataset dataset,
            List<FeedbackReplayEvent> replayEvents,
            Path basePath) {
        Objects.requireNonNull(dataset, "dataset");
        replayEvents = List.copyOf(Objects.requireNonNull(replayEvents, "replayEvents"));
        Objects.requireNonNull(basePath, "basePath");
        if (replayEvents.isEmpty()) {
            throw new IllegalArgumentException("replayEvents must not be empty");
        }

        Path replayPath = basePath.resolve(REPLAY_DIRECTORY);
        Path syntheticPath = basePath.resolve(SYNTHETIC_DIRECTORY);
        requireFreshDirectory(replayPath);
        requireFreshDirectory(syntheticPath);

        var profile = EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK;
        var options = profile.toMemoryOptions();
        var replayMemory = MonadaMemory.open(replayPath, options);
        var seeding = evaluationRunner.seedAtoms(dataset, replayMemory);
        var baseline = evaluationRunner.evaluate(dataset, replayMemory, seeding.idToLabel(), options);

        Map<String, List<String>> evaluationQueriesByKey = evaluationQueriesByKey(baseline);
        var resolvedEvents = resolveAndValidate(
                replayEvents, seeding.labelToAtomId(), evaluationQueriesByKey);
        appendResolvedEvents(replayPath, resolvedEvents);

        var replayed = evaluationRunner.evaluate(dataset, replayMemory, seeding.idToLabel(), options);

        var syntheticComparison = new EvaluationProfileRunner(evaluationRunner, comparator)
                .run(dataset, List.of(profile), syntheticPath);
        var seededSynthetic = syntheticComparison.reportByProfile().get(profile);

        var seededVsBaseline = comparator.compare(baseline, seededSynthetic);
        var replayedVsBaseline = comparator.compare(baseline, replayed);
        var diagnostics = resolvedEvents.stream()
                .map(ResolvedFeedback::diagnostic)
                .toList();

        return new FeedbackReplayEvaluationReport(
                baseline,
                seededSynthetic,
                replayed,
                seededVsBaseline,
                replayedVsBaseline,
                diagnostics);
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

    private record ResolvedFeedback(
            FeedbackEvent persistedEvent,
            FeedbackReplayEventDiagnostic diagnostic) {
    }
}
