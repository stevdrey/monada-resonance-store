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
import java.util.TreeMap;

/**
 * The versioned, synthetic and manually judged Forge experience corpus (issue #102). Every value comes from
 * resource files under {@code forge-experience-v1/}: executions, attempts (one attempt is one experience),
 * judged queries and hand-verified expectations. Nothing here is produced by a model or a provider, and nothing
 * comes from a real repository.
 *
 * <p>An experience id is {@code <execution>/a<ordinal>}. Scope keys ({@code alpha}, {@code beta}) name the two
 * opaque projects; {@link #scopeId} maps them to the persisted scope ids.
 */
public record ForgeFixture(String version, String provenance, Map<String, String> scopeIds, Price price,
                           int recallLimit, List<Integer> ks, List<Execution> executions, List<Attempt> attempts,
                           List<Query> queries, Expectations expectations) {

    static final String RESOURCE_ROOT = "/com/monada/evaluation/execution/forge-experience-v1/";
    static final int MIN_EXPERIENCES = 24;
    static final int MIN_QUERIES = 12;
    static final int MIN_PROJECTS = 2;

    /** Query kinds the corpus must cover (the issue's list) plus BLANK. */
    public enum QueryKind {
        DIRECT, PARAPHRASE, MULTI_RELEVANT, CONFUSABLE, SAME_SUMMARY_DIFFERENT_ATTEMPT, STALE_REVISION,
        MISSING_EVIDENCE, NO_RELEVANT, BLANK
    }

    public enum OutcomeKind { ACCEPTED_VALIDATED, ACCEPTED_IMPORTED, REJECTED_VALIDATED, NONE }

    /** Fixed USD price snapshot: {@code input} and {@code output} per {@code unitSize} tokens. */
    public record Price(String id, String version, String provider, String model, String currency,
                        LocalDate effective, BigDecimal input, BigDecimal output, long unitSize) {
    }

    public record Execution(String id, String scopeKey, String task, String summary, String sourceRevision,
                            String contextFingerprint, String constraintsFingerprint,
                            Set<QualityDimension> mandatory, OutcomeKind outcome, int outcomeAttempt,
                            String expectedLabel, String expectedAcceptance, Set<String> expectedReasons) {
    }

    public record Attempt(String execution, int ordinal, String reason, String result, String solution,
                          String lesson, long inputTokens, long outputTokens, long startSeconds, long endSeconds,
                          Map<QualityDimension, String> evidence, String revisedSolution, String revisedLesson) {
        public String experienceId() {
            return execution + "/a" + ordinal;
        }

        public boolean revised() {
            return revisedSolution != null;
        }
    }

    public record Query(String id, QueryKind kind, String scopeKey, String text, Set<String> relevant,
                        String judgment) {
    }

    /** One hand-verified pairwise expectation: {@code <scope>:<left>|<scope>:<right>|<conclusion>|<fields>}. */
    public record ExpectedComparison(String leftScope, String left, String rightScope, String right,
                                     String conclusion, Set<String> mismatchFields) {
    }

    /** Hand-verified chain usage; {@code cost} is empty when the cost to an accepted outcome is UNAVAILABLE. */
    public record ExpectedChain(long inputTokens, long outputTokens, java.util.Optional<BigDecimal> cost) {
    }

    public record Expectations(Map<String, ExpectedChain> chains, Map<String, Integer> successOrdinals,
                               List<ExpectedComparison> comparisons) {
        /** Executions judged to have no cost to an accepted outcome. */
        public Set<String> unavailableCosts() {
            Set<String> out = new LinkedHashSet<>();
            chains.forEach((id, c) -> {
                if (c.cost().isEmpty()) {
                    out.add(id);
                }
            });
            return out;
        }
    }

    public ForgeFixture {
        // Map.copyOf iterates in a per-JVM random order; the report iterates scopes, so keep fixture order.
        scopeIds = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(scopeIds));
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

    /**
     * The execution with that id, or empty. Use it for ids that come from an export or a recall: those may name an
     * execution the fixture does not know, and a gate must report that, never throw.
     */
    public Optional<Execution> findExecution(String id) {
        return executions.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public List<Attempt> attemptsOf(String execution) {
        return attempts.stream().filter(a -> a.execution().equals(execution)).toList();
    }

    public List<Execution> executionsOf(String scopeKey) {
        return executions.stream().filter(e -> e.scopeKey().equals(scopeKey)).toList();
    }

    public Set<String> experienceIds() {
        Set<String> ids = new LinkedHashSet<>();
        attempts.forEach(a -> ids.add(a.experienceId()));
        return ids;
    }

    /** Loads and validates the version-1 corpus from the module resources. */
    public static ForgeFixture load() {
        Properties manifest = new Properties();
        try (InputStream in = open("manifest.properties")) {
            manifest.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> scopes = new LinkedHashMap<>();
        scopes.put("alpha", require(manifest, "scope.alpha"));
        scopes.put("beta", require(manifest, "scope.beta"));
        Price price = new Price(require(manifest, "price.id"), require(manifest, "price.version"),
                require(manifest, "price.provider"), require(manifest, "price.model"),
                require(manifest, "price.currency"), LocalDate.parse(require(manifest, "price.effective")),
                new BigDecimal(require(manifest, "price.input")), new BigDecimal(require(manifest, "price.output")),
                Long.parseLong(require(manifest, "price.unitSize")));
        List<Integer> ks = Arrays.stream(require(manifest, "metric.ks").split(",")).map(String::trim)
                .map(Integer::valueOf).toList();

        List<Execution> executions = new ArrayList<>();
        for (String[] f : rows("executions.psv", 12)) {
            String[] outcome = f[8].split(":");
            executions.add(new Execution(f[0], f[1], f[2], f[3], f[4], f[5], f[6], dimensions(f[7]),
                    OutcomeKind.valueOf(outcome[0]), outcome.length > 1 ? Integer.parseInt(outcome[1]) : 0,
                    f[9], f[10], f[11].equals("-") ? Set.of()
                    : new LinkedHashSet<>(Arrays.stream(f[11].split(",")).map(String::trim).toList())));
        }
        List<Attempt> attempts = new ArrayList<>();
        for (String[] f : rows("attempts.psv", 13)) {
            attempts.add(new Attempt(f[0], Integer.parseInt(f[1]), f[2], f[3], f[4], f[5], Long.parseLong(f[6]),
                    Long.parseLong(f[7]), Long.parseLong(f[8]), Long.parseLong(f[9]), evidence(f[10]),
                    dash(f[11]), dash(f[12])));
        }
        List<Query> queries = new ArrayList<>();
        for (String[] f : rows("queries.psv", 6)) {
            Set<String> relevant = f[4].equals("-") ? Set.of()
                    : new LinkedHashSet<>(Arrays.stream(f[4].split(",")).map(String::trim).toList());
            // a blank query is stored as an empty field after trimming; keep it blank on purpose
            String text = f[3].isEmpty() ? "   " : f[3];
            queries.add(new Query(f[0], QueryKind.valueOf(f[1]), f[2], text, relevant, f[5]));
        }
        ForgeFixture fixture = new ForgeFixture(require(manifest, "fixtureVersion"), require(manifest, "provenance"),
                java.util.Collections.unmodifiableMap(scopes), price, Integer.parseInt(require(manifest, "recall.limit")), ks,
                List.copyOf(executions), List.copyOf(attempts), List.copyOf(queries), loadExpectations());
        fixture.validate();
        return fixture;
    }

    private static Expectations loadExpectations() {
        Properties p = new Properties();
        try (InputStream in = open("expectations.properties")) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, ExpectedChain> chains = new TreeMap<>();
        Map<String, Integer> ordinals = new TreeMap<>();
        Map<Integer, ExpectedComparison> comparisons = new TreeMap<>();
        for (String key : new TreeMap<>(p).keySet().stream().map(String::valueOf).toList()) {
            String value = p.getProperty(key).trim();
            if (key.startsWith("chain.") && key.endsWith(".cost")) {
                String exec = key.substring("chain.".length(), key.length() - ".cost".length());
                chains.put(exec, new ExpectedChain(Long.parseLong(require(p, "chain." + exec + ".input")),
                        Long.parseLong(require(p, "chain." + exec + ".output")),
                        value.equals("UNAVAILABLE") ? java.util.Optional.empty()
                                : java.util.Optional.of(new BigDecimal(value))));
            } else if (key.startsWith("ordinal.")) {
                ordinals.put(key.substring("ordinal.".length()), Integer.parseInt(value));
            } else if (key.startsWith("comparison.")) {
                String[] f = value.split("\\|", -1);
                if (f.length != 4) {
                    throw new IllegalStateException("bad comparison expectation: " + key);
                }
                String[] left = f[0].split(":");
                String[] right = f[1].split(":");
                Set<String> fields = f[3].isBlank() ? Set.of()
                        : new LinkedHashSet<>(Arrays.stream(f[3].split(",")).map(String::trim).toList());
                comparisons.put(Integer.parseInt(key.substring("comparison.".length())),
                        new ExpectedComparison(left[0], left[1], right[0], right[1], f[2].trim(), fields));
            }
        }
        return new Expectations(Map.copyOf(chains), Map.copyOf(ordinals),
                List.copyOf(comparisons.values()));
    }

    /** Structural validation: corpus minimums, unique ids, consistent references. */
    void validate() {
        if (attempts.size() < MIN_EXPERIENCES) {
            throw new IllegalStateException("fixture needs at least " + MIN_EXPERIENCES + " experiences, has "
                    + attempts.size());
        }
        if (queries.size() < MIN_QUERIES) {
            throw new IllegalStateException("fixture needs at least " + MIN_QUERIES + " queries");
        }
        if (scopeIds.size() < MIN_PROJECTS || scopeIds.values().stream().distinct().count() < MIN_PROJECTS) {
            throw new IllegalStateException("fixture needs two distinct projects");
        }
        unique(executions.stream().map(Execution::id).toList(), "execution");
        unique(attempts.stream().map(Attempt::experienceId).toList(), "experience");
        unique(queries.stream().map(Query::id).toList(), "query");
        for (Execution e : executions) {
            boolean learnable = e.expectedLabel().equals("SUCCESS") || e.expectedLabel().equals("FAILURE");
            if (learnable != e.expectedReasons().isEmpty()) {
                throw new IllegalStateException("execution " + e.id() + ": eligibility reasons must be given exactly "
                        + "for samples that are not SUCCESS or FAILURE");
            }
            scopeId(e.scopeKey());
            if (attemptsOf(e.id()).isEmpty()) {
                throw new IllegalStateException("execution without attempts: " + e.id());
            }
            if (e.outcome() != OutcomeKind.NONE && e.outcome() != OutcomeKind.REJECTED_VALIDATED
                    && attemptsOf(e.id()).stream().noneMatch(a -> a.ordinal() == e.outcomeAttempt())) {
                throw new IllegalStateException("outcome names a missing attempt: " + e.id());
            }
        }
        for (Attempt a : attempts) {
            execution(a.execution());
        }
        for (String exec : executions.stream().map(Execution::id).toList()) {
            List<Integer> ordinals = attemptsOf(exec).stream().map(Attempt::ordinal).toList();
            for (int i = 0; i < ordinals.size(); i++) {
                if (ordinals.get(i) != i + 1) {
                    throw new IllegalStateException("attempt ordinals of " + exec + " must be 1..n in order");
                }
            }
        }
        Set<String> ids = experienceIds();
        for (Query q : queries) {
            scopeId(q.scopeKey());
            for (String r : q.relevant()) {
                if (!ids.contains(r)) {
                    throw new IllegalStateException("query " + q.id() + " names an unknown experience: " + r);
                }
                String execScope = execution(r.substring(0, r.indexOf('/'))).scopeKey();
                if (!execScope.equals(q.scopeKey())) {
                    throw new IllegalStateException("query " + q.id() + " judges an experience of another project: "
                            + r);
                }
            }
            if ((q.kind() == QueryKind.NO_RELEVANT || q.kind() == QueryKind.BLANK) != q.relevant().isEmpty()) {
                throw new IllegalStateException("query " + q.id() + ": relevant set must be empty exactly for "
                        + "NO_RELEVANT and BLANK queries");
            }
        }
        Set<QueryKind> covered = EnumSet.noneOf(QueryKind.class);
        queries.forEach(q -> covered.add(q.kind()));
        if (!covered.containsAll(EnumSet.complementOf(EnumSet.of(QueryKind.BLANK)))) {
            throw new IllegalStateException("fixture must cover every query kind, covers " + covered);
        }
        if (attempts.stream().noneMatch(Attempt::revised)) {
            throw new IllegalStateException("fixture needs a stale-revision experience");
        }
        for (String exec : expectations.chains().keySet()) {
            execution(exec);
        }
        for (Execution e : executions) {
            if (!expectations.chains().containsKey(e.id())) {
                throw new IllegalStateException("no hand-verified usage for execution " + e.id());
            }
        }
        for (ExpectedComparison c : expectations.comparisons()) {
            scopeId(c.leftScope());
            scopeId(c.rightScope());
            execution(c.left());
            execution(c.right());
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

    private static InputStream open(String name) {
        InputStream in = ForgeFixture.class.getResourceAsStream(RESOURCE_ROOT + name);
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
