package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceHit;
import com.monada.api.execution.ExperienceRecall;
import com.monada.api.execution.ProjectionStatus;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.HitAtK;
import com.monada.evaluation.RecallAtK;
import com.monada.evaluation.ReciprocalRank;
import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import com.monada.evaluation.execution.RecallStressReport.Hit;
import com.monada.evaluation.execution.RecallStressReport.Neighbour;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import com.monada.evaluation.execution.RecallStressReport.Reason;
import com.monada.evaluation.execution.RecallStressReport.RelevantPosition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs the judged recall stress set (issue #122) through public APIs only: it records the unchanged
 * {@code forge-experience-v1} corpus into a fresh store per project, rebuilds the projection, recalls every judged
 * query with the production defaults and classifies each hit and each query against the hand judgments.
 *
 * <p>The corpus is recorded twice in two fresh directories to check determinism, and the #102 evaluation runs once
 * more in its own directory so the BASELINE rankings can be compared with it. Nothing is tuned: no encoder,
 * projection, threshold or query key is changed or configured here. No network, provider or Forge is used.
 */
public final class RecallStressEvaluator {
    private RecallStressEvaluator() {
    }

    /** One recalled hit reduced to what the report and the checks need. */
    record Observed(String experienceId, String scopeId, String execution, int revision, double similarity) {
    }

    /** All rankings of one fresh store. */
    record Observation(Map<String, String> projectionState, Map<String, List<Observed>> hits) {
        String fingerprint() {
            StringBuilder out = new StringBuilder();
            hits.forEach((id, list) -> {
                out.append(id).append(' ').append(projectionState.get(id));
                list.forEach(h -> out.append(' ').append(h.experienceId()).append('@').append(h.revision())
                        .append(':').append(h.similarity()));
                out.append('\n');
            });
            return out.toString();
        }
    }

    /**
     * Evaluates {@code fixture} with stores created below {@code workDir} (created if missing). Every call uses its
     * own fresh subdirectories; the caller owns {@code workDir} and removes it.
     */
    public static RecallStressReport evaluate(RecallStressFixture fixture, Path workDir) {
        Map<String, Long> elapsed = new LinkedHashMap<>();
        long t = System.nanoTime();
        Observation first = observe(fixture, fresh(workDir, "stress-run-"));
        elapsed.put("record+recall (run 1)", System.nanoTime() - t);
        t = System.nanoTime();
        Observation second = observe(fixture, fresh(workDir, "stress-run-"));
        elapsed.put("record+recall (run 2)", System.nanoTime() - t);
        t = System.nanoTime();
        ForgeEvaluationReport baseline = ForgeExperienceEvaluator.evaluate(fixture.corpus(),
                fresh(workDir, "baseline-102-"));
        elapsed.put("baseline #102 evaluation", System.nanoTime() - t);

        int deepest = fixture.ks().get(fixture.ks().size() - 1);
        List<QueryOutcome> outcomes = new ArrayList<>();
        for (StressQuery q : fixture.queries()) {
            outcomes.add(outcome(fixture.corpus(), q, first.projectionState().get(q.id()), first.hits().get(q.id()),
                    fixture.ks(), deepest));
        }
        List<ForgeGate> gates = List.of(scopedRecallGate(fixture, first), blankQueryGate(fixture, first),
                determinismGate(first, second), baselineParityGate(fixture, first, baseline));
        return new RecallStressReport(fixture.version(), fixture.provenance(), fixture.judgmentProtocol(),
                fixture.reviewer(), fixture.corpus().attempts().size(), fixture.corpus().scopeIds().size(),
                fixture.recallLimit(), fixture.ks(), outcomes, gates, elapsed);
    }

    private static Path fresh(Path workDir, String prefix) {
        try {
            Files.createDirectories(workDir);
            return Files.createTempDirectory(workDir, prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records the corpus into {@code root}, rebuilds each projection and recalls every query of the set. */
    static Observation observe(RecallStressFixture fixture, Path root) {
        record(fixture, root);
        return recallAll(fixture, root, StressQuery::text);
    }

    /** Records the corpus into {@code root} and rebuilds each project's projection. */
    static void record(RecallStressFixture fixture, Path root) {
        ForgeFixture corpus = fixture.corpus();
        for (String scopeKey : corpus.scopeIds().keySet()) {
            ScopeId scope = ForgeFixtureRecorder.scope(corpus, scopeKey);
            try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
                for (RecordResult r : ForgeFixtureRecorder.recordScope(memory, corpus, scopeKey)) {
                    if (r.status() != RecordResult.Status.APPENDED) {
                        throw new IllegalStateException(scopeKey + ": corpus recording was " + r.status() + " "
                                + r.reason());
                    }
                }
                memory.rebuildProjection(scope);
            }
        }
    }

    /**
     * Recalls every query of the set from the store already recorded in {@code root}, opened read-only, with the
     * text {@code text} gives for it (the query's own text for the stress evaluation).
     */
    static Observation recallAll(RecallStressFixture fixture, Path root, Function<StressQuery, String> text) {
        ForgeFixture corpus = fixture.corpus();
        Map<String, String> state = new LinkedHashMap<>();
        Map<String, List<Observed>> hits = new LinkedHashMap<>();
        Map<String, ExecutionMemory> memories = new LinkedHashMap<>();
        try {
            for (String scopeKey : corpus.scopeIds().keySet()) {
                memories.put(scopeKey, ExecutionMemory.openReadOnly(root, ForgeFixtureRecorder.scope(corpus, scopeKey)));
            }
            // Fixture order, not scope order: the report and the fingerprint follow the query list.
            for (StressQuery q : fixture.queries()) {
                ScopeId scope = ForgeFixtureRecorder.scope(corpus, q.scopeKey());
                ExperienceRecall recall = memories.get(q.scopeKey()).recall(scope, text.apply(q),
                        fixture.recallLimit());
                state.put(q.id(), recall.status().state().name());
                hits.put(q.id(), recall.hits().stream().map(RecallStressEvaluator::observed).toList());
            }
        } finally {
            memories.values().forEach(ExecutionMemory::close);
        }
        return new Observation(state, hits);
    }

    private static Observed observed(ExperienceHit h) {
        return new Observed(ForgeExperienceEvaluator.experienceId(h.ref()), h.ref().scope().value(),
                h.ref().execution().value(), h.ref().revision(), h.similarity());
    }

    /** Classifies the ranking of one query against its judgments and computes its exploratory metrics. */
    static QueryOutcome outcome(ForgeFixture corpus, StressQuery q, String projectionState, List<Observed> observed,
                                List<Integer> ks, int deepestK) {
        Judgments judgments = Judgments.of(corpus, q);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < observed.size(); i++) {
            Observed o = observed.get(i);
            boolean tie = i > 0 && observed.get(i - 1).similarity() == o.similarity();
            hits.add(new Hit(i + 1, o.experienceId(), o.similarity(), tie, neighbour(corpus, judgments, o)));
        }
        List<String> ids = hits.stream().map(Hit::experienceId).toList();
        Optional<Neighbour> lead = hits.isEmpty() || !hits.get(0).neighbour().falseNeighbour() ? Optional.empty()
                : Optional.of(hits.get(0).neighbour());
        if (q.relevant().isEmpty()) {
            Reason reason = !hits.isEmpty() ? Reason.FALSE_NEIGHBOURS_RETURNED
                    : q.text().isBlank() ? Reason.EMPTY_AS_EXPECTED : Reason.NO_HITS;
            return new QueryOutcome(q, projectionState, hits, List.of(), Map.of(), Map.of(), 0.0, reason, lead);
        }
        List<RelevantPosition> positions = new ArrayList<>();
        for (String id : q.relevant()) {
            int index = ids.indexOf(id);
            positions.add(index < 0 ? new RelevantPosition(id, 0, 0.0)
                    : new RelevantPosition(id, index + 1, hits.get(index).similarity()));
        }
        // Retrieved first (by rank), then the unretrieved in judgment order: a stable, readable order.
        positions.sort(java.util.Comparator.comparingInt((RelevantPosition p) -> p.retrieved() ? p.rank()
                : Integer.MAX_VALUE));
        Map<Integer, Double> hitAt = new LinkedHashMap<>();
        Map<Integer, Double> recallAt = new LinkedHashMap<>();
        for (int k : ks) {
            hitAt.put(k, HitAtK.compute(q.relevant(), ids, k));
            recallAt.put(k, RecallAtK.compute(q.relevant(), ids, k));
        }
        return new QueryOutcome(q, projectionState, hits, positions, hitAt, recallAt,
                ReciprocalRank.compute(q.relevant(), ids), reason(positions, deepestK), lead);
    }

    static Reason reason(List<RelevantPosition> positions, int deepestK) {
        int first = positions.stream().filter(RelevantPosition::retrieved).mapToInt(RelevantPosition::rank).min()
                .orElse(0);
        if (first == 0) {
            return Reason.NOT_RETRIEVED;
        }
        if (positions.stream().filter(RelevantPosition::retrieved).allMatch(p -> p.similarity() == 0.0)) {
            return Reason.ZERO_SIMILARITY_ONLY;
        }
        if (first > deepestK) {
            return Reason.NOT_IN_TOP_K;
        }
        if (first > 1) {
            return Reason.OUTRANKED;
        }
        // A zero-similarity fill hit is a tie-order artefact, not a match: it never makes a query RESOLVED.
        boolean allMatchedWithinK = positions.stream()
                .allMatch(p -> p.retrieved() && p.rank() <= deepestK && p.similarity() > 0.0);
        return allMatchedWithinK ? Reason.RESOLVED : Reason.PARTIAL_RECALL;
    }

    /** A query's judgments with the executions and tasks of its relevant experiences, derived once per query. */
    record Judgments(Set<String> relevant, Set<String> confusable, Set<String> relevantExecutions,
                     Set<String> relevantTasks) {
        static Judgments of(ForgeFixture corpus, StressQuery q) {
            Set<String> executions = q.relevant().stream().map(RecallStressFixture::executionOf)
                    .collect(Collectors.toSet());
            Set<String> tasks = executions.stream().map(e -> corpus.execution(e).task()).collect(Collectors.toSet());
            return new Judgments(q.relevant(), q.confusable(), executions, tasks);
        }
    }

    /** The category of one hit, in the precedence order documented on {@link Neighbour}. */
    static Neighbour neighbour(ForgeFixture corpus, Judgments j, Observed hit) {
        if (j.relevant().contains(hit.experienceId())) {
            return Neighbour.RELEVANT;
        }
        if (j.confusable().contains(hit.experienceId())) {
            return Neighbour.JUDGED_CONFUSABLE;
        }
        if (j.relevantExecutions().contains(hit.execution())) {
            return Neighbour.SAME_EXECUTION;
        }
        Optional<String> task = corpus.findExecution(hit.execution()).map(ForgeFixture.Execution::task);
        if (task.isPresent() && j.relevantTasks().contains(task.get())) {
            return Neighbour.SAME_TASK;
        }
        return hit.similarity() > 0.0 ? Neighbour.LEXICAL_NEIGHBOUR : Neighbour.ZERO_SIMILARITY;
    }

    static ForgeGate scopedRecallGate(RecallStressFixture fixture, Observation o) {
        ForgeFixture corpus = fixture.corpus();
        List<String> violations = new ArrayList<>();
        int hits = 0;
        for (StressQuery q : fixture.queries()) {
            String state = o.projectionState().get(q.id());
            if (!state.equals(ProjectionStatus.State.CURRENT.name())) {
                violations.add(q.id() + ": recall projection is " + state + ", not CURRENT");
            }
            String scopeId = corpus.scopeId(q.scopeKey());
            for (Observed h : o.hits().get(q.id())) {
                hits++;
                if (!h.scopeId().equals(scopeId)) {
                    violations.add(q.id() + ": foreign-scope hit " + h.experienceId() + " in " + h.scopeId());
                }
                Optional<ForgeFixture.Execution> e = corpus.findExecution(h.execution());
                if (e.isEmpty() || !e.get().scopeKey().equals(q.scopeKey())) {
                    violations.add(q.id() + ": hit " + h.experienceId() + " is not an experience of project "
                            + q.scopeKey());
                }
            }
        }
        return ForgeGate.of(RecallStressReport.SCOPED_RECALL, "every recall is CURRENT and stays in its project",
                violations, List.of("queries: " + fixture.queries().size() + "; hits checked: " + hits));
    }

    static ForgeGate blankQueryGate(RecallStressFixture fixture, Observation o) {
        List<String> violations = new ArrayList<>();
        List<String> blank = new ArrayList<>();
        for (StressQuery q : fixture.queries()) {
            if (q.text().isBlank()) {
                blank.add(q.id());
                int n = o.hits().get(q.id()).size();
                if (n != 0) {
                    violations.add(q.id() + ": blank query returned " + n + " hits");
                }
            }
        }
        if (blank.isEmpty()) {
            violations.add("the set has no blank query, so the contract is not exercised");
        }
        return ForgeGate.of(RecallStressReport.BLANK_QUERY, "empty and whitespace-only queries return no hit",
                violations, List.of("blank queries: " + String.join(", ", blank)));
    }

    static ForgeGate determinismGate(Observation first, Observation second) {
        List<String> violations = new ArrayList<>();
        if (!first.fingerprint().equals(second.fingerprint())) {
            first.hits().forEach((id, hits) -> {
                if (!hits.equals(second.hits().get(id))) {
                    violations.add(id + ": ranking differs between two fresh stores");
                }
            });
            if (violations.isEmpty()) {
                violations.add("projection states differ between two fresh stores");
            }
        }
        return ForgeGate.of(RecallStressReport.DETERMINISM,
                "two fresh stores give identical rankings, revisions and similarities", violations,
                List.of("compared " + first.hits().size() + " rankings"));
    }

    static ForgeGate baselineParityGate(RecallStressFixture fixture, Observation o, ForgeEvaluationReport baseline) {
        List<String> violations = new ArrayList<>();
        if (!baseline.allGatesPassed()) {
            violations.add("the #102 evaluation failed gates " + baseline.gates().stream().filter(g -> !g.passed())
                    .map(ForgeGate::id).toList());
        }
        Map<String, ForgeQueryResult> byId = new LinkedHashMap<>();
        baseline.queries().forEach(r -> byId.put(r.query().id(), r));
        List<StressQuery> baselineQueries = fixture.queriesOf(Category.BASELINE);
        for (StressQuery q : baselineQueries) {
            ForgeQueryResult expected = byId.get(q.id());
            if (expected == null) {
                violations.add(q.id() + ": missing from the #102 report");
                continue;
            }
            List<String> want = expected.ranked().stream().map(h -> h.experienceId() + ":" + h.similarity()).toList();
            List<String> got = o.hits().get(q.id()).stream().map(h -> h.experienceId() + ":" + h.similarity())
                    .toList();
            if (!want.equals(got)) {
                violations.add(q.id() + ": ranking differs from the #102 evaluation");
            }
        }
        if (baselineQueries.size() != baseline.queries().size()) {
            violations.add("baseline group has " + baselineQueries.size() + " queries, #102 reports "
                    + baseline.queries().size());
        }
        long passed = baseline.gates().stream().filter(ForgeGate::passed).count();
        return ForgeGate.of(RecallStressReport.BASELINE_PARITY,
                "the #102 evaluation passes and its rankings equal this runner's (same code: a regression "
                        + "against main needs the before/after report diff)", violations,
                List.of("#102 decision: " + baseline.decision() + " (" + passed + "/" + baseline.gates().size()
                        + " gates); baseline rankings compared with the #102 pipeline: " + baselineQueries.size()));
    }
}
