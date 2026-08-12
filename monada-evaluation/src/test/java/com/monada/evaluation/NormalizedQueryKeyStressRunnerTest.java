package com.monada.evaluation;

import com.monada.storage.feedback.FeedbackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizedQueryKeyStressRunnerTest {

    @Test
    void replaysEveryPairInIsolationAndProducesDeterministicRiskEvidence(
            @TempDir Path firstBase,
            @TempDir Path secondBase) throws Exception {
        var cases = NormalizedQueryKeyStressMain.loadFixture();
        var runner = new NormalizedQueryKeyStressRunner();

        var first = runner.run(cases, firstBase.resolve("run"));
        var second = runner.run(cases, secondBase.resolve("run"));

        assertEquals(first, second);
        assertEquals(first.render(), second.render());
        assertEquals(NormalizedQueryKeyStressDecision.NORMALIZED_STRESS_RISK, first.decision());
        assertEquals(24, first.observations().size());
        assertEquals(7, first.observations().stream()
                .filter(observation -> observation.stressCase().semanticRelationship()
                        == NormalizationSemanticRelationship.DISTINCT)
                .filter(NormalizedQueryKeyStressObservation::keysMatched)
                .count());
        assertEquals(7, first.observations().stream()
                .filter(observation -> observation.classification()
                        == NormalizedQueryKeyStressClassification.CONTAMINATION)
                .count());
        assertTrue(first.observations().stream()
                .filter(observation -> observation.stressCase().semanticRelationship()
                        == NormalizationSemanticRelationship.EQUIVALENT)
                .allMatch(observation -> observation.classification()
                        == NormalizedQueryKeyStressClassification.SAFE_EQUIVALENT_SHARING));

        var scoreOnly = observation(first, "relation_database_ledger");
        assertEquals(RankingChange.MAINTAINED, scoreOnly.targetRankChange());
        assertTrue(scoreOnly.scoreChanged());
        assertEquals(NormalizedQueryKeyStressClassification.CONTAMINATION,
                scoreOnly.classification());

        for (NormalizedQueryKeyStressCase stressCase : cases) {
            Path feedbackLog = firstBase.resolve("run")
                    .resolve(stressCase.id())
                    .resolve(FeedbackStore.DEFAULT_SEGMENT);
            assertTrue(Files.exists(feedbackLog), stressCase.id());
            assertEquals(1, Files.readAllLines(feedbackLog, StandardCharsets.UTF_8).size(), stressCase.id());
        }

        String rendered = first.render();
        assertTrue(rendered.contains("STOP_WORD_COORDINATION | 2 | N/A | 2/2 (1.0000) | 2/2 (1.0000)"));
        assertTrue(rendered.contains("BLANK_FALLBACK_BOUNDARY | 2 | N/A | 1/2 (0.5000) | 1/2 (0.5000)"));
        assertTrue(rendered.contains("Target score changed: true"));
        assertTrue(rendered.endsWith("ExactQueryKeyStrategy remains the production/default behavior.\n"));
    }

    @Test
    void classificationTreatsScoreOnlyMovementAsContamination() {
        var runner = new NormalizedQueryKeyStressRunner();

        assertEquals(NormalizedQueryKeyStressClassification.SAFE_EQUIVALENT_SHARING,
                runner.classify(NormalizationSemanticRelationship.EQUIVALENT, true, false));
        assertEquals(NormalizedQueryKeyStressClassification.SAFE_ISOLATION,
                runner.classify(NormalizationSemanticRelationship.DISTINCT, false, false));
        assertEquals(NormalizedQueryKeyStressClassification.COLLISION_WITHOUT_MOVEMENT,
                runner.classify(NormalizationSemanticRelationship.DISTINCT, true, false));
        assertEquals(NormalizedQueryKeyStressClassification.CONTAMINATION,
                runner.classify(NormalizationSemanticRelationship.DISTINCT, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> runner.classify(NormalizationSemanticRelationship.EQUIVALENT, false, false));
    }

    @Test
    void decisionSignalPrioritizesRiskAndRequiresReplaySensitivityForPass() {
        var runner = new NormalizedQueryKeyStressRunner();
        var equivalentMovement = syntheticObservation(
                runner, "equivalent-movement", NormalizationSemanticRelationship.EQUIVALENT,
                true, true);
        var equivalentNoMovement = syntheticObservation(
                runner, "equivalent-no-movement", NormalizationSemanticRelationship.EQUIVALENT,
                true, false);
        var distinctIsolation = syntheticObservation(
                runner, "distinct-isolation", NormalizationSemanticRelationship.DISTINCT,
                false, false);
        var distinctCollision = syntheticObservation(
                runner, "distinct-collision", NormalizationSemanticRelationship.DISTINCT,
                true, false);

        assertEquals(NormalizedQueryKeyStressDecision.NORMALIZED_STRESS_RISK,
                runner.decisionFor(List.of(equivalentMovement, distinctCollision)));
        assertEquals(NormalizedQueryKeyStressDecision.NORMALIZED_STRESS_PASS,
                runner.decisionFor(List.of(equivalentMovement, distinctIsolation)));
        assertEquals(NormalizedQueryKeyStressDecision.INCONCLUSIVE,
                runner.decisionFor(List.of(equivalentNoMovement, distinctIsolation)));
    }

    @Test
    void rejectsIncompleteStressShape(@TempDir Path basePath) throws Exception {
        var cases = NormalizedQueryKeyStressMain.loadFixture();

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new NormalizedQueryKeyStressRunner().run(
                        cases.subList(0, cases.size() - 1), basePath.resolve("run")))
                .getMessage().contains("exactly 24 cases"));
        assertFalse(Files.exists(basePath.resolve("run")));
    }

    private NormalizedQueryKeyStressObservation observation(
            NormalizedQueryKeyStressReport report,
            String id) {
        return report.observations().stream()
                .filter(observation -> observation.stressCase().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private NormalizedQueryKeyStressObservation syntheticObservation(
            NormalizedQueryKeyStressRunner runner,
            String id,
            NormalizationSemanticRelationship relationship,
            boolean keysMatched,
            boolean targetMoved) {
        Set<String> targetsA = Set.of("target-a");
        Set<String> targetsB = relationship == NormalizationSemanticRelationship.EQUIVALENT
                ? targetsA
                : Set.of("target-b");
        var stressCase = new NormalizedQueryKeyStressCase(
                id,
                relationship == NormalizationSemanticRelationship.EQUIVALENT
                        ? NormalizationStressCategory.CASING
                        : NormalizationStressCategory.TOKEN_ORDER,
                relationship,
                id + " query a",
                id + " query b",
                targetsA,
                targetsB,
                "target-a",
                relationship == NormalizationSemanticRelationship.EQUIVALENT
                        ? ExpectedQueryKeyRelation.MATCH
                        : ExpectedQueryKeyRelation.DISCOVER,
                10.0,
                Instant.EPOCH);
        String queryKeyA = "normalized:key-a";
        String queryKeyB = keysMatched ? queryKeyA : "normalized:key-b";
        var before = new FeedbackQueryKeyTargetRank(1, 0.1);
        var after = new FeedbackQueryKeyTargetRank(1, targetMoved ? 0.2 : 0.1);
        return new NormalizedQueryKeyStressObservation(
                stressCase,
                "normalized a",
                "normalized b",
                queryKeyA,
                queryKeyB,
                keysMatched,
                before,
                after,
                RankingChange.MAINTAINED,
                targetMoved,
                List.of("target-b"),
                List.of("target-b"),
                runner.classify(relationship, keysMatched, targetMoved));
    }
}
