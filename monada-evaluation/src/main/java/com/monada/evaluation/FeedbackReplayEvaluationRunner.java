package com.monada.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private final PersistedFeedbackReplayRunner persistedReplayRunner;

    public FeedbackReplayEvaluationRunner() {
        this(new EvaluationRunner(), new EvaluationComparator());
    }

    public FeedbackReplayEvaluationRunner(
            EvaluationRunner evaluationRunner,
            EvaluationComparator comparator) {
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.persistedReplayRunner = new PersistedFeedbackReplayRunner(
                this.evaluationRunner, this.comparator);
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
        var replayArm = persistedReplayRunner.run(dataset, replayEvents, replayPath, profile);

        var syntheticComparison = new EvaluationProfileRunner(evaluationRunner, comparator)
                .run(dataset, List.of(profile), syntheticPath);
        var seededSynthetic = syntheticComparison.reportByProfile().get(profile);

        var seededVsBaseline = comparator.compare(replayArm.baseline(), seededSynthetic);

        return new FeedbackReplayEvaluationReport(
                replayArm.baseline(),
                seededSynthetic,
                replayArm.replayedPersisted(),
                seededVsBaseline,
                replayArm.replayedVsBaseline(),
                replayArm.replayDiagnostics());
    }

    private void requireFreshDirectory(Path path) {
        if (Files.exists(path)) {
            throw new IllegalArgumentException("evaluation mode directory already exists: " + path);
        }
    }

}
