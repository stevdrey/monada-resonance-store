package com.monada.evaluation.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The judged recall stress set of issue #122, fixture {@code forge-recall-stress-v1}: categorized queries with
 * hand-judged relevant and confusable experience ids over the UNCHANGED {@code forge-experience-v1} corpus of #102.
 * The #102 queries are read from that fixture and form the {@link Category#BASELINE} group, so every group is
 * measured on the same corpus. Nothing here is produced by recall, a model or a provider.
 */
public record RecallStressFixture(String version, String provenance, String judgmentProtocol, String reviewer,
                                  ForgeFixture corpus, int recallLimit, List<Integer> ks, List<StressQuery> queries) {

    static final String RESOURCE_ROOT = "/com/monada/evaluation/execution/forge-recall-stress-v1/";
    static final String EMPTY_TOKEN = "<EMPTY>";
    static final String WHITESPACE_TOKEN = "<WHITESPACE>";
    static final String WHITESPACE_TEXT = "  \t  ";

    /** Query groups. BASELINE holds the #102 queries; every other category is required by the issue. */
    public enum Category {
        BASELINE, PARAPHRASE, SYNONYM, NEGATION, RELATION, TERMINOLOGY, NO_RELEVANT, DUPLICATE_SUMMARY, EMPTY_QUERY;

        /** Categories that must have at least one stress query. */
        static Set<Category> stress() {
            return EnumSet.complementOf(EnumSet.of(BASELINE));
        }
    }

    /**
     * One judged query. {@code kind} is the #102 query kind for BASELINE queries and the category name otherwise.
     * {@code relevant} may be empty (explicitly judged: nothing answers it); {@code confusable} lists experiences
     * judged NOT relevant although they share vocabulary or task with the query.
     */
    public record StressQuery(String id, Category category, String kind, String scopeKey, String text,
                              Set<String> relevant, Set<String> confusable, String judgment) {
        public StressQuery {
            relevant = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(relevant));
            confusable = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(confusable));
        }
    }

    public RecallStressFixture {
        ks = List.copyOf(ks);
        queries = List.copyOf(queries);
    }

    public List<StressQuery> queriesOf(Category category) {
        return queries.stream().filter(q -> q.category() == category).toList();
    }

    public Optional<StressQuery> query(String id) {
        return queries.stream().filter(q -> q.id().equals(id)).findFirst();
    }

    /** Loads the bundled stress set over the bundled #102 corpus and validates it. */
    public static RecallStressFixture load() {
        return load(ForgeFixture.load());
    }

    static RecallStressFixture load(ForgeFixture corpus) {
        Properties manifest = ForgeFixture.properties(RESOURCE_ROOT, "manifest.properties");
        String base = ForgeFixture.require(manifest, "baseCorpus");
        if (!base.equals("forge-experience-v" + corpus.version())) {
            throw new IllegalStateException("stress set is judged over " + base + " but the corpus is "
                    + "forge-experience-v" + corpus.version());
        }
        List<Integer> ks = Arrays.stream(ForgeFixture.require(manifest, "metric.ks").split(",")).map(String::trim)
                .map(Integer::valueOf).toList();
        List<StressQuery> queries = new ArrayList<>();
        for (ForgeFixture.Query q : corpus.queries()) {
            queries.add(new StressQuery(q.id(), Category.BASELINE, q.kind().name(), q.scopeKey(), q.text(),
                    q.relevant(), Set.of(), q.judgment()));
        }
        for (String[] f : ForgeFixture.rows(RESOURCE_ROOT, "queries.psv", 7)) {
            Category category = Category.valueOf(f[1]);
            queries.add(new StressQuery(f[0], category, category.name(), f[2], text(f[3]), ids(f[4]), ids(f[5]),
                    f[6]));
        }
        RecallStressFixture fixture = new RecallStressFixture(ForgeFixture.require(manifest, "fixtureVersion"),
                ForgeFixture.require(manifest, "provenance"), ForgeFixture.require(manifest, "judgment.protocol"),
                ForgeFixture.require(manifest, "judgment.reviewer"), corpus,
                Integer.parseInt(ForgeFixture.require(manifest, "recall.limit")), ks, queries);
        fixture.validate();
        return fixture;
    }

    /** The query text of a fixture field; a blank query must be written as a token so it is never accidental. */
    static String text(String field) {
        if (field.isEmpty()) {
            throw new IllegalStateException("empty query text field: write " + EMPTY_TOKEN + " or "
                    + WHITESPACE_TOKEN);
        }
        return switch (field) {
            case EMPTY_TOKEN -> "";
            case WHITESPACE_TOKEN -> WHITESPACE_TEXT;
            default -> field;
        };
    }

    private static Set<String> ids(String field) {
        return field.equals("-") ? Set.of()
                : new LinkedHashSet<>(Arrays.stream(field.split(",")).map(String::trim).toList());
    }

    /** Structural checks of the judgments; a violation is a fixture bug and aborts loading. */
    void validate() {
        if (recallLimit != corpus.recallLimit() || !ks.equals(corpus.ks())) {
            throw new IllegalStateException("recall limit and metric K values must equal those of the #102 corpus");
        }
        if (ks.isEmpty() || ks.stream().anyMatch(k -> k < 1 || k > recallLimit)) {
            throw new IllegalStateException("metric K values must lie between 1 and the recall limit");
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<Category> covered = EnumSet.noneOf(Category.class);
        for (StressQuery q : queries) {
            if (!seen.add(q.id())) {
                throw new IllegalStateException("duplicate query id " + q.id());
            }
            covered.add(q.category());
            corpus.scopeId(q.scopeKey());
            if (q.category() != Category.BASELINE && !q.id().matches("S\\d{2}")) {
                throw new IllegalStateException(q.id() + ": stress query ids are S followed by two digits");
            }
            if (q.text().isBlank() && q.category() != Category.EMPTY_QUERY && q.category() != Category.BASELINE) {
                throw new IllegalStateException(q.id() + ": only an EMPTY_QUERY may be blank");
            }
            if ((q.category() == Category.NO_RELEVANT || q.category() == Category.EMPTY_QUERY)
                    && !q.relevant().isEmpty()) {
                throw new IllegalStateException(q.id() + ": a " + q.category() + " query has no relevant experience");
            }
            if (q.relevant().size() > recallLimit) {
                throw new IllegalStateException(q.id() + ": more relevant experiences than the recall limit");
            }
            for (String id : q.relevant()) {
                requireInScope(q, id, "relevant");
                if (q.confusable().contains(id)) {
                    throw new IllegalStateException(q.id() + ": " + id + " is judged both relevant and confusable");
                }
            }
            for (String id : q.confusable()) {
                requireInScope(q, id, "confusable");
            }
            if (q.judgment().isBlank()) {
                throw new IllegalStateException(q.id() + ": every query states its relevance judgment");
            }
        }
        Set<Category> missing = EnumSet.copyOf(Category.stress());
        missing.removeAll(covered);
        if (!missing.isEmpty() || !covered.contains(Category.BASELINE)) {
            throw new IllegalStateException("categories without a query: " + missing);
        }
    }

    private void requireInScope(StressQuery q, String experienceId, String what) {
        if (!corpus.experienceIds().contains(experienceId)) {
            throw new IllegalStateException(q.id() + ": " + what + " " + experienceId + " is not in the corpus");
        }
        String execution = executionOf(experienceId);
        if (!corpus.execution(execution).scopeKey().equals(q.scopeKey())) {
            throw new IllegalStateException(q.id() + ": " + what + " " + experienceId + " belongs to another project");
        }
    }

    /** {@code <execution>} of an experience id {@code <execution>/a<n>}. */
    static String executionOf(String experienceId) {
        int slash = experienceId.indexOf('/');
        return slash < 0 ? experienceId : experienceId.substring(0, slash);
    }
}
