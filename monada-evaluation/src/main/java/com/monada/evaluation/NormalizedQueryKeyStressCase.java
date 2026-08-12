package com.monada.evaluation;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** One inspectable adversarial pair and its evaluation-only replay declaration. */
public record NormalizedQueryKeyStressCase(
        String id,
        NormalizationStressCategory category,
        NormalizationSemanticRelationship semanticRelationship,
        String queryA,
        String queryB,
        Set<String> relevantTargetsA,
        Set<String> relevantTargetsB,
        String feedbackTargetLabel,
        ExpectedQueryKeyRelation expectedKeyRelation,
        double delta,
        Instant createdAt
) {
    public NormalizedQueryKeyStressCase {
        id = requireNonBlank(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(semanticRelationship, "semanticRelationship");
        queryA = requireNonBlank(queryA, "queryA");
        queryB = requireNonBlank(queryB, "queryB");
        relevantTargetsA = immutableNonEmpty(relevantTargetsA, "relevantTargetsA");
        relevantTargetsB = immutableNonEmpty(relevantTargetsB, "relevantTargetsB");
        feedbackTargetLabel = requireNonBlank(feedbackTargetLabel, "feedbackTargetLabel");
        Objects.requireNonNull(expectedKeyRelation, "expectedKeyRelation");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!Double.isFinite(delta) || delta <= 0.0) {
            throw new IllegalArgumentException("delta must be finite and greater than zero: " + delta);
        }
        if (!relevantTargetsA.contains(feedbackTargetLabel)) {
            throw new IllegalArgumentException(
                    "feedback target must be relevant to query A: " + feedbackTargetLabel);
        }
        if (semanticRelationship == NormalizationSemanticRelationship.EQUIVALENT) {
            if (!relevantTargetsA.equals(relevantTargetsB)) {
                throw new IllegalArgumentException("equivalent queries must declare identical relevant targets");
            }
            if (expectedKeyRelation != ExpectedQueryKeyRelation.MATCH) {
                throw new IllegalArgumentException("equivalent queries must expect matching keys");
            }
        } else if (relevantTargetsB.contains(feedbackTargetLabel)) {
            throw new IllegalArgumentException(
                    "distinct query B must not consider the feedback target relevant: " + feedbackTargetLabel);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Set<String> immutableNonEmpty(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        values = Collections.unmodifiableSet(new TreeSet<>(values));
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        values.forEach(value -> requireNonBlank(value, name + " value"));
        return values;
    }
}
