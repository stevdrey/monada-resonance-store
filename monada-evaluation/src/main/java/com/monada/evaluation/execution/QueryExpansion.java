package com.monada.evaluation.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The bounded, documented query-side term map of issue #124, fixture {@code forge-query-expansion-v1}. It exists only
 * in {@code monada-evaluation}: nothing in the store, the projection, the encoder or the query keys reads it, and
 * {@code ExecutionMemory.recall} never applies it. The evaluator passes the expanded text to recall explicitly.
 *
 * <p>Expansion is pure and deterministic: a rule fires when its source token occurs in the query as a whole token
 * (lowercase, split on non-alphanumeric characters exactly like the default lexical encoder); fired rules append their
 * terms in table order after the unchanged source text; a term already in the query or already added is skipped; at
 * most {@link #maxAddedTermsPerQuery()} terms are added. A query where no rule fires is returned unchanged.
 */
public record QueryExpansion(String version, String provenance, String leakage, int maxRules, int maxTermsPerRule,
                             int maxAddedTermsPerQuery, List<Rule> rules) {

    static final String RESOURCE_ROOT = "/com/monada/evaluation/execution/forge-query-expansion-v1/";

    /** Declared before the comparison ran; never edited from its output. */
    public enum Risk { LOW, MEDIUM, HIGH }

    /** One term rule: a whole-token source and the terms it appends. */
    public record Rule(String id, String source, List<String> added, Risk risk, String rationale) {
        public Rule {
            added = List.copyOf(added);
        }
    }

    /**
     * The result of expanding one query. {@code expanded} equals {@code source} when nothing was added;
     * {@code fired} lists the rules whose source token occurs in the query (in table order), even when all of their
     * terms were skipped as already present.
     */
    public record Expansion(String source, String expanded, List<String> fired, List<String> added) {
        public Expansion {
            fired = List.copyOf(fired);
            added = List.copyOf(added);
        }

        public boolean changed() {
            return !added.isEmpty();
        }
    }

    public QueryExpansion {
        rules = List.copyOf(rules);
    }

    /** Loads the bundled table and validates it. */
    public static QueryExpansion load() {
        Properties manifest = ForgeFixture.properties(RESOURCE_ROOT, "manifest.properties");
        List<Rule> rules = new ArrayList<>();
        for (String[] f : ForgeFixture.rows(RESOURCE_ROOT, "terms.psv", 5)) {
            rules.add(new Rule(f[0], f[1], f[2].isEmpty() ? List.of() : Arrays.asList(f[2].split(" +")),
                    Risk.valueOf(f[3]), f[4]));
        }
        QueryExpansion table = new QueryExpansion(ForgeFixture.require(manifest, "fixtureVersion"),
                ForgeFixture.require(manifest, "provenance"), ForgeFixture.require(manifest, "leakage"),
                Integer.parseInt(ForgeFixture.require(manifest, "max.rules")),
                Integer.parseInt(ForgeFixture.require(manifest, "max.termsPerRule")),
                Integer.parseInt(ForgeFixture.require(manifest, "max.addedTermsPerQuery")), rules);
        table.validate();
        return table;
    }

    /** Structural checks; a violation is a table bug and aborts loading. */
    void validate() {
        if (maxRules < 1 || maxTermsPerRule < 1 || maxAddedTermsPerQuery < 1) {
            throw new IllegalStateException("expansion bounds must be positive");
        }
        if (rules.isEmpty() || rules.size() > maxRules) {
            throw new IllegalStateException("the table needs between 1 and " + maxRules + " rules, found "
                    + rules.size());
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        for (Rule r : rules) {
            if (!r.id().matches("R\\d{2}") || !ids.add(r.id())) {
                throw new IllegalStateException(r.id() + ": rule ids are unique, R followed by two digits");
            }
            if (!isToken(r.source()) || !sources.add(r.source())) {
                throw new IllegalStateException(r.id() + ": the source must be one unique lowercase alphanumeric "
                        + "token: " + r.source());
            }
            if (r.added().isEmpty() || r.added().size() > maxTermsPerRule) {
                throw new IllegalStateException(r.id() + ": a rule adds between 1 and " + maxTermsPerRule
                        + " terms");
            }
            if (new LinkedHashSet<>(r.added()).size() != r.added().size()) {
                throw new IllegalStateException(r.id() + ": duplicate added term");
            }
            for (String term : r.added()) {
                if (!isToken(term) || term.equals(r.source())) {
                    throw new IllegalStateException(r.id() + ": added terms are lowercase alphanumeric tokens "
                            + "other than the source: " + term);
                }
            }
            if (r.rationale().isBlank()) {
                throw new IllegalStateException(r.id() + ": every rule states why it is safe or unsafe");
            }
        }
    }

    private static boolean isToken(String s) {
        return s.matches("[\\p{Lower}\\p{Digit}]+") && s.equals(s.toLowerCase(Locale.ROOT));
    }

    /** The lowercase whole tokens of {@code text}, split like the default lexical encoder; blanks dropped. */
    static List<String> tokens(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+")).filter(t -> !t.isBlank())
                .toList();
    }

    /** All rules. */
    public Expansion expand(String query) {
        return expand(query, r -> true);
    }

    /** Expands {@code query} with the rules {@code enabled} accepts; pure and deterministic. */
    public Expansion expand(String query, Predicate<Rule> enabled) {
        Set<String> present = new LinkedHashSet<>(tokens(query));
        List<String> fired = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (Rule r : rules) {
            if (!enabled.test(r) || !present.contains(r.source())) {
                continue;
            }
            fired.add(r.id());
            for (String term : r.added()) {
                if (added.size() < maxAddedTermsPerQuery && present.add(term)) {
                    added.add(term);
                }
            }
        }
        String expanded = added.isEmpty() ? query : query + " " + String.join(" ", added);
        return new Expansion(query, expanded, fired, added);
    }

    public Rule rule(String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown rule " + id));
    }
}
