package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Entry point for running the A/B profile comparison harness from the command line:
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runProfileComparison -q
 * </pre>
 *
 * <p>Evaluates the {@link ExpandedTechnologyDataset} against three retrieval profiles:
 * <ul>
 *   <li>{@link EvaluationProfile#RAW} — no enrichment, no feedback.</li>
 *   <li>{@link EvaluationProfile#LEXICAL_ENRICHED} — full lexical pipeline, no feedback.</li>
 *   <li>{@link EvaluationProfile#LEXICAL_ENRICHED_WITH_FEEDBACK} — lexical pipeline with
 *       deterministic seeded feedback events.</li>
 * </ul>
 *
 * <p>Each profile runs in its own isolated subdirectory inside a temporary base directory,
 * which is removed on exit. Metric values from this run are exploratory and are not
 * protected as a strict regression baseline.
 */
public final class ProfileComparisonMain {

    private ProfileComparisonMain() {
    }

    public static void main(String[] args) throws IOException {
        var basedir = Files.createTempDirectory("monada-profile-comparison-");
        try {
            var profiles = List.of(
                    EvaluationProfile.RAW,
                    EvaluationProfile.LEXICAL_ENRICHED_FULL_EXPANSION_WEIGHT,
                    EvaluationProfile.LEXICAL_ENRICHED,
                    EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK,
                    EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY
            );
            var comparison = new EvaluationProfileRunner()
                    .run(ExpandedTechnologyDataset.get(), profiles, basedir);
            System.out.println(TextEvaluationCatalog.EXPANDED_PROFILE_COMPARISON
                    .render(comparison.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(basedir);
        }
    }
}
