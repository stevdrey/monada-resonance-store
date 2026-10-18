package com.monada.evaluation.speech;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechMissingModalityPolicyRunnerTest {

    private final SpeechMissingModalityPolicyRunner runner = new SpeechMissingModalityPolicyRunner();

    @Test
    void decisionVocabularyIsExploratoryOnly() {
        assertEquals(Set.of(SpeechMissingModalityPolicyDecision.RISK_REMAINS, SpeechMissingModalityPolicyDecision.FURTHER_STUDY),
                Set.of(SpeechMissingModalityPolicyDecision.values()));
    }

    @Test
    void referenceArmAlwaysInheritsTheObservedRisk() {
        SpeechMissingModalityPolicyVerdict verdict = runner.decide(SpeechMissingModalityPolicy.BOTH_REQUIRED,
                List.of(clean("development", 1), clean("holdout", 1)));

        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, verdict.decision());
    }

    @Test
    void cleanPolicyWithRecoveryOnBothSplitsIsOnlyEligibleForFurtherStudy() {
        SpeechMissingModalityPolicyVerdict verdict = runner.decide(SpeechMissingModalityPolicy.AVAILABLE_MODALITY_ONLY,
                List.of(clean("development", 2), clean("holdout", 0)));

        assertEquals(SpeechMissingModalityPolicyDecision.FURTHER_STUDY, verdict.decision());
        assertTrue(verdict.reasons().isEmpty());
        assertEquals(SpeechMissingModalityPolicyDecision.FURTHER_STUDY, runner.overall(List.of(
                new SpeechMissingModalityPolicyVerdict(SpeechMissingModalityPolicy.BOTH_REQUIRED,
                        SpeechMissingModalityPolicyDecision.RISK_REMAINS, List.of("reference")),
                verdict)));
    }

    @Test
    void missingModalityRecoveriesCannotOffsetADirectConflictRegression() {
        SpeechMissingModalityPolicyVerdict verdict = runner.decide(SpeechMissingModalityPolicy.AVAILABLE_MODALITY_ONLY,
                List.of(clean("development", 2), split("holdout", 0, 0, 1, List.of(), 2)));

        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, verdict.decision());
        assertTrue(verdict.reasons().contains("holdout: 1 conflict/agreement regression(s)"));
    }

    @Test
    void anyRegressionKindOrMissingHoldoutBlocksFurtherStudy() {
        SpeechMissingModalityPolicy policy = SpeechMissingModalityPolicy.ABSTAIN_ON_PARTIAL;
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, runner.decide(policy,
                List.of(clean("development", 1), split("holdout", 1, 0, 0, List.of(), 1))).decision());
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, runner.decide(policy,
                List.of(clean("development", 1), split("holdout", 0, 1, 0, List.of(), 1))).decision());
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, runner.decide(policy,
                List.of(clean("development", 1), split("holdout", 0, 0, 0, List.of("task=WORD"), 1))).decision());
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, runner.decide(policy,
                List.of(clean("development", 0), clean("holdout", 0))).decision());
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, runner.decide(policy,
                List.of(clean("development", 2))).decision());
    }

    @Test
    void overallDecisionStaysRiskRemainsWhenOnlyTheReferenceArmIsListed() {
        assertEquals(SpeechMissingModalityPolicyDecision.RISK_REMAINS, runner.overall(List.of(
                new SpeechMissingModalityPolicyVerdict(SpeechMissingModalityPolicy.BOTH_REQUIRED,
                        SpeechMissingModalityPolicyDecision.FURTHER_STUDY, List.of()))));
    }

    private SpeechMissingModalityPolicySplitResult clean(String split, int recoveries) {
        return split(split, 0, 0, 0, List.of(), recoveries);
    }

    private SpeechMissingModalityPolicySplitResult split(
            String split,
            int regressionsVsStronger,
            int regressionsVsBothRequired,
            int conflictRegressions,
            List<String> regressedGroups,
            int recoveries
    ) {
        return new SpeechMissingModalityPolicySplitResult(split, SpeechMissingModalityPolicy.AVAILABLE_MODALITY_ONLY,
                List.of(), List.of(), regressionsVsStronger, regressionsVsBothRequired, conflictRegressions,
                regressedGroups, recoveries, 0);
    }
}
