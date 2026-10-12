package com.monada.evaluation.execution;

import com.monada.core.execution.ExecutionEvent.StageRecorded;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Effort, wall-clock and active time of stage intervals; parallel stages are never double counted as elapsed. */
final class TimeCalculator {

    private TimeCalculator() {
    }

    static TimeSummary of(List<StageRecorded> stages) {
        if (stages.isEmpty()) {
            return new TimeSummary(0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("no stage recorded"));
        }
        Duration effort = Duration.ZERO;
        Instant first = null;
        Instant last = null;
        List<StageRecorded> byStart = new ArrayList<>(stages);
        byStart.sort(Comparator.comparing(StageRecorded::startedAt).thenComparing(StageRecorded::endedAt));
        Duration active = Duration.ZERO;
        Instant runStart = null;
        Instant runEnd = null;
        for (StageRecorded s : byStart) {
            effort = effort.plus(Duration.between(s.startedAt(), s.endedAt()));
            first = first == null || s.startedAt().isBefore(first) ? s.startedAt() : first;
            last = last == null || s.endedAt().isAfter(last) ? s.endedAt() : last;
            if (runStart == null) {
                runStart = s.startedAt();
                runEnd = s.endedAt();
            } else if (!s.startedAt().isAfter(runEnd)) {
                runEnd = s.endedAt().isAfter(runEnd) ? s.endedAt() : runEnd;
            } else {
                active = active.plus(Duration.between(runStart, runEnd));
                runStart = s.startedAt();
                runEnd = s.endedAt();
            }
        }
        active = active.plus(Duration.between(runStart, runEnd));
        return new TimeSummary(stages.size(), Optional.of(first), Optional.of(last), Optional.of(effort),
                Optional.of(Duration.between(first, last)), Optional.of(active),
                Optional.of(effort.minus(active)), Optional.empty());
    }
}
