package com.monada.api.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import java.util.List;
import java.util.Optional;

/**
 * One attempt as currently known: corrected events are replaced by their latest revision, in ledger order.
 * Stage routes, usage and evidence are returned exactly as recorded.
 */
public record AttemptView(AttemptId id, int ordinal, AttemptReason reason, Optional<AttemptId> previousAttempt,
                          Optional<AttemptResult> result, Optional<String> solutionSummary,
                          Optional<String> lessonSummary, List<StageRecorded> stages,
                          List<EvidenceRecorded> evidence) {
    public AttemptView {
        stages = List.copyOf(stages);
        evidence = List.copyOf(evidence);
    }

    public boolean isInProgress() {
        return result.isEmpty();
    }
}
