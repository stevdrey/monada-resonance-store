package com.monada.evaluation.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Whether two executions may be compared directly (contract section 10): scope, task, initial source revision,
 * context and constraints fingerprints and evaluation policy id and version must all match exactly. Each
 * mismatching field is one {@link Mismatch}; nothing is compared across different starting conditions.
 */
public record Comparability(List<Mismatch> mismatches) {

    public enum Field {
        SCOPE, TASK, SOURCE_REVISION, CONTEXT_FINGERPRINT, CONSTRAINTS_FINGERPRINT, POLICY_ID, POLICY_VERSION
    }

    public record Mismatch(Field field, String left, String right) {
        public Mismatch {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /** The seven values that decide comparability, in {@link Field} order. */
    public record Key(String scope, String task, String sourceRevision, String contextFingerprint,
                      String constraintsFingerprint, String policyId, String policyVersion)
            implements Comparable<Key> {
        private static final Comparator<Key> ORDER = Comparator.comparing(Key::scope)
                .thenComparing(Key::task).thenComparing(Key::sourceRevision)
                .thenComparing(Key::contextFingerprint).thenComparing(Key::constraintsFingerprint)
                .thenComparing(Key::policyId).thenComparing(Key::policyVersion);

        static Key of(ComparisonCandidate candidate) {
            var view = candidate.view();
            return new Key(candidate.scope().value(), view.task().value(), view.provenance().sourceRevision(),
                    view.provenance().contextFingerprint(), view.provenance().constraintsFingerprint(),
                    view.policy().id(), view.policy().version());
        }

        String value(Field field) {
            return switch (field) {
                case SCOPE -> scope;
                case TASK -> task;
                case SOURCE_REVISION -> sourceRevision;
                case CONTEXT_FINGERPRINT -> contextFingerprint;
                case CONSTRAINTS_FINGERPRINT -> constraintsFingerprint;
                case POLICY_ID -> policyId;
                case POLICY_VERSION -> policyVersion;
            };
        }

        @Override
        public int compareTo(Key other) {
            return ORDER.compare(this, other);
        }
    }

    public Comparability {
        mismatches = List.copyOf(mismatches);
    }

    static Comparability of(Key left, Key right) {
        List<Mismatch> found = new ArrayList<>();
        for (Field field : Field.values()) {
            if (!left.value(field).equals(right.value(field))) {
                found.add(new Mismatch(field, left.value(field), right.value(field)));
            }
        }
        return new Comparability(found);
    }

    public boolean isComparable() {
        return mismatches.isEmpty();
    }
}
