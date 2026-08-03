package com.monada.evaluation;

import java.util.Objects;

/** Expected value and comparison policy for one protected aggregate metric. */
record TextBaselineExpectation(TextBaselinePolicy policy, double expectedValue) {
    TextBaselineExpectation {
        Objects.requireNonNull(policy, "policy");
        if (!Double.isFinite(expectedValue) || expectedValue < 0.0 || expectedValue > 1.0) {
            throw new IllegalArgumentException(
                    "expectedValue must be finite and in [0.0, 1.0]: " + expectedValue);
        }
    }
}
