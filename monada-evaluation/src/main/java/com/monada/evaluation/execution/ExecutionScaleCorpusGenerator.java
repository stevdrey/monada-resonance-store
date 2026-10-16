package com.monada.evaluation.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Generates the synthetic execution corpus of the scale sweep (issue #125) from an explicit seed: the same seed and
 * size always produce the same events, ids, timestamps, summaries and queries in the same order.
 *
 * <p>Every scope holds exactly {@code experiencesPerScope} recallable experiences (attempt finishes at their latest
 * revision). The occurrence patterns are those of the #121 multi-attempt fixture, drawn by the seed: a single
 * accepted attempt, a failure repaired by a second attempt, a failure followed by an unfinished repair (never
 * recallable), a finish corrected to another result (its first text becomes a retired atom) and groups of
 * independent executions with identical texts (one shared atom, several refs). Each scope also holds one execution
 * whose text is identical in every scope, the probe for cross-scope leakage.
 *
 * <p>Texts carry a per-execution case token, so two texts are identical only where a pattern makes them so and the
 * ground truth (live atoms, retired atoms, refs per atom) follows from the generated texts alone.
 */
final class ExecutionScaleCorpusGenerator {
    static final long DEFAULT_SEED = 42L;
    static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    /** The isolated scopes of every size, in recording order. */
    static final List<String> SCOPES = List.of("scale-alpha", "scale-beta", "scale-gamma");
    /** Smallest size that still holds the probe, one duplicate group and one generated execution. */
    static final int MIN_EXPERIENCES_PER_SCOPE = 5;
    static final int EXACT_QUERIES_PER_SCOPE = 4;
    static final int TEMPLATE_QUERIES_PER_SCOPE = 4;
    static final ForgeFixture.Price PRICE = new ForgeFixture.Price("scale-sweep-prices", "1", "fixture-provider",
            "fixture-model", "USD", LocalDate.parse("2026-02-01"), BigDecimal.valueOf(2), BigDecimal.TEN, 1_000_000L);

    private static final String PROBE_TEXT_SUMMARY = "Fix shared probe timeout in the cross scope gateway";
    private static final String PROBE_SOLUTION = "added a bounded retry around the gateway probe call";
    private static final String PROBE_LESSON = "probe calls need an explicit deadline";
    private static final String JUSTIFICATION = "reviewed correction of the attempt result and summaries";

    private static final List<String> TOPICS = List.of("invoice", "billing", "login", "cache", "parser", "report",
            "export", "schema", "payment", "search", "upload", "session", "ledger", "catalog", "routing", "audit");
    private static final List<String> COMPONENTS = List.of("service", "module", "endpoint", "worker", "adapter",
            "controller", "repository", "scheduler");
    private static final List<String> ISSUES = List.of("timeout", "null pointer", "rounding error", "race condition",
            "memory leak", "encoding bug", "stale cache", "off by one", "deadlock", "missing index");
    private static final List<String> ACTIONS = List.of("patched", "refactored", "added a retry to", "validated",
            "rewrote", "guarded", "simplified", "instrumented");
    private static final List<String> LESSONS = List.of("check boundaries before release",
            "reproduce with a failing test first", "keep the fix small and reviewable",
            "measure before optimizing", "log the inputs that trigger the failure",
            "prefer explicit configuration over defaults");

    /** How an execution's attempts and outcome are shaped. */
    enum Pattern { PROBE, SINGLE, REPAIR, UNFINISHED_REPAIR, CORRECTED, DUPLICATE }

    enum QueryKind { PROBE, DUPLICATE, EXACT, TEMPLATE }

    /** One recallable experience: the exact ref recall must return for it and its projection text. */
    record Experience(String experienceId, ExperienceRef ref, String text, AttemptResult result, String solution) {
    }

    /** One query of a scope; {@code target} is the experience whose exact text the query is, if any. */
    record Query(String id, QueryKind kind, String text, Optional<String> target) {
    }

    /** One scope's events in recording order plus the ground truth the structural checks compare with. */
    record ScopeCorpus(ScopeId scope, List<ExecutionEvent> events, List<Experience> experiences,
                       List<Query> queries, Map<Pattern, Integer> patternCounts, int executions,
                       int attemptsStarted, int unfinishedAttempts, int correctedFinishes, Set<String> liveTexts,
                       Set<String> retiredTexts) {
        ScopeCorpus {
            events = List.copyOf(events);
            experiences = List.copyOf(experiences);
            queries = List.copyOf(queries);
            patternCounts = Map.copyOf(patternCounts);
            liveTexts = Set.copyOf(liveTexts);
            retiredTexts = Set.copyOf(retiredTexts);
        }

        /** Finished attempts at their latest revision: what recall can return. */
        int activeAttempts() {
            return experiences.size();
        }
    }

    record ScaleCorpus(long seed, int experiencesPerScope, List<ScopeCorpus> scopes) {
        ScaleCorpus {
            scopes = List.copyOf(scopes);
        }
    }

    private ExecutionScaleCorpusGenerator() {
    }

    static ScaleCorpus generate(long seed, int experiencesPerScope) {
        if (experiencesPerScope < MIN_EXPERIENCES_PER_SCOPE) {
            throw new IllegalArgumentException("experiencesPerScope must be >= " + MIN_EXPERIENCES_PER_SCOPE
                    + ", got: " + experiencesPerScope);
        }
        List<ScopeCorpus> scopes = new ArrayList<>();
        for (int s = 0; s < SCOPES.size(); s++) {
            // One independent stream per scope: a scope's corpus never depends on how many draws another took.
            scopes.add(new ScopeBuilder(ScopeId.of(SCOPES.get(s)), new Random(seed * 31 + s),
                    experiencesPerScope).build());
        }
        return new ScaleCorpus(seed, experiencesPerScope, scopes);
    }

    private static final class ScopeBuilder {
        private final ScopeId scope;
        private final String key;
        private final Random random;
        private final int target;
        private final RouteDescriptor route = ForgeFixtureRecorder.route(PRICE);
        private final List<ExecutionEvent> events = new ArrayList<>();
        private final List<Experience> experiences = new ArrayList<>();
        private final Map<Pattern, Integer> patterns = new LinkedHashMap<>();
        private final Set<String> liveTexts = new LinkedHashSet<>();
        private final Set<String> retiredTexts = new LinkedHashSet<>();
        private final List<String> duplicateTexts = new ArrayList<>();
        private int executions;
        private int attemptsStarted;
        private int unfinished;
        private int corrected;

        ScopeBuilder(ScopeId scope, Random random, int target) {
            this.scope = scope;
            this.key = scope.value().substring("scale-".length());
            this.random = random;
            this.target = target;
        }

        ScopeCorpus build() {
            probe();
            duplicates(2);
            while (experiences.size() < target) {
                int remaining = target - experiences.size();
                int draw = random.nextInt(100);
                if (draw < 50 || remaining < 2) {
                    single();
                } else if (draw < 65) {
                    repair();
                } else if (draw < 75) {
                    unfinishedRepair();
                } else if (draw < 87) {
                    corrected();
                } else {
                    duplicates(Math.min(remaining, 2 + random.nextInt(2)));
                }
            }
            for (String text : retiredTexts) {
                if (liveTexts.contains(text)) {
                    throw new IllegalStateException("a retired text is still live: " + text);
                }
            }
            return new ScopeCorpus(scope, events, experiences, queries(), patterns, executions, attemptsStarted,
                    unfinished, corrected, liveTexts, retiredTexts);
        }

        private List<Query> queries() {
            List<Query> queries = new ArrayList<>();
            queries.add(new Query(key + "-q-probe", QueryKind.PROBE, text(PROBE_TEXT_SUMMARY, PROBE_SOLUTION,
                    PROBE_LESSON), Optional.of(experiences.get(0).experienceId())));
            queries.add(new Query(key + "-q-dup", QueryKind.DUPLICATE, duplicateTexts.get(0),
                    Optional.of(experiences.get(1).experienceId())));
            for (int i = 0; i < EXACT_QUERIES_PER_SCOPE; i++) {
                Experience e = experiences.get(random.nextInt(experiences.size()));
                queries.add(new Query(key + "-q-exact-" + i, QueryKind.EXACT, e.text(), Optional.of(e.experienceId())));
            }
            for (int i = 0; i < TEMPLATE_QUERIES_PER_SCOPE; i++) {
                queries.add(new Query(key + "-q-template-" + i, QueryKind.TEMPLATE, pick(ISSUES) + " in the "
                        + pick(TOPICS) + " " + pick(COMPONENTS), Optional.empty()));
            }
            return queries;
        }

        // --- patterns ---------------------------------------------------------------------------------------

        private void probe() {
            Exec x = start(Pattern.PROBE, PROBE_TEXT_SUMMARY);
            finish(x, 1, "INITIAL", AttemptResult.COMPLETED, PROBE_SOLUTION, PROBE_LESSON, Optional.empty());
            accept(x, 1);
        }

        private void single() {
            Exec x = start(Pattern.SINGLE, summary());
            finish(x, 1, "INITIAL", AttemptResult.COMPLETED, solution(), pick(LESSONS), Optional.empty());
            accept(x, 1);
        }

        private void repair() {
            Exec x = start(Pattern.REPAIR, summary());
            finish(x, 1, "INITIAL", AttemptResult.FAILED, solution(), pick(LESSONS), Optional.empty());
            finish(x, 2, "REPAIR", AttemptResult.COMPLETED, solution() + " after the first fix failed",
                    pick(LESSONS), Optional.empty());
            accept(x, 2);
        }

        private void unfinishedRepair() {
            Exec x = start(Pattern.UNFINISHED_REPAIR, summary());
            finish(x, 1, "INITIAL", AttemptResult.FAILED, solution(), pick(LESSONS), Optional.empty());
            open(x, 2, "REPAIR"); // started, never finished: no ATTEMPT_FINISHED fact exists to recall
            unfinished++;
        }

        private void corrected() {
            Exec x = start(Pattern.CORRECTED, summary());
            String first = solution();
            String lesson = pick(LESSONS);
            String revised = "reverted: " + first + " broke the " + pick(TOPICS) + " checks";
            retiredTexts.add(text(x.summary, first, lesson));
            finish(x, 1, "INITIAL", AttemptResult.COMPLETED, first, lesson, Optional.of(
                    new ForgeFixtureRecorder.Revision(AttemptResult.FAILED, revised, lesson, JUSTIFICATION)));
            corrected++;
            events.add(ForgeFixtureRecorder.outcome(scope, x.task, x.exec, Outcome.rejected(OutcomeOrigin.VALIDATED),
                    x.at(1)));
        }

        /** {@code size} independent executions with one identical summary, solution and lesson. */
        private void duplicates(int size) {
            String tag = "group " + key + "-g" + executions;
            String summary = "Fix " + pick(ISSUES) + " in the " + pick(TOPICS) + " " + pick(COMPONENTS) + " " + tag;
            String solution = solution();
            String lesson = pick(LESSONS);
            duplicateTexts.add(text(summary, solution, lesson));
            for (int i = 0; i < size; i++) {
                Exec x = start(Pattern.DUPLICATE, summary);
                finish(x, 1, "INITIAL", AttemptResult.COMPLETED, solution, lesson, Optional.empty());
                accept(x, 1);
            }
        }

        // --- event building -----------------------------------------------------------------------------------

        private record Exec(TaskId task, ExecutionId exec, String summary, Instant base) {
            Instant at(int ordinal) {
                return base.plusSeconds(60L * ordinal);
            }
        }

        private Exec start(Pattern pattern, String summary) {
            String id = key + "-e" + String.format(java.util.Locale.ROOT, "%05d", executions);
            Instant base = T0.plusSeconds(600L * executions);
            executions++;
            patterns.merge(pattern, 1, Integer::sum);
            Exec x = new Exec(TaskId.of("task-" + id), ExecutionId.of(id), summary, base);
            events.add(ForgeFixtureRecorder.executionStarted(scope, x.task, x.exec, "rev-" + id, "ctx-" + id,
                    "constraints-v1", Set.of(QualityDimension.TESTS), summary, base));
            return x;
        }

        private void open(Exec x, int ordinal, String reason) {
            attemptsStarted++;
            String experienceId = ForgeFixtureRecorder.experienceId(x.exec.value(),
                    ForgeFixtureRecorder.attemptId(ordinal));
            events.addAll(ForgeFixtureRecorder.attemptOpened(scope, x.task, x.exec, experienceId, ordinal, reason,
                    x.at(ordinal - 1), x.at(ordinal), route, 1000L + random.nextInt(9000), 100L + random.nextInt(900),
                    Map.of()));
        }

        private void finish(Exec x, int ordinal, String reason, AttemptResult result, String solution, String lesson,
                            Optional<ForgeFixtureRecorder.Revision> revision) {
            open(x, ordinal, reason);
            events.addAll(ForgeFixtureRecorder.attemptFinished(scope, x.task, x.exec, ordinal, x.at(ordinal), result,
                    solution, lesson, revision));
            AttemptId attempt = ForgeFixtureRecorder.attemptId(ordinal);
            String prefix = x.exec.value() + "." + attempt.value();
            String latestSolution = revision.map(ForgeFixtureRecorder.Revision::solution).orElse(solution);
            String latestLesson = revision.map(ForgeFixtureRecorder.Revision::lesson).orElse(lesson);
            AttemptResult latestResult = revision.map(ForgeFixtureRecorder.Revision::result).orElse(result);
            EventId eventId = EventId.of(revision.isPresent() ? prefix + ".fin.r2" : prefix + ".fin");
            ExperienceRef ref = new ExperienceRef(scope, x.task, x.exec, Optional.of(attempt), eventId,
                    revision.isPresent() ? 2 : 1);
            String text = text(x.summary, latestSolution, latestLesson);
            experiences.add(new Experience(ForgeFixtureRecorder.experienceId(x.exec.value(), attempt), ref, text,
                    latestResult, latestSolution));
            liveTexts.add(text);
        }

        private void accept(Exec x, int ordinal) {
            events.add(ForgeFixtureRecorder.outcome(scope, x.task, x.exec, Outcome.accepted(OutcomeOrigin.VALIDATED,
                    ForgeFixtureRecorder.attemptId(ordinal)), x.at(ordinal)));
        }

        // --- text -------------------------------------------------------------------------------------------

        /** A task summary with this execution's case token, so it is identical to no other summary. */
        private String summary() {
            return "Fix " + pick(ISSUES) + " in the " + pick(TOPICS) + " " + pick(COMPONENTS) + " case " + key
                    + "-c" + executions;
        }

        private String solution() {
            return pick(ACTIONS) + " the " + pick(TOPICS) + " " + pick(COMPONENTS) + " to handle the " + pick(ISSUES);
        }

        private String pick(List<String> values) {
            return values.get(random.nextInt(values.size()));
        }
    }

    /** The projection text of scheme {@code summary-text-v1}: summary, solution and lesson joined by a line feed. */
    static String text(String summary, String solution, String lesson) {
        return summary + "\n" + solution + "\n" + lesson;
    }
}
