package com.monada.evaluation.execution;

import com.monada.evaluation.execution.QueryExpansion.Expansion;
import com.monada.evaluation.execution.QueryExpansion.Risk;
import com.monada.evaluation.execution.QueryExpansionReport.Candidate;
import com.monada.evaluation.execution.QueryExpansionReport.CandidateResult;
import com.monada.evaluation.execution.QueryExpansionReport.Effect;
import com.monada.evaluation.execution.QueryExpansionReport.QueryComparison;
import com.monada.evaluation.execution.QueryExpansionReport.Verdict;
import com.monada.evaluation.execution.QueryExpansionReport.Veto;
import com.monada.evaluation.execution.RecallStressEvaluator.Observation;
import com.monada.evaluation.execution.RecallStressEvaluator.Observed;
import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import com.monada.evaluation.execution.RecallStressReport.Hit;
import com.monada.evaluation.execution.RecallStressReport.Neighbour;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Offline A/B comparison of exact queries against the bounded query-side expansion of issue #124. The corpus is
 * recorded once into a scratch store; every query is then recalled, through the public read-only API, with its exact
 * text and with each candidate's expanded text, on the SAME persisted projection. Nothing is configured on recall and
 * nothing is written: the store files are fingerprinted before and after every candidate recall.
 *
 * <p>Judgments are always those of the source query. A candidate's verdict is exploratory and vetoed by any
 * regression on protected or stress queries, any judged confusable rising into the top K and any new false sharing on
 * queries with nothing relevant; the best verdict is {@link Verdict#JUSTIFIED_FOR_FOLLOW_UP}, never adoption.
 */
public final class QueryExpansionEvaluator {
    /** Categories where any loss vetoes a candidate on its own: the #102 queries and the collision stress groups. */
    static final Set<Category> PROTECTED = EnumSet.of(Category.BASELINE, Category.NEGATION, Category.RELATION,
            Category.DUPLICATE_SUMMARY);

    private QueryExpansionEvaluator() {
    }

    /** The declared candidates: every rule, and only the rules declared LOW risk. */
    static List<Candidate> declaredCandidates(QueryExpansion table) {
        return List.of(
                new Candidate("FULL", "every rule of the table, HIGH-risk rules included",
                        ruleIds(table, r -> true)),
                new Candidate("LOW_RISK", "only the rules declared LOW risk before the comparison ran",
                        ruleIds(table, r -> r.risk() == Risk.LOW)));
    }

    /** One isolated candidate per rule, to attribute effects without interaction. */
    static List<Candidate> ruleCandidates(QueryExpansion table) {
        return table.rules().stream().map(r -> new Candidate(r.id(), r.source() + " -> " + String.join(" ", r.added()),
                Set.of(r.id()))).toList();
    }

    private static Set<String> ruleIds(QueryExpansion table, java.util.function.Predicate<QueryExpansion.Rule> p) {
        return table.rules().stream().filter(p).map(QueryExpansion.Rule::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Evaluates {@code table} on {@code fixture} with stores created below {@code workDir} (created if missing). Every
     * call uses its own fresh subdirectories; the caller owns {@code workDir} and removes it.
     */
    public static QueryExpansionReport evaluate(RecallStressFixture fixture, QueryExpansion table, Path workDir) {
        Map<String, Long> elapsed = new LinkedHashMap<>();
        long t = System.nanoTime();
        RecallStressReport stress = RecallStressEvaluator.evaluate(fixture, workDir);
        elapsed.put("stress evaluation (#122)", System.nanoTime() - t);

        t = System.nanoTime();
        Path store = fresh(workDir, "expansion-store-");
        RecallStressEvaluator.record(fixture, store);
        String before = fingerprint(store);
        Observation exact = RecallStressEvaluator.recallAll(fixture, store, StressQuery::text);
        List<Candidate> declared = declaredCandidates(table);
        List<Candidate> isolated = ruleCandidates(table);
        Map<Candidate, Map<String, Expansion>> expansions = new LinkedHashMap<>();
        Map<Candidate, Observation> observed = new LinkedHashMap<>();
        for (Candidate c : Stream.concat(declared.stream(), isolated.stream()).toList()) {
            Map<String, Expansion> e = expand(fixture, table, c);
            expansions.put(c, e);
            observed.put(c, RecallStressEvaluator.recallAll(fixture, store, q -> e.get(q.id()).expanded()));
        }
        String after = fingerprint(store);
        elapsed.put("exact + candidate recall", System.nanoTime() - t);

        t = System.nanoTime();
        Path second = fresh(workDir, "expansion-store-");
        RecallStressEvaluator.record(fixture, second);
        Map<Candidate, Observation> repeated = new LinkedHashMap<>();
        for (Candidate c : declared) {
            Map<String, Expansion> e = expansions.get(c);
            repeated.put(c, RecallStressEvaluator.recallAll(fixture, second, q -> e.get(q.id()).expanded()));
        }
        elapsed.put("determinism store", System.nanoTime() - t);

        Map<String, QueryOutcome> exactOutcomes = outcomes(fixture, exact);
        List<CandidateResult> declaredResults = declared.stream()
                .map(c -> result(fixture, c, expansions.get(c), exactOutcomes, outcomes(fixture, observed.get(c))))
                .toList();
        List<CandidateResult> ruleResults = isolated.stream()
                .map(c -> result(fixture, c, expansions.get(c), exactOutcomes, outcomes(fixture, observed.get(c))))
                .toList();
        List<ForgeGate> gates = List.of(stressChecksGate(stress), exactParityGate(fixture, stress, exact),
                identityGate(fixture, exact, declared, expansions, observed),
                boundsGate(fixture, table, expansions), storeUnchangedGate(before, after),
                determinismGate(declared, observed, repeated));
        return new QueryExpansionReport(table, stress, declaredResults, ruleResults, gates, elapsed);
    }

    private static Path fresh(Path workDir, String prefix) {
        try {
            Files.createDirectories(workDir);
            return Files.createTempDirectory(workDir, prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Expansion> expand(RecallStressFixture fixture, QueryExpansion table, Candidate c) {
        Map<String, Expansion> out = new LinkedHashMap<>();
        fixture.queries().forEach(q -> out.put(q.id(), table.expand(q.text(), r -> c.ruleIds().contains(r.id()))));
        return out;
    }

    private static Map<String, QueryOutcome> outcomes(RecallStressFixture fixture, Observation o) {
        int deepest = fixture.ks().get(fixture.ks().size() - 1);
        Map<String, QueryOutcome> out = new LinkedHashMap<>();
        for (StressQuery q : fixture.queries()) {
            out.put(q.id(), RecallStressEvaluator.outcome(fixture.corpus(), q, o.projectionState().get(q.id()),
                    o.hits().get(q.id()), fixture.ks(), deepest));
        }
        return out;
    }

    static CandidateResult result(RecallStressFixture fixture, Candidate c, Map<String, Expansion> expansions,
                                  Map<String, QueryOutcome> exact, Map<String, QueryOutcome> candidate) {
        int k = fixture.ks().get(fixture.ks().size() - 1);
        List<QueryComparison> rows = new ArrayList<>();
        for (StressQuery q : fixture.queries()) {
            rows.add(compare(expansions.get(q.id()), exact.get(q.id()), candidate.get(q.id()), fixture.ks().get(0), k));
        }
        List<Veto> vetoes = vetoes(rows, k);
        return new CandidateResult(c, rows, vetoes, verdict(rows, vetoes));
    }

    /**
     * Compares one query's exact and candidate rankings: metric deltas, the effect, the false sharing (non-relevant
     * positive-similarity hits that enter the candidate top {@code k}) and the judged confusables that rise to a
     * better rank within the candidate top {@code k}.
     */
    static QueryComparison compare(Expansion expansion, QueryOutcome exact, QueryOutcome candidate, int firstK,
                                   int k) {
        List<String> exactTop = exact.rankedIds().stream().limit(k).toList();
        List<Hit> sharing = candidate.hits().stream().limit(k)
                .filter(h -> h.neighbour() != Neighbour.RELEVANT && h.similarity() > 0.0)
                .filter(h -> !exactTop.contains(h.experienceId())).toList();
        List<String> rises = new ArrayList<>();
        for (String id : exact.query().confusable()) {
            int before = rank(exact, id);
            int after = rank(candidate, id);
            if (after <= k && after < before) {
                rises.add(id + " " + (before == Integer.MAX_VALUE ? "not retrieved" : "#" + before) + " -> #" + after);
            }
        }
        Effect effect = Effect.NOT_SCORED;
        if (exact.scored()) {
            double[] d = {candidate.hitAt().get(firstK) - exact.hitAt().get(firstK),
                    candidate.reciprocalRank() - exact.reciprocalRank(),
                    candidate.recallAt().get(k) - exact.recallAt().get(k)};
            boolean up = false;
            boolean down = false;
            for (double v : d) {
                up |= v > 0.0;
                down |= v < 0.0;
            }
            effect = up && down ? Effect.MIXED : up ? Effect.IMPROVED : down ? Effect.REGRESSED : Effect.UNCHANGED;
        }
        return new QueryComparison(expansion, exact, candidate, effect, sharing, rises);
    }

    private static int rank(QueryOutcome o, String experienceId) {
        int index = o.rankedIds().indexOf(experienceId);
        return index < 0 ? Integer.MAX_VALUE : index + 1;
    }

    /**
     * The vetoes of one candidate. V1: a scored query of a protected category loses Hit@1, RR or Recall@K. V2: any
     * other scored query loses RR or Recall@K. V3: a judged confusable rises within the top K. V4: a non-blank query
     * with nothing relevant gets a new top-K neighbour, or a top-K wrong match with a higher similarity than under
     * exact recall (false sharing or a strengthened collision).
     */
    static List<Veto> vetoes(List<QueryComparison> rows, int k) {
        List<Veto> out = new ArrayList<>();
        for (QueryComparison r : rows) {
            StressQuery q = r.exact().query();
            if (r.exact().scored() && (r.effect() == Effect.REGRESSED || r.effect() == Effect.MIXED)) {
                out.add(new Veto(PROTECTED.contains(q.category()) ? "V1" : "V2", q.id(), r.deltas()));
            }
            if (!r.confusableRises().isEmpty()) {
                out.add(new Veto("V3", q.id(), "judged confusable " + String.join(", ", r.confusableRises())));
            }
            if (!r.exact().scored() && !q.text().isBlank()) {
                List<String> strengthened = strengthened(r, k);
                if (!r.falseSharing().isEmpty() || !strengthened.isEmpty()) {
                    out.add(new Veto("V4", q.id(), ForgeEvaluationReport.fmt("new top-K neighbours: %s; "
                            + "strengthened wrong matches: %s", r.falseSharing().isEmpty() ? "none"
                            : r.falseSharing().stream().map(Hit::experienceId).collect(Collectors.joining(", ")),
                            strengthened.isEmpty() ? "none" : String.join(", ", strengthened))));
                }
            }
        }
        return out;
    }

    /** Candidate top-K hits whose similarity is higher than under exact recall (0 when exact did not return them). */
    private static List<String> strengthened(QueryComparison r, int k) {
        List<String> out = new ArrayList<>();
        Map<String, Double> before = new LinkedHashMap<>();
        r.exact().hits().forEach(h -> before.put(h.experienceId(), h.similarity()));
        for (Hit h : r.candidate().hits().subList(0, Math.min(r.candidate().hits().size(), k))) {
            double was = before.getOrDefault(h.experienceId(), 0.0);
            if (h.similarity() > was) {
                out.add(ForgeEvaluationReport.fmt("%s %.4f -> %.4f", h.experienceId(), was, h.similarity()));
            }
        }
        return out;
    }

    /** Any veto rejects; without a veto, at least one improved scored query is needed. Never an adoption. */
    static Verdict verdict(List<QueryComparison> rows, List<Veto> vetoes) {
        if (!vetoes.isEmpty()) {
            return Verdict.REJECTED;
        }
        return rows.stream().anyMatch(r -> r.effect() == Effect.IMPROVED) ? Verdict.JUSTIFIED_FOR_FOLLOW_UP
                : Verdict.NOT_JUSTIFIED;
    }

    /** SHA-256 of every regular file below {@code root}, by sorted relative path, with its size. */
    static String fingerprint(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            StringBuilder out = new StringBuilder();
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.append(root.relativize(p).toString().replace('\\', '/')).append(' ').append(Files.size(p))
                        .append(' ').append(sha256(p)).append('\n');
            }
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file),
                MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ForgeGate stressChecksGate(RecallStressReport stress) {
        List<String> violations = stress.failedGates().stream().map(id -> "stress check " + id + " failed").toList();
        return ForgeGate.of(QueryExpansionReport.STRESS_CHECKS,
                "the #122 exact rankings pass RS1-RS4 (scoped, blank, deterministic, #102 parity)", violations,
                List.of("stress decision: " + stress.decision()));
    }

    static ForgeGate exactParityGate(RecallStressFixture fixture, RecallStressReport stress, Observation exact) {
        List<String> violations = new ArrayList<>();
        for (StressQuery q : fixture.queries()) {
            List<String> want = stress.outcome(q.id()).hits().stream()
                    .map(h -> h.experienceId() + ":" + h.similarity()).toList();
            List<String> got = exact.hits().get(q.id()).stream().map(h -> h.experienceId() + ":" + h.similarity())
                    .toList();
            if (!want.equals(got)) {
                violations.add(q.id() + ": exact ranking on the scratch store differs from the #122 report");
            }
        }
        return ForgeGate.of(QueryExpansionReport.EXACT_PARITY,
                "without expansion the scratch store ranks every query exactly like the #122 evaluation", violations,
                List.of("queries compared: " + fixture.queries().size()));
    }

    /** Queries no rule changed (blank ones included) are recalled unchanged and rank identically to exact. */
    static ForgeGate identityGate(RecallStressFixture fixture, Observation exact, List<Candidate> candidates,
                                  Map<Candidate, Map<String, Expansion>> expansions,
                                  Map<Candidate, Observation> observed) {
        List<String> violations = new ArrayList<>();
        int unchanged = 0;
        for (Candidate c : candidates) {
            for (StressQuery q : fixture.queries()) {
                Expansion e = expansions.get(c).get(q.id());
                List<Observed> got = observed.get(c).hits().get(q.id());
                if (q.text().isBlank() && (e.changed() || !got.isEmpty())) {
                    violations.add(c.name() + " " + q.id() + ": a blank query was expanded or returned hits");
                }
                if (!e.changed()) {
                    unchanged++;
                    if (!e.expanded().equals(q.text())) {
                        violations.add(c.name() + " " + q.id() + ": text changed although nothing was added");
                    }
                    if (!got.equals(exact.hits().get(q.id()))) {
                        violations.add(c.name() + " " + q.id() + ": unexpanded query ranks differently from exact");
                    }
                }
            }
        }
        return ForgeGate.of(QueryExpansionReport.IDENTITY,
                "a query no rule changes (blank ones included) is sent unchanged and ranks exactly as without the "
                        + "candidate", violations, List.of("unchanged query recalls compared: " + unchanged));
    }

    /** Checked independently of {@link QueryExpansion#expand}: the expansion is bounded and only adds table terms. */
    static ForgeGate boundsGate(RecallStressFixture fixture, QueryExpansion table,
                                Map<Candidate, Map<String, Expansion>> expansions) {
        List<String> violations = new ArrayList<>();
        int changed = 0;
        int maxAdded = 0;
        for (Map.Entry<Candidate, Map<String, Expansion>> entry : expansions.entrySet()) {
            Candidate c = entry.getKey();
            for (StressQuery q : fixture.queries()) {
                Expansion e = entry.getValue().get(q.id());
                String where = c.name() + " " + q.id();
                Set<String> allowed = new LinkedHashSet<>();
                for (String id : e.fired()) {
                    if (!c.ruleIds().contains(id)) {
                        violations.add(where + ": rule " + id + " is not part of the candidate");
                    }
                    allowed.addAll(table.rule(id).added());
                }
                String expected = e.added().isEmpty() ? q.text() : q.text() + " " + String.join(" ", e.added());
                if (!e.source().equals(q.text()) || !e.expanded().equals(expected)) {
                    violations.add(where + ": the expanded text is not the source followed by the added terms");
                }
                if (e.added().size() > table.maxAddedTermsPerQuery()) {
                    violations.add(where + ": " + e.added().size() + " terms added, cap "
                            + table.maxAddedTermsPerQuery());
                }
                List<String> sourceTokens = QueryExpansion.tokens(q.text());
                for (String term : e.added()) {
                    if (!allowed.contains(term) || sourceTokens.contains(term)) {
                        violations.add(where + ": added term " + term + " is not a new term of a fired rule");
                    }
                }
                if (e.changed()) {
                    changed++;
                }
                maxAdded = Math.max(maxAdded, e.added().size());
            }
        }
        return ForgeGate.of(QueryExpansionReport.BOUNDS,
                "every expansion keeps the source text verbatim and appends at most " + table.maxAddedTermsPerQuery()
                        + " new terms of fired candidate rules", violations,
                List.of("rules: " + table.rules().size() + " (cap " + table.maxRules() + "); expanded query texts: "
                        + changed + "; most terms added to one query: " + maxAdded));
    }

    static ForgeGate storeUnchangedGate(String before, String after) {
        List<String> violations = before.equals(after) ? List.of()
                : List.of("store files changed while exact and candidate queries were recalled");
        return ForgeGate.of(QueryExpansionReport.STORE_UNCHANGED,
                "candidate recall reads the persisted projection only: every store file is byte-identical after it",
                violations, List.of("files fingerprinted: " + before.lines().count()));
    }

    static ForgeGate determinismGate(List<Candidate> candidates, Map<Candidate, Observation> first,
                                     Map<Candidate, Observation> second) {
        List<String> violations = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!first.get(c).fingerprint().equals(second.get(c).fingerprint())) {
                violations.add(c.name() + ": candidate rankings differ between two fresh stores");
            }
        }
        return ForgeGate.of(QueryExpansionReport.DETERMINISM,
                "two fresh stores give identical candidate rankings, revisions and similarities", violations,
                List.of("candidates compared: " + candidates.stream().map(Candidate::name)
                        .collect(Collectors.joining(", "))));
    }
}
