package com.monada.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record EvaluationDataset(List<DatasetAtom> atoms, List<EvaluationQuery> queries) {
    public EvaluationDataset {
        atoms = List.copyOf(Objects.requireNonNull(atoms, "atoms"));
        queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
        if (atoms.isEmpty()) {
            throw new IllegalArgumentException("dataset must contain at least one atom");
        }
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("dataset must contain at least one query");
        }

        Set<String> labels = new HashSet<>();
        for (DatasetAtom atom : atoms) {
            if (!labels.add(atom.label())) {
                throw new IllegalArgumentException("duplicate atom label: " + atom.label());
            }
        }
        for (EvaluationQuery query : queries) {
            for (String expected : query.expectedLabels()) {
                if (!labels.contains(expected)) {
                    throw new IllegalArgumentException(
                            "query references unknown atom label: " + expected);
                }
            }
        }
    }
}
