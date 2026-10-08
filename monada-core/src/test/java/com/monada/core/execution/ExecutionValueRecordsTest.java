package com.monada.core.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionValueRecordsTest {

    // --- usage counters: unknown, absent and zero stay distinguishable ---

    @Test
    void knownZeroIsDistinctFromUnknown() {
        var zero = UsageCounter.reported(UsageKind.INPUT_TOKENS, 0, "provider");
        var unknown = UsageCounter.unknown(UsageKind.INPUT_TOKENS, "provider");
        assertEquals(OptionalLong.of(0), zero.value());
        assertTrue(unknown.value().isEmpty());
        assertNotEquals(zero, unknown);
    }

    @Test
    void usageCounterRejectsInconsistentValueAndProvenance() {
        assertThrows(IllegalArgumentException.class, () -> UsageCounter.reported(UsageKind.REQUESTS, -1, "s"));
        assertThrows(IllegalArgumentException.class,
                () -> new UsageCounter(UsageKind.REQUESTS, OptionalLong.empty(), UsageProvenance.REPORTED, "s"));
        assertThrows(IllegalArgumentException.class,
                () -> new UsageCounter(UsageKind.REQUESTS, OptionalLong.of(3), UsageProvenance.UNKNOWN, "s"));
        assertThrows(IllegalArgumentException.class, () -> UsageCounter.estimated(UsageKind.REQUESTS, 1, " "));
    }

    // --- quality observations ---

    @Test
    void unknownAndNotApplicableAreNotPass() {
        assertNotEquals(Fixtures.tests(ObservationState.UNKNOWN), Fixtures.tests(ObservationState.PASS));
        assertNotEquals(ObservationState.NOT_APPLICABLE, ObservationState.PASS);
    }

    @Test
    void notApplicableRequiresJustification() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.tests(ObservationState.NOT_APPLICABLE));
        var ok = new QualityObservation(QualityDimension.SECURITY, ObservationState.NOT_APPLICABLE,
                OptionalDouble.empty(), Optional.empty(), "human", "1", "forge-gates", "1",
                ObservationSource.HUMAN, List.of(), Optional.of("documentation-only change"));
        assertEquals(Optional.of("documentation-only change"), ok.justification());
    }

    @Test
    void measuredValueMustBeFiniteNonNegativeWithUnitAndOnlyForPassOrFail() {
        assertEquals(OptionalDouble.of(0.0), Fixtures.withValue(0.0).measuredValue());
        for (double bad : new double[]{-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Fixtures.withValue(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new QualityObservation(QualityDimension.COMPLEXITY,
                ObservationState.UNKNOWN, OptionalDouble.of(1), Optional.of("cc"), "t", "1", "p", "1",
                ObservationSource.TOOL, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new QualityObservation(QualityDimension.COMPLEXITY,
                ObservationState.PASS, OptionalDouble.of(1), Optional.empty(), "t", "1", "p", "1",
                ObservationSource.TOOL, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new QualityObservation(QualityDimension.COMPLEXITY,
                ObservationState.PASS, OptionalDouble.empty(), Optional.of("cc"), "t", "1", "p", "1",
                ObservationSource.TOOL, List.of(), Optional.empty()));
    }

    @Test
    void observationPreservesEvaluatorPolicyAndEvidenceReferences() {
        var evidence = List.of(ArtifactRef.of("test-report", "reports/tests.xml", "sha256:abc"));
        var obs = new QualityObservation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(),
                Optional.empty(), "gradle", "9.8.0", "forge-gates", "1", ObservationSource.TOOL, evidence,
                Optional.empty());
        assertEquals("gradle", obs.evaluatorId());
        assertEquals("9.8.0", obs.evaluatorVersion());
        assertEquals("forge-gates", obs.policyId());
        assertEquals("1", obs.policyVersion());
        assertEquals(Optional.of("sha256:abc"), obs.evidence().get(0).digest());
    }

    // --- outcome ---

    @Test
    void outcomeAcceptedRequiresAttemptAndOthersForbidIt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Outcome(OutcomeStatus.ACCEPTED, OutcomeOrigin.VALIDATED, Optional.empty()));
        for (OutcomeStatus status : EnumSet.complementOf(EnumSet.of(OutcomeStatus.ACCEPTED))) {
            assertThrows(IllegalArgumentException.class, () ->
                    new Outcome(status, OutcomeOrigin.VALIDATED, Optional.of(AttemptId.of("a1"))));
        }
        assertEquals(OutcomeStatus.CANCELLED, Outcome.cancelled(OutcomeOrigin.IMPORTED_CLAIM).status());
        assertEquals(Optional.of(AttemptId.of("a2")),
                Outcome.accepted(OutcomeOrigin.VALIDATED, AttemptId.of("a2")).acceptedAttempt());
    }

    // --- provenance / policy / route / artifact ---

    @Test
    void routeAbsentValuesAreExplicit() {
        var route = RouteDescriptor.workerOnly("reviewer");
        assertTrue(route.provider().isEmpty());
        assertEquals(BillingMode.UNKNOWN, route.billingMode());
        assertThrows(IllegalArgumentException.class, () -> RouteDescriptor.workerOnly(""));
    }

    @Test
    void artifactRefDigestIsOptionalButValidatedWhenSupplied() {
        assertTrue(ArtifactRef.of("log", "logs/run-1").digest().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ArtifactRef.of("log", "logs/run-1", " "));
        assertThrows(IllegalArgumentException.class, () -> ArtifactRef.of("", "x"));
    }

    @Test
    void experienceRefRequiresPositiveRevision() {
        assertThrows(IllegalArgumentException.class, () -> new ExperienceRef(Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of("e"), Optional.empty(), EventId.of("v"), 0));
    }

    // --- price snapshot ---

    private static PriceSnapshot snapshot(List<PriceLine> lines) {
        return new PriceSnapshot("prices", "1", "provider-a", "model-x", PriceSnapshot.currency("USD"),
                LocalDate.of(2026, 10, 1), "caller supplied", "list price, no discounts", lines);
    }

    @Test
    void priceSnapshotValidatesLinesAndCurrency() {
        var input = PriceLine.of(UsageKind.INPUT_TOKENS, new BigDecimal("2"), 1_000_000);
        var cached = new PriceLine(UsageKind.CACHED_INPUT_TOKENS, new BigDecimal("0.5"), 1_000_000,
                Optional.of(UsageKind.INPUT_TOKENS));
        assertEquals(2, snapshot(List.of(input, cached)).lines().size());

        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(input, input)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(cached)), "overlap target unpriced");
        assertThrows(IllegalArgumentException.class, () -> PriceLine.of(UsageKind.INPUT_TOKENS, BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class, () -> PriceLine.of(UsageKind.INPUT_TOKENS, BigDecimal.ONE, -5));
        assertThrows(IllegalArgumentException.class,
                () -> PriceLine.of(UsageKind.INPUT_TOKENS, new BigDecimal("-0.01"), 1));
        assertThrows(IllegalArgumentException.class, () -> new PriceLine(UsageKind.INPUT_TOKENS, BigDecimal.ONE,
                1, Optional.of(UsageKind.INPUT_TOKENS)));
        assertThrows(IllegalArgumentException.class, () -> PriceSnapshot.currency("ZZZ9"));
    }

    @Test
    void priceSnapshotRejectsInclusionCycles() {
        PriceLine in = new PriceLine(UsageKind.INPUT_TOKENS, BigDecimal.ONE, 1, Optional.of(UsageKind.CACHED_INPUT_TOKENS));
        PriceLine cached = new PriceLine(UsageKind.CACHED_INPUT_TOKENS, BigDecimal.ONE, 1, Optional.of(UsageKind.INPUT_TOKENS));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(in, cached)));

        PriceLine a = new PriceLine(UsageKind.INPUT_TOKENS, BigDecimal.ONE, 1, Optional.of(UsageKind.OUTPUT_TOKENS));
        PriceLine b = new PriceLine(UsageKind.OUTPUT_TOKENS, BigDecimal.ONE, 1, Optional.of(UsageKind.REASONING_TOKENS));
        PriceLine c = new PriceLine(UsageKind.REASONING_TOKENS, BigDecimal.ONE, 1, Optional.of(UsageKind.INPUT_TOKENS));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(a, b, c)));

        PriceLine root = PriceLine.of(UsageKind.REASONING_TOKENS, BigDecimal.ONE, 1);
        assertEquals(3, snapshot(List.of(a, b, root)).lines().size(), "a chain is not a cycle");
    }

    @Test
    void artifactReferenceAllows512CodePointsAndRejects513() {
        assertEquals(512, ArtifactRef.of("log", "r".repeat(512)).reference().length());
        assertThrows(IllegalArgumentException.class, () -> ArtifactRef.of("log", "r".repeat(513)));
    }

    // --- defensive copies ---

    @Test
    void mutableCallerInputsAreCopiedAndExposedViewsAreImmutable() {
        Set<QualityDimension> dims = new HashSet<>(Set.of(QualityDimension.TESTS));
        var policy = new EvaluationPolicy("p", "1", dims);
        dims.add(QualityDimension.SECURITY);
        assertEquals(Set.of(QualityDimension.TESTS), policy.mandatoryDimensions());
        assertThrows(UnsupportedOperationException.class,
                () -> policy.mandatoryDimensions().add(QualityDimension.SECURITY));

        List<ArtifactRef> refs = new ArrayList<>(List.of(ArtifactRef.of("log", "a")));
        var obs = new QualityObservation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(),
                Optional.empty(), "t", "1", "p", "1", ObservationSource.TOOL, refs, Optional.empty());
        refs.add(ArtifactRef.of("log", "b"));
        assertEquals(1, obs.evidence().size());
        assertThrows(UnsupportedOperationException.class, () -> obs.evidence().clear());

        List<PriceLine> lines = new ArrayList<>(List.of(PriceLine.of(UsageKind.REQUESTS, BigDecimal.ONE, 1)));
        var snap = snapshot(lines);
        lines.clear();
        assertFalse(snap.lines().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snap.lines().clear());
    }
}
