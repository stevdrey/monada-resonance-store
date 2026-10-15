package com.monada.evaluation.execution;

import com.monada.core.execution.QualityDimension;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The versioned, synthetic and manually judged multi-attempt corpus (issue #121). It closes the representativeness
 * gap of {@code forge-experience-v1}: retries live inside one execution (failed then repaired, abandoned then
 * accepted, unfinished, corrected), independent executions share identical summaries, and both projects hold the
 * same summary. Every judged value comes from resource files under {@code forge-multi-attempt-v1/}; nothing is
 * produced by a model or a provider, and nothing comes from a real repository.
 *
 * <p>Labels stay per execution: {@link Execution#expectedLabel} is the raw label the export must carry for every
 * attempt of the execution. What an attempt did ({@link Attempt#latestResult}) and whether it is the validated
 * accepted attempt ({@link Attempt#judgedAccepted}) are judged separately, so a failed attempt of a {@code SUCCESS}
 * execution is never an accepted attempt.
 */
public record MultiAttemptFixture(String version, String provenance, Map<String, String> scopeIds,
                                  ForgeFixture.Price price, int recallLimit, int pageSize, String cutoffScope,
                                  String cutoffEvent, List<Execution> executions, List<Attempt> attempts,
                                  List<Query> queries, List<CutoffAttempt> cutoff) {

    static final String RESOURCE_ROOT = "/com/monada/evaluation/execution/forge-multi-attempt-v1/";
    /** Result of an attempt that was started and never finished: it has no {@code ATTEMPT_FINISHED} fact. */
    static final String UNFINISHED = "UNFINISHED";
    static final int MIN_MULTI_ATTEMPT_SUCCESSES = 3;
    private static final Set<String> RESULTS = Set.of("COMPLETED", "FAILED", "CANCELLED", UNFINISHED);

    public enum OutcomeKind { ACCEPTED_VALIDATED, ACCEPTED_IMPORTED, REJECTED_VALIDATED, CANCELLED, NONE }

    public record Execution(String id, String scopeKey, String task, String summary, String sourceRevision,
                            String contextFingerprint, String constraintsFingerprint,
                            Set<QualityDimension> mandatory, OutcomeKind outcome, int outcomeAttempt,
                            String expectedLabel, String expectedAcceptance, List<String> expectedReasons) {
        /** True when the recorded outcome names an accepted attempt, whatever its origin. */
        boolean namesAttempt(int ordinal) {
            return (outcome == OutcomeKind.ACCEPTED_VALIDATED || outcome == OutcomeKind.ACCEPTED_IMPORTED)
                    && outcomeAttempt == ordinal;
        }
    }

    /**
     * One attempt. {@code result} is the originally recorded result ({@link #UNFINISHED} for an attempt without a
     * finish); a revision-2 correction may replace result, solution and lesson ({@code revised*}, else null).
     */
    public record Attempt(String execution, int ordinal, String reason, String result, String solution,
                          String lesson, long inputTokens, long outputTokens, long startSeconds, long endSeconds,
                          Map<QualityDimension, String> evidence, String revisedResult, String revisedSolution,
                          String revisedLesson, boolean judgedAccepted) {
        public String experienceId() {
            return execution + "/a" + ordinal;
        }

        public boolean finished() {
            return !result.equals(UNFINISHED);
        }

        public boolean revised() {
            return revisedResult != null;
        }

        /** Result of the latest revision of the finish, or {@link #UNFINISHED}. */
        public String latestResult() {
            return revised() ? revisedResult : result;
        }

        public String latestSolution() {
            return revised() ? revisedSolution : solution;
        }

        /** Revision of the effective finish: 2 when corrected, 1 otherwise, 0 for an unfinished attempt. */
        public int latestRevision() {
            return !finished() ? 0 : revised() ? 2 : 1;
        }
    }

    public record Query(String id, String scopeKey, String text, Set<String> relevant, String judgment) {
    }

    /**
     * Hand-judged state of one attempt in the cutoff export ({@code revision} 0 = no finish at the cutoff).
     * {@code markedAccepted} is the raw marker (the outcome recorded at the cutoff names this attempt);
     * {@code accepted} is the validated accepted attempt verdict. Both are judged and checked separately.
     */
    public record CutoffAttempt(String execution, int ordinal, String result, int revision, boolean markedAccepted,
                                boolean accepted, String label, String acceptance) {
        public String experienceId() {
            return execution + "/a" + ordinal;
        }
    }

    public MultiAttemptFixture {
        // Map.copyOf iterates in a per-JVM random order; the report iterates scopes, so keep fixture order.
        scopeIds = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(scopeIds));
        executions = List.copyOf(executions);
        attempts = List.copyOf(attempts);
        queries = List.copyOf(queries);
        cutoff = List.copyOf(cutoff);
    }

    public String scopeId(String scopeKey) {
        String id = scopeIds.get(scopeKey);
        if (id == null) {
            throw new IllegalArgumentException("unknown scope key: " + scopeKey);
        }
        return id;
    }

    /** The execution with that id, for ids that come from the fixture itself (an unknown id is a fixture bug). */
    public Execution execution(String id) {
        return findExecution(id).orElseThrow(() -> new IllegalArgumentException("unknown fixture execution: " + id));
    }

    /** The execution with that id, or empty: ids from an export or a recall may be unknown and must be reported. */
    public Optional<Execution> findExecution(String id) {
        return executions.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** The attempt with that experience id ({@code <execution>/a<ordinal>}), or empty. */
    public Optional<Attempt> findAttempt(String experienceId) {
        return attempts.stream().filter(a -> a.experienceId().equals(experienceId)).findFirst();
    }

    public List<Attempt> attemptsOf(String execution) {
        return attempts.stream().filter(a -> a.execution().equals(execution)).toList();
    }

    public List<Execution> executionsOf(String scopeKey) {
        return executions.stream().filter(e -> e.scopeKey().equals(scopeKey)).toList();
    }

    /** Loads and validates the version-1 corpus from the module resources. */
    public static MultiAttemptFixture load() {
        Properties manifest = properties("manifest.properties");
        Map<String, String> scopes = new LinkedHashMap<>();
        scopes.put("alpha", require(manifest, "scope.alpha"));
        scopes.put("beta", require(manifest, "scope.beta"));
        ForgeFixture.Price price = new ForgeFixture.Price(require(manifest, "price.id"),
                require(manifest, "price.version"), require(manifest, "price.provider"),
                require(manifest, "price.model"), require(manifest, "price.currency"),
                LocalDate.parse(require(manifest, "price.effective")), new BigDecimal(require(manifest, "price.input")),
                new BigDecimal(require(manifest, "price.output")), Long.parseLong(require(manifest, "price.unitSize")));

        List<Execution> executions = new ArrayList<>();
        for (String[] f : rows("executions.psv", 12)) {
            String[] outcome = f[8].split(":");
            executions.add(new Execution(f[0], f[1], f[2], f[3], f[4], f[5], f[6], dimensions(f[7]),
                    OutcomeKind.valueOf(outcome[0]), outcome.length > 1 ? Integer.parseInt(outcome[1]) : 0,
                    f[9], f[10], csv(f[11])));
        }
        List<Attempt> attempts = new ArrayList<>();
        for (String[] f : rows("attempts.psv", 15)) {
            attempts.add(new Attempt(f[0], Integer.parseInt(f[1]), f[2], f[3], dash(f[4]), dash(f[5]),
                    Long.parseLong(f[6]), Long.parseLong(f[7]), Long.parseLong(f[8]), Long.parseLong(f[9]),
                    evidence(f[10]), dash(f[11]), dash(f[12]), dash(f[13]), yesNo(f[14])));
        }
        List<Query> queries = new ArrayList<>();
        for (String[] f : rows("queries.psv", 5)) {
            queries.add(new Query(f[0], f[1], f[2], new LinkedHashSet<>(csv(f[3])), f[4]));
        }
        List<CutoffAttempt> cutoff = new ArrayList<>();
        for (String[] f : rows("cutoff.psv", 8)) {
            cutoff.add(new CutoffAttempt(f[0], Integer.parseInt(f[1]), f[2], f[3].equals("-") ? 0
                    : Integer.parseInt(f[3]), yesNo(f[4]), yesNo(f[5]), f[6], f[7]));
        }
        MultiAttemptFixture fixture = new MultiAttemptFixture(require(manifest, "fixtureVersion"),
                require(manifest, "provenance"), scopes, price, Integer.parseInt(require(manifest, "recall.limit")),
                Integer.parseInt(require(manifest, "export.pageSize")), require(manifest, "cutoff.scope"),
                require(manifest, "cutoff.event"), executions, attempts, queries, cutoff);
        fixture.validate();
        return fixture;
    }

    /** Structural validation: consistent references, judged values that agree with each other, required scenarios. */
    void validate() {
        if (scopeIds.size() < 2 || scopeIds.values().stream().distinct().count() < 2) {
            throw new IllegalStateException("fixture needs two distinct projects");
        }
        unique(executions.stream().map(Execution::id).toList(), "execution");
        unique(attempts.stream().map(Attempt::experienceId).toList(), "attempt");
        unique(queries.stream().map(Query::id).toList(), "query");
        unique(cutoff.stream().map(CutoffAttempt::experienceId).toList(), "cutoff attempt");
        for (Attempt a : attempts) {
            execution(a.execution());
            if (!RESULTS.contains(a.result())) {
                throw new IllegalStateException(a.experienceId() + ": unknown result " + a.result());
            }
            if (a.finished() && (a.solution() == null || a.lesson() == null)) {
                throw new IllegalStateException(a.experienceId() + ": a finished attempt needs a solution and a "
                        + "lesson");
            }
            if (!a.finished() && (a.solution() != null || a.lesson() != null)) {
                throw new IllegalStateException(a.experienceId() + ": an unfinished attempt has neither a solution "
                        + "nor a lesson");
            }
            if (a.revised() && (!a.finished() || a.revisedResult().equals(UNFINISHED)
                    || !RESULTS.contains(a.revisedResult()) || a.revisedSolution() == null
                    || a.revisedLesson() == null)) {
                throw new IllegalStateException(a.experienceId() + ": a correction revises a finished attempt with a "
                        + "finished result, a solution and a lesson");
            }
        }
        for (Execution e : executions) {
            scopeId(e.scopeKey());
            List<Attempt> own = attemptsOf(e.id());
            if (own.isEmpty()) {
                throw new IllegalStateException("execution without attempts: " + e.id());
            }
            for (int i = 0; i < own.size(); i++) {
                if (own.get(i).ordinal() != i + 1) {
                    throw new IllegalStateException("attempt ordinals of " + e.id() + " must be 1..n in order");
                }
                if (!own.get(i).finished() && i != own.size() - 1) {
                    throw new IllegalStateException(e.id() + ": only the last attempt may be unfinished");
                }
            }
            boolean names = e.outcome() == OutcomeKind.ACCEPTED_VALIDATED || e.outcome() == OutcomeKind.ACCEPTED_IMPORTED;
            if (names && own.stream().noneMatch(a -> a.ordinal() == e.outcomeAttempt())) {
                throw new IllegalStateException("outcome names a missing attempt: " + e.id());
            }
            boolean learnable = e.expectedLabel().equals("SUCCESS") || e.expectedLabel().equals("FAILURE");
            if (learnable != e.expectedReasons().isEmpty()) {
                throw new IllegalStateException("execution " + e.id() + ": eligibility reasons must be given exactly "
                        + "for samples that are not SUCCESS or FAILURE");
            }
            long accepted = own.stream().filter(Attempt::judgedAccepted).count();
            boolean validated = e.expectedAcceptance().equals("VALIDATED_ACCEPTED");
            if (accepted != (validated ? 1 : 0)) {
                throw new IllegalStateException(e.id() + ": exactly one judged accepted attempt is required for a "
                        + "VALIDATED_ACCEPTED execution and none otherwise, found " + accepted);
            }
            for (Attempt a : own) {
                if (a.judgedAccepted() && (e.outcome() != OutcomeKind.ACCEPTED_VALIDATED
                        || a.ordinal() != e.outcomeAttempt() || !a.latestResult().equals("COMPLETED"))) {
                    throw new IllegalStateException(a.experienceId() + ": a judged accepted attempt must be the "
                            + "COMPLETED attempt a validated acceptance names");
                }
            }
        }
        for (Query q : queries) {
            scopeId(q.scopeKey());
            if (q.relevant().isEmpty()) {
                throw new IllegalStateException("query " + q.id() + " has no judged relevant attempt");
            }
            for (String r : q.relevant()) {
                Attempt a = findAttempt(r).orElseThrow(() -> new IllegalStateException("query " + q.id()
                        + " names an unknown attempt: " + r));
                if (!a.finished()) {
                    throw new IllegalStateException("query " + q.id() + " judges an unfinished attempt relevant: " + r);
                }
                if (!execution(a.execution()).scopeKey().equals(q.scopeKey())) {
                    throw new IllegalStateException("query " + q.id() + " judges an attempt of another project: " + r);
                }
            }
        }
        validateCutoff();
        validateScenarios();
    }

    private void validateCutoff() {
        scopeId(cutoffScope);
        String[] parts = cutoffEvent.split("\\.");
        if (parts.length != 3 || !parts[2].equals("fin")) {
            throw new IllegalStateException("cutoff event must be an attempt finish <execution>.a<n>.fin: " + cutoffEvent);
        }
        Attempt at = findAttempt(parts[0] + "/" + parts[1])
                .orElseThrow(() -> new IllegalStateException("cutoff event names an unknown attempt: " + cutoffEvent));
        if (!at.finished() || !execution(at.execution()).scopeKey().equals(cutoffScope)) {
            throw new IllegalStateException("cutoff event must be a finish recorded in the cutoff scope: " + cutoffEvent);
        }
        if (cutoff.isEmpty()) {
            throw new IllegalStateException("fixture needs a judged cutoff state");
        }
        for (CutoffAttempt c : cutoff) {
            Attempt a = findAttempt(c.experienceId()).orElseThrow(() -> new IllegalStateException(
                    "cutoff row names an unknown attempt: " + c.experienceId()));
            if (!execution(a.execution()).scopeKey().equals(cutoffScope)) {
                throw new IllegalStateException("cutoff row names an attempt of another project: " + c.experienceId());
            }
            if (!RESULTS.contains(c.result()) || c.result().equals(UNFINISHED) != (c.revision() == 0)) {
                throw new IllegalStateException("cutoff row " + c.experienceId() + ": an unfinished attempt has no "
                        + "finish revision, a finished one has one");
            }
            if (c.markedAccepted() && !execution(a.execution()).namesAttempt(a.ordinal())) {
                throw new IllegalStateException("cutoff row " + c.experienceId() + ": marked as named by the outcome, "
                        + "but its execution's outcome never names it");
            }
            if (c.accepted() && !c.markedAccepted()) {
                throw new IllegalStateException("cutoff row " + c.experienceId() + ": a validated accepted attempt "
                        + "must be the attempt the outcome names");
            }
        }
    }

    /** The scenarios issue #121 requires; a fixture without them could pass every gate vacuously. */
    private void validateScenarios() {
        long multiAttemptSuccesses = executions.stream().filter(e -> e.expectedLabel().equals("SUCCESS"))
                .filter(e -> attemptsOf(e.id()).stream().anyMatch(a -> !a.judgedAccepted() && a.finished()
                        && !a.latestResult().equals("COMPLETED")))
                .count();
        if (multiAttemptSuccesses < MIN_MULTI_ATTEMPT_SUCCESSES) {
            throw new IllegalStateException("fixture needs at least " + MIN_MULTI_ATTEMPT_SUCCESSES + " SUCCESS "
                    + "executions with a failed or cancelled earlier attempt, has " + multiAttemptSuccesses);
        }
        require(attempts.stream().anyMatch(a -> a.result().equals("CANCELLED")), "an abandoned (cancelled) attempt");
        require(attempts.stream().anyMatch(a -> !a.finished()), "an unfinished attempt");
        require(attempts.stream().anyMatch(a -> a.revised() && !a.revisedResult().equals(a.result())),
                "a correction that changes the attempt result");
        Set<String> sameScope = new LinkedHashSet<>();
        Set<String> crossScope = new LinkedHashSet<>();
        for (int i = 0; i < executions.size(); i++) {
            for (int j = i + 1; j < executions.size(); j++) {
                Execution a = executions.get(i);
                Execution b = executions.get(j);
                if (a.summary().equals(b.summary())) {
                    (a.scopeKey().equals(b.scopeKey()) ? sameScope : crossScope).add(a.id() + "|" + b.id());
                }
            }
        }
        require(!sameScope.isEmpty(), "two independent executions of one project with identical summaries");
        require(!crossScope.isEmpty(), "identical summaries in two projects");
    }

    private static void require(boolean present, String scenario) {
        if (!present) {
            throw new IllegalStateException("fixture needs " + scenario);
        }
    }

    private static void unique(Collection<String> values, String what) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalStateException("duplicate " + what + " id");
        }
    }

    private static Set<QualityDimension> dimensions(String csv) {
        Set<QualityDimension> out = EnumSet.noneOf(QualityDimension.class);
        Arrays.stream(csv.split(",")).map(String::trim).map(QualityDimension::valueOf).forEach(out::add);
        return out;
    }

    private static Map<QualityDimension, String> evidence(String field) {
        Map<QualityDimension, String> out = new LinkedHashMap<>();
        if (!field.equals("-")) {
            for (String part : field.split(";")) {
                String[] kv = part.split("=");
                out.put(QualityDimension.valueOf(kv[0].trim()), kv[1].trim());
            }
        }
        return out;
    }

    private static List<String> csv(String field) {
        return field.equals("-") ? List.of() : Arrays.stream(field.split(",")).map(String::trim).toList();
    }

    private static boolean yesNo(String field) {
        return switch (field) {
            case "y" -> true;
            case "n" -> false;
            default -> throw new IllegalStateException("expected y or n but found: " + field);
        };
    }

    private static String dash(String field) {
        return field.equals("-") ? null : field;
    }

    private static String require(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing fixture property: " + key);
        }
        return value.trim();
    }

    private static Properties properties(String name) {
        Properties p = new Properties();
        try (InputStream in = open(name)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private static InputStream open(String name) {
        InputStream in = MultiAttemptFixture.class.getResourceAsStream(RESOURCE_ROOT + name);
        if (in == null) {
            throw new IllegalStateException("missing fixture resource: " + RESOURCE_ROOT + name);
        }
        return in;
    }

    private static List<String[]> rows(String name, int columns) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = open(name)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                if (fields.length != columns) {
                    throw new IllegalStateException(name + ": expected " + columns + " columns but found "
                            + fields.length + " in: " + line);
                }
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = fields[i].trim();
                }
                out.add(fields);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
