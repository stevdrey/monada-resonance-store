package com.monada.api.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.ExperienceRefCodec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recall-quality regression gate for the scope-isolation fixture (issue #98).
 *
 * <p>The baseline below is the protected per-query top-K (refs, order and similarities) of the v1 projection
 * with production defaults. Every run compares the candidate against it, prints a baseline-vs-candidate table
 * (test report standard output), and fails with a diff on any reordering, changed similarity, cross-scope hit or
 * drop in the protected aggregate metrics. Relevance is annotated per query by execution ID. Changing projection
 * text, encoding or ranking therefore requires updating this baseline deliberately, with the evidence in review.
 */
class ExperienceRecallIsolationReportTest {
    static final int K = 3;
    /** Similarities are recorded with 6 decimals; candidates must round to the same value. */
    static final double TOLERANCE = 5e-7;

    record Query(ScopeId scope, String text, Optional<String> relevantExecution) {
    }

    static final List<Query> QUERIES = List.of(
            new Query(ExperienceRecallTest.ALPHA, ExperienceRecallTest.DISTRACTOR, Optional.of("x-2")),
            new Query(ExperienceRecallTest.ALPHA, "flaky test", Optional.of("x-1")),
            new Query(ExperienceRecallTest.ALPHA, "gradle cache", Optional.of("x-2")),
            new Query(ExperienceRecallTest.ALPHA, "release notes", Optional.of("x-3")),
            new Query(ExperienceRecallTest.ALPHA, "unrelated astronomy question", Optional.empty()),
            new Query(ExperienceRecallTest.BETA, ExperienceRecallTest.DISTRACTOR, Optional.of("b-1")),
            new Query(ExperienceRecallTest.BETA, "flaky test", Optional.of("b-1")),
            new Query(ExperienceRecallTest.BETA, "gradle cache", Optional.of("b-1")),
            new Query(ExperienceRecallTest.BETA, "release notes", Optional.empty()),
            new Query(ExperienceRecallTest.BETA, "unrelated astronomy question", Optional.empty()));

    private static String a(String exec) {
        return "project-alpha|task-" + exec + "|" + exec + "|~a1|" + exec + "-a1-fin|1";
    }

    private static String b(String exec) {
        return "project-beta|task-" + exec + "|" + exec + "|~a1|" + exec + "-a1-fin|1";
    }

    /** Protected baseline: query key → ranked "canonicalRef@similarity" (6 decimals). */
    static final Map<String, List<String>> BASELINE = new LinkedHashMap<>();

    static {
        String d = ExperienceRecallTest.DISTRACTOR;
        BASELINE.put("project-alpha:" + d, List.of(a("x-2") + "@0.384111", a("x-1") + "@0.307974", a("x-3") + "@0.000000"));
        BASELINE.put("project-alpha:flaky test", List.of(a("x-1") + "@0.534523", a("x-2") + "@0.055556",
                a("x-3") + "@0.000000"));
        BASELINE.put("project-alpha:gradle cache", List.of(a("x-2") + "@0.458123", a("x-1") + "@0.064820",
                a("x-3") + "@0.000000"));
        BASELINE.put("project-alpha:release notes", List.of(a("x-3") + "@0.816497", a("x-1") + "@0.267261",
                a("x-2") + "@0.000000"));
        BASELINE.put("project-alpha:unrelated astronomy question", List.of(a("x-2") + "@0.000000",
                a("x-1") + "@0.000000", a("x-3") + "@0.000000"));
        BASELINE.put("project-beta:" + d, List.of(b("b-1") + "@0.791632"));
        BASELINE.put("project-beta:flaky test", List.of(b("b-1") + "@0.327327"));
        BASELINE.put("project-beta:gradle cache", List.of(b("b-1") + "@0.758600"));
        BASELINE.put("project-beta:release notes", List.of(b("b-1") + "@0.000000"));
        BASELINE.put("project-beta:unrelated astronomy question", List.of(b("b-1") + "@0.000000"));
    }

    /** Protected aggregate metrics over the annotated queries (baseline values). */
    static final double BASELINE_HIT_AT_1 = 1.0;
    static final double BASELINE_MRR = 1.0;
    static final double BASELINE_RECALL_AT_3 = 1.0;

    @TempDir
    Path root;

    @Test
    void recallMatchesTheProtectedBaseline() {
        ExperienceRecallTest.seed(root);
        Map<String, List<ExperienceHit>> candidate = new LinkedHashMap<>();
        List<String> report = new ArrayList<>();
        List<String> diffs = new ArrayList<>();
        double hits1 = 0;
        double reciprocal = 0;
        double recall3 = 0;
        int annotated = 0;
        for (Query q : QUERIES) {
            String key = q.scope() + ":" + q.text();
            List<ExperienceHit> hits;
            try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, q.scope())) {
                ExperienceRecall recall = m.recall(q.scope(), q.text(), K);
                assertEquals(ProjectionStatus.State.CURRENT, recall.status().state(), key);
                hits = recall.hits();
                for (ExperienceHit hit : hits) {
                    assertEquals(q.scope(), hit.ref().scope(), "isolation: " + key);
                    assertEquals(recall.status().ledgerSequence(), hit.coveredSequence(), key);
                }
            }
            candidate.put(key, hits);
            compare(key, BASELINE.get(key), hits, report, diffs);
            if (q.relevantExecution().isPresent()) {
                annotated++;
                int rank = 0;
                for (int i = 0; i < hits.size(); i++) {
                    if (hits.get(i).ref().execution().value().equals(q.relevantExecution().get())) {
                        rank = i + 1;
                        break;
                    }
                }
                hits1 += rank == 1 ? 1 : 0;
                reciprocal += rank == 0 ? 0 : 1.0 / rank;
                recall3 += rank > 0 && rank <= K ? 1 : 0;
            }
        }
        double hitAt1 = hits1 / annotated;
        double mrr = reciprocal / annotated;
        double recallAt3 = recall3 / annotated;
        report.add(String.format(Locale.ROOT, "aggregate over %d annotated queries: Hit@1 %.4f (baseline %.4f), "
                        + "MRR %.4f (baseline %.4f), Recall@3 %.4f (baseline %.4f)", annotated, hitAt1,
                BASELINE_HIT_AT_1, mrr, BASELINE_MRR, recallAt3, BASELINE_RECALL_AT_3));
        report.forEach(System.out::println);

        assertEquals(BASELINE.keySet(), candidate.keySet());
        if (!diffs.isEmpty()) {
            fail("experience recall regressed against the protected baseline:\n" + String.join("\n", diffs));
        }
        assertEquals(BASELINE_HIT_AT_1, hitAt1, 0.0, "protected Hit@1");
        assertEquals(BASELINE_MRR, mrr, 0.0, "protected MRR");
        assertEquals(BASELINE_RECALL_AT_3, recallAt3, 0.0, "protected Recall@3");
        assertTrue(annotated >= 7, "the gate must keep its annotated queries");
    }

    private static void compare(String key, List<String> baseline, List<ExperienceHit> hits, List<String> report,
                                List<String> diffs) {
        report.add("query " + key + " (k=" + K + ")");
        report.add("  rank | baseline                                  | candidate                                 | delta");
        int rows = Math.max(baseline.size(), hits.size());
        for (int i = 0; i < rows; i++) {
            String expectedRef = i < baseline.size() ? baseline.get(i).substring(0, baseline.get(i).lastIndexOf('@')) : "-";
            double expectedSim = i < baseline.size()
                    ? Double.parseDouble(baseline.get(i).substring(baseline.get(i).lastIndexOf('@') + 1)) : Double.NaN;
            String actualRef = i < hits.size() ? ExperienceRefCodec.canonical(hits.get(i).ref()) : "-";
            double actualSim = i < hits.size() ? hits.get(i).similarity() : Double.NaN;
            double delta = actualSim - expectedSim;
            report.add(String.format(Locale.ROOT, "  %4d | %s@%.6f | %s@%.6f | %+.6f", i + 1, expectedRef,
                    expectedSim, actualRef, actualSim, delta));
            if (!expectedRef.equals(actualRef)) {
                diffs.add(key + " rank " + (i + 1) + ": expected " + expectedRef + ", got " + actualRef);
            } else if (!(Math.abs(delta) <= TOLERANCE)) {
                diffs.add(String.format(Locale.ROOT, "%s rank %d: similarity %.9f differs from baseline %.6f", key,
                        i + 1, actualSim, expectedSim));
            }
        }
    }
}
