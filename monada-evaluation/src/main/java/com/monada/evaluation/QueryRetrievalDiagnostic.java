package com.monada.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record QueryRetrievalDiagnostic(
        Set<String> expectedLabels,
        List<String> returnedLabels,
        List<String> presentExpectedLabels,
        Set<String> missingExpectedLabels,
        int firstExpectedRank,
        Optional<RetrievalFailureType> failureType,
        String note
) {
    public QueryRetrievalDiagnostic {
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        returnedLabels = List.copyOf(Objects.requireNonNull(returnedLabels, "returnedLabels"));
        presentExpectedLabels = List.copyOf(Objects.requireNonNull(presentExpectedLabels, "presentExpectedLabels"));
        missingExpectedLabels = Set.copyOf(Objects.requireNonNull(missingExpectedLabels, "missingExpectedLabels"));
        Objects.requireNonNull(failureType, "failureType");
        Objects.requireNonNull(note, "note");
    }

    public static QueryRetrievalDiagnostic compute(QueryEvaluation evaluation) {
        Set<String> expected = evaluation.expectedLabels();
        List<String> returned = evaluation.returnedLabels();

        List<String> presentExpected = new ArrayList<>();
        for (String label : returned) {
            if (expected.contains(label)) {
                presentExpected.add(label);
            }
        }

        Set<String> missingExpected = new TreeSet<>();
        for (String label : expected) {
            if (!presentExpected.contains(label)) {
                missingExpected.add(label);
            }
        }

        int firstRank = 0;
        for (int i = 0; i < returned.size(); i++) {
            if (expected.contains(returned.get(i))) {
                firstRank = i + 1;
                break;
            }
        }

        Optional<RetrievalFailureType> failureType = RetrievalFailureClassifier.classify(evaluation);
        String note = generateNote(failureType, presentExpected, missingExpected);

        return new QueryRetrievalDiagnostic(
                expected,
                returned,
                presentExpected,
                missingExpected,
                firstRank,
                failureType,
                note
        );
    }

    private static String generateNote(
            Optional<RetrievalFailureType> failureType,
            List<String> presentExpected,
            Set<String> missingExpected
    ) {
        if (failureType.isEmpty()) {
            return "Perfect retrieval.";
        }
        return switch (failureType.get()) {
            case POSSIBLE_DATASET_ALIAS_GAP ->
                    "No overlapping tokens found between the query and expected labels. This suggests a gap in synonyms or aliases.";
            case MISSING_EXPECTED_ATOM ->
                    "None of the expected atoms were retrieved in the top results.";
            case MULTI_RELEVANT_RECALL_GAP ->
                    "Recall gap: retrieved " + presentExpected + ", but missed " + missingExpected + ".";
            case CONFUSABLE_ATOM_RANKED_HIGHER ->
                    "An unexpected but semantically related/confusable atom was ranked higher than the expected ones.";
            case EXPECTED_ATOM_PRESENT_BUT_LOW_RANK ->
                    "The expected atom was retrieved, but at a low rank.";
            case POSSIBLE_ENCODER_LIMITATION ->
                    "Possible encoder representation limit or vector resolution issues.";
        };
    }
}
