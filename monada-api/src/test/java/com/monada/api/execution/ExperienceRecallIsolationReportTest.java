package com.monada.api.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.ExperienceRefCodec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-query top-K evidence for the scope-isolation fixture (issue #98): prints each query's ranked
 * experience refs and similarities for both scopes, and checks that no hit ever crosses scopes. Output is in
 * the test report's standard output.
 */
class ExperienceRecallIsolationReportTest {
    static final List<String> QUERIES = List.of(ExperienceRecallTest.DISTRACTOR, "flaky test", "gradle cache",
            "release notes", "unrelated astronomy question");
    static final int K = 3;

    @TempDir
    Path root;

    @Test
    void perQueryTopKStaysInsideEachScope() {
        ExperienceRecallTest.seed(root);
        List<String> report = new ArrayList<>();
        for (ScopeId scope : List.of(ExperienceRecallTest.ALPHA, ExperienceRecallTest.BETA)) {
            try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, scope)) {
                for (String query : QUERIES) {
                    ExperienceRecall recall = m.recall(scope, query, K);
                    assertEquals(ProjectionStatus.State.CURRENT, recall.status().state());
                    report.add("scope=" + scope + " k=" + K + " query=\"" + query + "\"");
                    int rank = 1;
                    for (ExperienceHit hit : recall.hits()) {
                        assertEquals(scope, hit.ref().scope());
                        assertTrue(hit.coveredSequence() == recall.status().ledgerSequence());
                        report.add(String.format(Locale.ROOT, "  %d. %.6f %s atom=%s", rank++, hit.similarity(),
                                ExperienceRefCodec.canonical(hit.ref()), hit.atomId()));
                    }
                }
            }
        }
        report.forEach(System.out::println);
    }
}
