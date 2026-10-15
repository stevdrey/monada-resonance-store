package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.QueryExpansion.Expansion;
import com.monada.evaluation.execution.QueryExpansion.Risk;
import com.monada.evaluation.execution.QueryExpansion.Rule;
import com.monada.evaluation.execution.QueryExpansionReport.Effect;
import com.monada.evaluation.execution.QueryExpansionReport.QueryComparison;
import com.monada.evaluation.execution.QueryExpansionReport.Verdict;
import com.monada.evaluation.execution.QueryExpansionReport.Veto;
import com.monada.evaluation.execution.RecallStressEvaluator.Observed;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bounded query-side expansion of issue #124 in isolation: the table's bounds and validation, the pure expansion
 * (whole tokens, verbatim source prefix, skip and cap rules) and the veto logic on synthetic rankings, including that
 * no verdict ever means adoption.
 */
class QueryExpansionTest {
    private static final QueryExpansion TABLE = QueryExpansion.load();
    private static final RecallStressFixture FIXTURE = RecallStressFixture.load();

    @Test
    void theBundledTableIsBoundedAndEveryRuleStatesItsRisk() {
        assertTrue(TABLE.rules().size() <= TABLE.maxRules());
        assertEquals(8, TABLE.maxAddedTermsPerQuery());
        assertEquals(EnumSet.allOf(Risk.class), TABLE.rules().stream().map(Rule::risk)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Risk.class))),
                "LOW, MEDIUM and deliberately risky HIGH rules are all declared");
        for (Rule r : TABLE.rules()) {
            assertTrue(r.added().size() <= TABLE.maxTermsPerRule(), r.id());
            assertFalse(r.rationale().isBlank(), r.id());
        }
    }

    @Test
    void firedRulesAppendTheirTermsAfterTheVerbatimSourceInTableOrder() {
        Expansion e = TABLE.expand("NPE in billing aggregation");
        assertEquals("NPE in billing aggregation null pointer invoice totals", e.expanded());
        assertEquals(List.of("R01", "R09", "R16"), e.fired());
        assertEquals(List.of("null", "pointer", "invoice", "totals"), e.added());
        assertTrue(e.expanded().startsWith(e.source()));
    }

    @Test
    void onlyWholeTokensMatchCaseAndPunctuationAside() {
        assertTrue(TABLE.expand("retry the Callback!").changed());
        assertFalse(TABLE.expand("callbacked npes billings").changed(), "inflections are never inferred");
        assertEquals(List.of("R03"), TABLE.expand("outbound callbacks").fired());
    }

    @Test
    void termsAlreadyInTheQueryOrAlreadyAddedAreSkipped() {
        Expansion e = TABLE.expand("purge and delete stale tokens");
        assertEquals(List.of("deletes", "expired"), e.added());
        // R02 and R03 both add webhook: it is added once.
        assertEquals(List.of("webhook"), TABLE.expand("callback callbacks").added());
        // A fired rule whose terms are all present adds nothing and leaves the text unchanged.
        Expansion none = TABLE.expand("npe null pointer");
        assertEquals(List.of("R01"), none.fired());
        assertFalse(none.changed());
        assertEquals("npe null pointer", none.expanded());
    }

    @Test
    void thePerQueryCapIsEnforced() {
        QueryExpansion tight = new QueryExpansion("t", "p", "l", 5, 3, 2, List.of(
                new Rule("R01", "aa", List.of("x1", "x2", "x3"), Risk.LOW, "why"),
                new Rule("R02", "bb", List.of("y1"), Risk.LOW, "why")));
        tight.validate();
        Expansion e = tight.expand("aa bb");
        assertEquals(List.of("x1", "x2"), e.added());
        assertEquals(List.of("R01", "R02"), e.fired());
        assertEquals("aa bb x1 x2", e.expanded());
    }

    @Test
    void blankAndUnmatchedQueriesAreReturnedUnchanged() {
        for (String q : List.of("", "  \t  ", "quantum error correction for photonic qubits", "?! ... --")) {
            Expansion e = TABLE.expand(q);
            assertFalse(e.changed(), q);
            assertEquals(q, e.expanded());
            assertEquals(List.of(), e.fired());
        }
    }

    @Test
    void theRulePredicateRestrictsTheCandidateAndExpansionIsDeterministic() {
        String q = "rotate the encryption keys of nightly backups";
        assertTrue(TABLE.expand(q).changed());
        assertFalse(TABLE.expand(q, r -> r.risk() == Risk.LOW).changed());
        for (StressQuery s : FIXTURE.queries()) {
            assertEquals(TABLE.expand(s.text()), TABLE.expand(s.text()), s.id());
        }
    }

    @Test
    void invalidTablesAreRejected() {
        Rule ok = new Rule("R01", "aa", List.of("bb"), Risk.LOW, "why");
        List<List<Rule>> bad = List.of(
                List.of(ok, new Rule("R02", "aa", List.of("cc"), Risk.LOW, "why")),
                List.of(new Rule("R01", "aa", List.of("b1", "b2", "b3", "b4"), Risk.LOW, "why")),
                List.of(new Rule("R01", "aa", List.of("Null"), Risk.LOW, "why")),
                List.of(new Rule("R01", "aa", List.of("null pointer"), Risk.LOW, "why")),
                List.of(new Rule("R01", "aa", List.of("aa"), Risk.LOW, "why")),
                List.of(new Rule("R01", "aa", List.of("bb"), Risk.LOW, " ")),
                List.of(new Rule("X1", "aa", List.of("bb"), Risk.LOW, "why")),
                List.of(ok, new Rule("R01", "cc", List.of("dd"), Risk.LOW, "why")),
                List.of());
        for (List<Rule> rules : bad) {
            assertThrows(IllegalStateException.class,
                    () -> new QueryExpansion("t", "p", "l", 5, 3, 8, rules).validate(), rules.toString());
        }
        List<Rule> tooMany = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            tooMany.add(new Rule(String.format("R%02d", i), "s" + i, List.of("t" + i), Risk.LOW, "why"));
        }
        assertThrows(IllegalStateException.class, () -> new QueryExpansion("t", "p", "l", 5, 3, 8, tooMany)
                .validate());
    }

    @Test
    void aNegationRegressionAndAConfusableRiseRejectTheCandidate() {
        // S07 excludes the offset attempt a-page/a1; the candidate ranks it first.
        QueryComparison r = compare("S07", ranking("a-page/a2:0.5", "a-page/a1:0.4", "a-page/a3:0.3"),
                ranking("a-page/a1:0.6", "a-page/a2:0.5", "a-page/a3:0.3"));
        assertEquals(Effect.REGRESSED, r.effect());
        assertEquals(List.of("a-page/a1 #2 -> #1"), r.confusableRises());
        List<Veto> vetoes = QueryExpansionEvaluator.vetoes(List.of(r), 5);
        assertEquals(List.of("V1", "V3"), vetoes.stream().map(Veto::code).toList());
        assertEquals(Verdict.REJECTED, QueryExpansionEvaluator.verdict(List.of(r), vetoes));
    }

    @Test
    void anAggregateGainDoesNotOutweighFalseSharingOnANoRelevantQuery() {
        QueryComparison gain = compare("S06", ranking("b-backup/a1:0.08"),
                ranking("b-webhook/a1:0.44", "b-webhook/a2:0.29", "b-backup/a1:0.05"));
        assertEquals(Effect.IMPROVED, gain.effect());
        QueryComparison sharing = compare("S18", ranking("b-backup/a1:0.39", "b-dedup/a2:0.09"),
                ranking("b-backup/a1:0.33", "b-tokens/a1:0.27", "b-dedup/a2:0.08"));
        assertEquals(Effect.NOT_SCORED, sharing.effect());
        assertEquals(List.of("b-tokens/a1"), sharing.falseSharing().stream()
                .map(RecallStressReport.Hit::experienceId).toList());
        List<QueryComparison> rows = List.of(gain, sharing);
        List<Veto> vetoes = QueryExpansionEvaluator.vetoes(rows, 5);
        // b-tokens/a1 is also a judged confusable of S18, so its entry into the top K is a V3 as well.
        assertEquals(List.of("V3", "V4"), vetoes.stream().map(Veto::code).toList());
        assertTrue(vetoes.get(1).detail().contains("b-tokens/a1 0.0000 -> 0.2700"), vetoes.get(1).detail());
        assertEquals(Verdict.REJECTED, QueryExpansionEvaluator.verdict(rows, vetoes));
    }

    @Test
    void aStrengthenedConfusableOnANoRelevantQueryIsVetoedEvenWithoutANewNeighbour() {
        QueryComparison r = compare("S18", ranking("b-backup/a1:0.39", "b-tokens/a1:0.13"),
                ranking("b-backup/a1:0.33", "b-tokens/a1:0.27"));
        assertEquals(List.of(), r.falseSharing());
        assertEquals(List.of("V4"), QueryExpansionEvaluator.vetoes(List.of(r), 5).stream().map(Veto::code)
                .toList());
    }

    @Test
    void aCleanGainIsAtMostJustifiedForAFollowUpAndNoChangeIsNotJustified() {
        QueryComparison gain = compare("S06", ranking("b-backup/a1:0.08"),
                ranking("b-webhook/a1:0.44", "b-webhook/a2:0.29"));
        assertEquals(Verdict.JUSTIFIED_FOR_FOLLOW_UP, QueryExpansionEvaluator.verdict(List.of(gain),
                QueryExpansionEvaluator.vetoes(List.of(gain), 5)));
        QueryComparison same = compare("S06", ranking("b-backup/a1:0.08"), ranking("b-backup/a1:0.08"));
        assertEquals(Effect.UNCHANGED, same.effect());
        assertEquals(Verdict.NOT_JUSTIFIED, QueryExpansionEvaluator.verdict(List.of(same), List.of()));
        assertEquals(Set.of("REJECTED", "NOT_JUSTIFIED", "JUSTIFIED_FOR_FOLLOW_UP"),
                Arrays.stream(Verdict.values()).map(Enum::name).collect(Collectors.toSet()),
                "there is deliberately no adopt verdict");
    }

    @Test
    void theStoreFingerprintSeesEveryByteChange(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.log"), "one");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/b.vec"), "two");
        String before = QueryExpansionEvaluator.fingerprint(dir);
        assertEquals(before, QueryExpansionEvaluator.fingerprint(dir));
        Files.writeString(dir.resolve("sub/b.vec"), "twO");
        assertNotEquals(before, QueryExpansionEvaluator.fingerprint(dir));
    }

    private static QueryComparison compare(String queryId, List<Observed> exact, List<Observed> candidate) {
        StressQuery q = FIXTURE.query(queryId).orElseThrow();
        return QueryExpansionEvaluator.compare(TABLE.expand(q.text()), outcome(q, exact), outcome(q, candidate), 1,
                5);
    }

    private static QueryOutcome outcome(StressQuery q, List<Observed> hits) {
        return RecallStressEvaluator.outcome(FIXTURE.corpus(), q, "CURRENT", hits, FIXTURE.ks(), 5);
    }

    /** {@code id:similarity} pairs in rank order. */
    private static List<Observed> ranking(String... hits) {
        List<Observed> out = new ArrayList<>();
        for (String h : hits) {
            String[] f = h.split(":");
            String execution = RecallStressFixture.executionOf(f[0]);
            String scope = FIXTURE.corpus().scopeId(FIXTURE.corpus().execution(execution).scopeKey());
            out.add(new Observed(f[0], scope, execution, 1, Double.parseDouble(f[1])));
        }
        return out;
    }
}
