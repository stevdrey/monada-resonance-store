package com.monada.evaluation.speech;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeechHybridRobustnessRunnerTest {

    private final SpeechHybridRobustnessRunner runner = new SpeechHybridRobustnessRunner();

    @Test
    void riskEvidenceWinsEvenWhenCoverageIsIncomplete() {
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY,
                runner.decide(List.of(result(SpeechHybridConflictCategory.AGREEMENT_RELEVANT,
                        SpeechHybridComparison.REGRESS, SpeechSpeakerRelation.SAME_SPEAKER,
                        SpeechCondition.CONTROL,
                        SpeechTaskType.COMMAND)), false));
    }

    @Test
    void missingCoverageIsInconclusiveWithoutObservedRisk() {
        assertEquals(SpeechHybridRobustnessDecision.INCONCLUSIVE,
                runner.decide(List.of(result(SpeechHybridConflictCategory.AGREEMENT_RELEVANT,
                        SpeechHybridComparison.MAINTAIN, SpeechSpeakerRelation.SAME_SPEAKER,
                        SpeechCondition.CONTROL,
                        SpeechTaskType.COMMAND)), false));
    }

    @Test
    void completeCoverageWithNoRegressionIsRobustButAnyGroupedRegressionIsRisky() {
        List<SpeechHybridRobustnessQueryResult> coverage = completeCoverage();
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_ROBUST, runner.decide(coverage, false));
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY, runner.decide(coverage, true));
    }

    private List<SpeechHybridRobustnessQueryResult> completeCoverage() {
        List<SpeechHybridRobustnessQueryResult> results = new ArrayList<>();
        SpeechHybridConflictCategory[] categories = SpeechHybridConflictCategory.values();
        for (int index = 0; index < categories.length; index++) {
            results.add(result(categories[index], SpeechHybridComparison.MAINTAIN,
                    index % 2 == 0 ? SpeechSpeakerRelation.SAME_SPEAKER : SpeechSpeakerRelation.CROSS_SPEAKER,
                    index % 2 == 0 ? SpeechCondition.CONTROL : SpeechCondition.DYSARTHRIC,
                    switch (index % 3) {
                        case 0 -> SpeechTaskType.COMMAND;
                        case 1 -> SpeechTaskType.SENTENCE;
                        default -> SpeechTaskType.WORD;
                    }));
        }
        return results;
    }

    private SpeechHybridRobustnessQueryResult result(
            SpeechHybridConflictCategory category,
            SpeechHybridComparison comparison,
            SpeechSpeakerRelation speakerRelation,
            SpeechCondition condition,
            SpeechTaskType taskType
    ) {
        PairedSpeechQuery query = new PairedSpeechQuery(category.name(), Path.of(category.name() + ".wav"),
                "synthetic", Set.of("sample"), condition, taskType);
        SpeechHybridRobustnessCase stressCase = new SpeechHybridRobustnessCase(query, "speaker", category,
                category == SpeechHybridConflictCategory.TRANSCRIPT_MISSING ? Set.of("sample") : Set.of(),
                category == SpeechHybridConflictCategory.ACOUSTIC_MISSING ? Set.of("sample") : Set.of());
        return new SpeechHybridRobustnessQueryResult(stressCase, null, null, speakerRelation,
                SpeechHybridBetterControl.TIED, comparison);
    }
}
