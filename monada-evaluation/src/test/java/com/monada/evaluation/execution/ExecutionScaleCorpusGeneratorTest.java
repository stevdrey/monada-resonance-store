package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionLimits;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.Experience;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.Pattern;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.QueryKind;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.ScaleCorpus;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.ScopeCorpus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The scale corpus (issue #125) is a pure function of seed and size: exact recallable counts per scope, every
 * #121 occurrence pattern present, ids within the contract limits, and ground truth consistent with the texts.
 */
class ExecutionScaleCorpusGeneratorTest {
    @Test
    void theSameSeedAndSizeGenerateTheSameCorpus() {
        assertEquals(ExecutionScaleCorpusGenerator.generate(42, 250), ExecutionScaleCorpusGenerator.generate(42, 250));
    }

    @Test
    void anotherSeedGeneratesAnotherCorpus() {
        assertNotEquals(ExecutionScaleCorpusGenerator.generate(42, 250).scopes().get(0).events(),
                ExecutionScaleCorpusGenerator.generate(43, 250).scopes().get(0).events());
    }

    @Test
    void everyScopeHoldsExactlyTheRequestedRecallableExperiences() {
        for (int n : List.of(7, 8, 25, 250)) {
            ScaleCorpus corpus = ExecutionScaleCorpusGenerator.generate(7, n);
            assertEquals(ExecutionScaleCorpusGenerator.SCOPES.size(), corpus.scopes().size());
            for (ScopeCorpus scope : corpus.scopes()) {
                assertEquals(n, scope.activeAttempts(), scope.scope().value() + " N=" + n);
                assertEquals(n, scope.experiences().stream().map(Experience::experienceId).distinct().count());
            }
        }
    }

    @Test
    void sizesBelowTheMinimumAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleCorpusGenerator.generate(42,
                ExecutionScaleCorpusGenerator.MIN_EXPERIENCES_PER_SCOPE - 1));
    }

    @Test
    void everySizeRecallsTheSameQueries() {
        List<List<ExecutionScaleCorpusGenerator.Query>> perScope = ExecutionScaleCorpusGenerator.generate(42,
                ExecutionScaleCorpusGenerator.MIN_EXPERIENCES_PER_SCOPE).scopes().stream()
                .map(ScopeCorpus::queries).toList();
        for (int n : List.of(25, 250, 1000)) {
            assertEquals(perScope, ExecutionScaleCorpusGenerator.generate(42, n).scopes().stream()
                    .map(ScopeCorpus::queries).toList(), "N=" + n);
        }
        assertNotEquals(perScope, ExecutionScaleCorpusGenerator.generate(43, 25).scopes().stream()
                .map(ScopeCorpus::queries).toList());
    }

    @Test
    void exactQueriesTargetTheAnchorPatterns() {
        for (ScopeCorpus scope : ExecutionScaleCorpusGenerator.generate(42, 250).scopes()) {
            List<String> targets = scope.queries().stream().filter(q -> q.kind() == QueryKind.EXACT)
                    .map(q -> q.target().orElseThrow()).toList();
            assertEquals(ExecutionScaleCorpusGenerator.EXACT_QUERIES_PER_SCOPE, targets.size());
            List<Experience> anchors = scope.experiences().subList(3, 7);
            assertEquals(anchors.stream().map(Experience::experienceId).toList(), targets);
            assertEquals(List.of("a1", "a2", "a1", "a1"), anchors.stream()
                    .map(e -> e.ref().attempt().orElseThrow().value()).toList());
            assertEquals(com.monada.core.execution.AttemptResult.FAILED, anchors.get(0).result());
            assertEquals(2, anchors.get(2).ref().revision(), "the corrected anchor is recalled at r2");
        }
    }

    @Test
    void everyOccurrencePatternIsPresentAtRealisticSizes() {
        for (ScopeCorpus scope : ExecutionScaleCorpusGenerator.generate(42, 250).scopes()) {
            for (Pattern p : Pattern.values()) {
                assertTrue(scope.patternCounts().getOrDefault(p, 0) > 0, scope.scope().value() + " " + p);
            }
            assertEquals(scope.patternCounts().get(Pattern.CORRECTED), scope.correctedFinishes());
            assertEquals(scope.patternCounts().get(Pattern.UNFINISHED_REPAIR), scope.unfinishedAttempts());
            assertEquals(scope.correctedFinishes(), scope.retiredTexts().size());
            assertTrue(scope.liveTexts().size() < scope.activeAttempts(), "duplicate texts share atoms");
        }
    }

    @Test
    void theProbeTextIsIdenticalInEveryScopeAndOtherTextsAreScopeLocal() {
        ScaleCorpus corpus = ExecutionScaleCorpusGenerator.generate(42, 250);
        Set<String> probes = corpus.scopes().stream().map(s -> s.experiences().get(0).text())
                .collect(Collectors.toSet());
        assertEquals(1, probes.size());
        Set<String> seen = new HashSet<>();
        for (ScopeCorpus scope : corpus.scopes()) {
            for (String text : scope.liveTexts()) {
                if (!probes.contains(text)) {
                    assertTrue(seen.add(text), "text shared across scopes: " + text);
                }
            }
        }
    }

    @Test
    void everyScopeHasOneQueryOfEachKindAndQueriesTargetOwnExperiences() {
        for (ScopeCorpus scope : ExecutionScaleCorpusGenerator.generate(42, 25).scopes()) {
            Set<QueryKind> kinds = scope.queries().stream().map(ExecutionScaleCorpusGenerator.Query::kind)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(QueryKind.values()), kinds);
            Set<String> ids = scope.experiences().stream().map(Experience::experienceId).collect(Collectors.toSet());
            scope.queries().forEach(q -> q.target().ifPresent(t -> assertTrue(ids.contains(t), q.id())));
        }
    }

    @Test
    void idsAndSummariesStayWithinTheContractLimitsAndEventsAreInScope() {
        for (ScopeCorpus scope : ExecutionScaleCorpusGenerator.generate(42, 1000).scopes()) {
            Set<String> eventIds = new HashSet<>();
            for (ExecutionEvent e : scope.events()) {
                assertEquals(scope.scope(), e.envelope().scopeId());
                assertTrue(eventIds.add(e.eventId().value()), "duplicate event id " + e.eventId().value());
                assertTrue(e.eventId().value().length() <= ExecutionLimits.MAX_IDENTIFIER_CODE_POINTS);
            }
            scope.experiences().forEach(x -> assertTrue(x.text().length() < ExecutionLimits.MAX_SUMMARY_CODE_POINTS));
        }
    }
}
