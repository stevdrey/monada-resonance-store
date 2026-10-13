package com.monada.evaluation.execution;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Pure checks of one exported {@link ExperienceSample} against expectations derived from the fixture, never from
 * the export itself: who may appear in an export, what a success needs, which group and ordinal it has and which
 * kind of ledger event each provenance reference must address. The expectations follow the sample contract of
 * {@code SampleBuilder} (contract v1, section 21); a legitimate change of that contract must change them here
 * explicitly.
 */
final class ForgeSampleChecks {
    private ForgeSampleChecks() {
    }

    // --- membership: exactly one sample per execution ---------------------------------------------------

    /**
     * An export holds exactly one sample per execution of the project. Multiplicities are counted, so a
     * duplicated, missing or foreign execution is reported on its own and never hidden by a set comparison.
     */
    static List<String> exportMembershipViolations(ForgeFixture fixture, String scopeKey,
                                                   List<ExperienceSample> samples) {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        samples.forEach(s -> counts.merge(s.executionId().value(), 1, Integer::sum));
        Set<String> expected = new TreeSet<>();
        fixture.executionsOf(scopeKey).forEach(e -> expected.add(e.id()));
        counts.forEach((id, n) -> {
            if (n > 1) {
                violations.add(scopeKey + ": execution " + id + " is exported " + n + " times");
            }
            if (!expected.contains(id)) {
                violations.add(scopeKey + ": export holds the foreign or unknown execution " + id);
            }
        });
        expected.stream().filter(id -> !counts.containsKey(id))
                .forEach(id -> violations.add(scopeKey + ": execution " + id + " is missing from the export"));
        if (samples.size() != expected.size()) {
            violations.add(scopeKey + ": export holds " + samples.size() + " samples for " + expected.size()
                    + " executions");
        }
        return violations;
    }

    // --- success: mandatory gates, group and ordinal ----------------------------------------------------

    /** The repeat-detection key the fixture implies for an execution (task, revision, context, constraints, policy). */
    static ExperienceSample.Group expectedGroup(ForgeFixture.Execution e) {
        return new ExperienceSample.Group(com.monada.core.execution.TaskId.of(e.task()), e.sourceRevision(),
                e.contextFingerprint(), e.constraintsFingerprint(), ForgeFixtureRecorder.POLICY_ID,
                ForgeFixtureRecorder.POLICY_VERSION);
    }

    /**
     * The success ordinal of every judged-SUCCESS execution of a project: its position, in recording order,
     * among the successes of its group. Derived from the fixture alone.
     */
    static Map<String, Integer> expectedOrdinals(ForgeFixture fixture, String scopeKey) {
        Map<ExperienceSample.Group, Integer> next = new LinkedHashMap<>();
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        for (ForgeFixture.Execution e : fixture.executionsOf(scopeKey)) {
            if (e.expectedLabel().equals("SUCCESS")) {
                ordinals.put(e.id(), next.merge(expectedGroup(e), 1, Integer::sum));
            }
        }
        return ordinals;
    }

    /**
     * Everything a sample must satisfy beyond its label: the group, the ordinal (present exactly for a success and
     * equal to the derived one) and, for a success, one passing gate for every mandatory dimension of the policy.
     */
    static List<String> successViolations(ForgeFixture fixture, ExperienceSample sample) {
        List<String> violations = new ArrayList<>();
        String exec = sample.executionId().value();
        ForgeFixture.Execution judged = fixture.execution(exec);

        if (!sample.group().equals(expectedGroup(judged))) {
            violations.add(exec + ": group " + sample.group() + " but derived " + expectedGroup(judged));
        }
        Integer ordinal = expectedOrdinals(fixture, judged.scopeKey()).get(exec);
        if (sample.label() == ExperienceSample.Label.SUCCESS) {
            if (ordinal == null) {
                violations.add(exec + ": SUCCESS but judged " + judged.expectedLabel());
            } else if (sample.successOrdinal().isEmpty() || sample.successOrdinal().getAsInt() != ordinal) {
                violations.add(exec + ": success ordinal " + sample.successOrdinal() + " but derived " + ordinal);
            }
            violations.addAll(gateViolations(exec, judged.mandatory(), sample));
        } else if (sample.successOrdinal().isPresent()) {
            violations.add(exec + ": non-success sample carries the success ordinal " + sample.successOrdinal());
        }
        return violations;
    }

    private static List<String> gateViolations(String exec, Set<QualityDimension> mandatory,
                                               ExperienceSample sample) {
        List<String> violations = new ArrayList<>();
        if (!EnumSet.copyOf(sample.requiredGates()).equals(EnumSet.copyOf(mandatory))
                || sample.requiredGates().size() != mandatory.size()) {
            violations.add(exec + ": required gates " + sample.requiredGates() + " but the policy mandates "
                    + new TreeSet<>(mandatory));
        }
        Map<QualityDimension, Integer> required = new TreeMap<>();
        for (ExperienceSample.Gate g : sample.gates()) {
            if (g.required()) {
                required.merge(g.dimension(), 1, Integer::sum);
                boolean passing = g.state() == ExperienceSample.GateState.PASS
                        || g.state() == ExperienceSample.GateState.NOT_APPLICABLE;
                if (!passing) {
                    violations.add(exec + ": SUCCESS although required gate " + g.dimension() + " is " + g.state());
                }
                if (passing && (g.observation().isEmpty() || g.ref().isEmpty())) {
                    violations.add(exec + ": gate " + g.dimension() + " is " + g.state()
                            + " without the observation that backs it");
                }
            }
        }
        for (QualityDimension d : mandatory) {
            int n = required.getOrDefault(d, 0);
            if (n != 1) {
                violations.add(exec + ": SUCCESS carries " + n + " required gates for the mandatory dimension " + d);
            }
        }
        required.keySet().stream().filter(d -> !mandatory.contains(d))
                .forEach(d -> violations.add(exec + ": required gate " + d + " is not mandated by the policy"));
        return violations;
    }

    // --- references: each one must address the fact it is attached to -----------------------------------

    /** Which enclosing attempt a reference's event must belong to. */
    record AttemptRule(boolean constrained, Optional<AttemptId> id) {
        static AttemptRule none() {
            return new AttemptRule(true, Optional.empty());
        }

        static AttemptRule of(AttemptId id) {
            return new AttemptRule(true, Optional.of(id));
        }

        static AttemptRule any() {
            return new AttemptRule(false, Optional.empty());
        }

        boolean accepts(Optional<AttemptId> actual) {
            return !constrained || id.equals(actual);
        }

        @Override
        public String toString() {
            return !constrained ? "any attempt" : id.map(AttemptId::value).orElse("no attempt");
        }
    }

    /**
     * What a provenance reference must address: the event kind ({@code outer} = the stored envelope, as for a
     * correction itself, otherwise its effective payload), the enclosing attempt and the content the origin claims.
     */
    record Expectation(EventKind kind, boolean outer, AttemptRule attempt, Predicate<ExecutionEvent> content,
                       String description) {
        static Expectation of(EventKind kind, AttemptRule attempt) {
            return new Expectation(kind, false, attempt, e -> true, "any content");
        }

        static Expectation of(EventKind kind, AttemptRule attempt, Predicate<ExecutionEvent> content,
                              String description) {
            return new Expectation(kind, false, attempt, content, description);
        }

        static Expectation correction(AttemptRule attempt) {
            return new Expectation(EventKind.CORRECTION, true, attempt, e -> true, "any content");
        }
    }

    /** The effective payload of a ledger event: a correction stands for its replacement. */
    static ExecutionEvent effective(ExecutionEvent event) {
        return event instanceof Correction c ? c.replacement() : event;
    }

    /**
     * Checks one reference against what it is attached to: the effective event kind, the enclosing attempt and the
     * content the origin claims (an attempt's ordinal, a finish's result, a stage's name, a gate's dimension).
     * Coordinates are checked separately; this proves the reference is not merely a genuine event, but the right one.
     */
    static List<String> factViolations(String what, Expectation expectation, HistoryEntry entry) {
        List<String> violations = new ArrayList<>();
        ExecutionEvent outer = entry.event();
        ExecutionEvent actual = expectation.outer() ? outer : effective(outer);
        if (actual.kind() != expectation.kind()) {
            violations.add(what + " addresses a " + actual.kind() + " event but must address " + expectation.kind());
            return violations;
        }
        if (!expectation.attempt().accepts(outer.envelope().attemptId())) {
            violations.add(what + " belongs to attempt "
                    + outer.envelope().attemptId().map(AttemptId::value).orElse("<none>") + " but must belong to "
                    + expectation.attempt());
        }
        if (!expectation.content().test(effective(outer))) {
            violations.add(what + " does not carry " + expectation.description());
        }
        return violations;
    }

    static Predicate<ExecutionEvent> startedWithOrdinal(int ordinal) {
        return e -> e instanceof AttemptStarted s && s.ordinal() == ordinal;
    }

    static Predicate<ExecutionEvent> finishedWith(Optional<com.monada.core.execution.AttemptResult> result) {
        return e -> e instanceof AttemptFinished f && result.map(r -> r == f.result()).orElse(true);
    }

    static Predicate<ExecutionEvent> stageNamed(String name) {
        return e -> e instanceof StageRecorded s && s.stage().equals(name);
    }

    static Predicate<ExecutionEvent> observing(QualityDimension dimension) {
        return e -> e instanceof EvidenceRecorded ev && ev.observations().stream().map(QualityObservation::dimension)
                .anyMatch(dimension::equals);
    }

    static EventKind kindOf(ExecutionEvent event) {
        return effective(event).kind();
    }
}
