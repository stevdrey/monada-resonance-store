package com.monada.api.execution;

import static com.monada.api.execution.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.monada.api.execution.DerivedAcceptance.Status;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DerivedAcceptanceTest {
    @TempDir Path root;

    private Status statusWith(ObservationState tests, Outcome outcome) {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, tests);
            m.record(outcome("e6", outcome));
            return m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status();
        }
    }

    @Test
    void passOrJustifiedNotApplicableIsValidatedAcceptance() {
        assertEquals(Status.VALIDATED_ACCEPTED,
                statusWith(ObservationState.PASS, Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
    }

    @Test
    void notApplicableWithJustificationIsValidatedAcceptance() {
        assertEquals(Status.VALIDATED_ACCEPTED,
                statusWith(ObservationState.NOT_APPLICABLE, Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
    }

    @Test
    void failingOrUnknownEvidenceCannotBeValidated() {
        assertEquals(Status.ACCEPTED_UNVALIDATED,
                statusWith(ObservationState.FAIL, Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
    }

    @Test
    void unknownEvidenceCannotBeValidated(@TempDir Path other) {
        try (ExecutionMemory m = ExecutionMemory.open(other, SCOPE)) {
            baseRun(m, ObservationState.UNKNOWN);
            m.record(outcome("e6", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            DerivedAcceptance a = m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance();
            assertEquals(Status.ACCEPTED_UNVALIDATED, a.status());
            assertFalse(a.isValidatedAcceptance());
            assertTrue(a.reasons().get(0).contains("TESTS"));
        }
    }

    @Test
    void missingRequiredEvidenceCannotBeValidated() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            m.record(finished("e3", A1));
            m.record(outcome("e4", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            assertEquals(Status.ACCEPTED_UNVALIDATED, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
        }
    }

    @Test
    void importedClaimStaysDistinctFromValidation() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.PASS);
            m.record(outcome("e6", Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM, A1)));
            ExecutionView v = m.loadExecution(SCOPE, EXEC).orElseThrow();
            assertEquals(OutcomeOrigin.IMPORTED_CLAIM, v.currentOutcome().orElseThrow().origin());
            assertEquals(Status.ACCEPTED_UNVALIDATED, v.acceptance().status());
        }
    }

    @Test
    void failedRejectedAndCancelledOutcomesNeverImplyAcceptance() {
        assertEquals(Status.NOT_ACCEPTED, statusWith(ObservationState.PASS, Outcome.failed(OutcomeOrigin.VALIDATED)));
    }

    @Test
    void rejectedAndCancelledAreNotAccepted(@TempDir Path a, @TempDir Path b) {
        for (Path dir : new Path[] {a, b}) {
            try (ExecutionMemory m = ExecutionMemory.open(dir, SCOPE)) {
                baseRun(m, ObservationState.PASS);
                m.record(outcome("e6", dir == a ? Outcome.rejected(OutcomeOrigin.VALIDATED)
                        : Outcome.cancelled(OutcomeOrigin.VALIDATED)));
                assertEquals(Status.NOT_ACCEPTED, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
            }
        }
    }

    @Test
    void acceptedOutcomeOnAFailedAttemptIsNotValidated() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.PASS);
            m.record(correctFinish("e6", "e5", 2, A1)); // finish becomes FAILED
            m.record(outcome("e7", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            DerivedAcceptance a = m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance();
            assertEquals(Status.ACCEPTED_UNVALIDATED, a.status());
            assertTrue(a.reasons().get(0).contains("FAILED"));
        }
    }

    @Test
    void acceptedOutcomeOnAnUnfinishedAttemptIsNotValidated() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            m.record(evidence("e3", A1, ObservationState.PASS, "2"));
            m.record(outcome("e4", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            DerivedAcceptance a = m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance();
            assertEquals(Status.ACCEPTED_UNVALIDATED, a.status());
            assertTrue(a.reasons().get(0).contains("not finished"));
        }
    }

    @Test
    void laterPassOverridesEarlierFailInTheSameAttempt() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            m.record(evidence("e3", A1, ObservationState.FAIL, "2"));
            m.record(evidence("e4", A1, ObservationState.PASS, "2"));
            m.record(finished("e5", A1));
            m.record(outcome("e6", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            assertEquals(Status.VALIDATED_ACCEPTED, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
        }
    }

    @Test
    void evidenceOnlyOnAnotherAttemptDoesNotValidateTheAcceptedOne() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            m.record(evidence("e3", A1, ObservationState.PASS, "2"));
            m.record(finished("e4", A1));
            m.record(attempt("e5", A2, 2));
            m.record(finished("e6", A2));
            m.record(outcome("e7", Outcome.accepted(OutcomeOrigin.VALIDATED, A2)));
            assertEquals(Status.ACCEPTED_UNVALIDATED, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
        }
    }

    @Test
    void cancelledAndPending(@TempDir Path other) {
        try (ExecutionMemory m = ExecutionMemory.open(other, SCOPE)) {
            m.record(started("e1"));
            assertEquals(Status.PENDING, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
            m.record(outcome("e2", Outcome.cancelled(OutcomeOrigin.VALIDATED)));
            assertEquals(Status.NOT_ACCEPTED, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
            m.record(outcome("e3", Outcome.pending(OutcomeOrigin.VALIDATED)));
            assertEquals(Status.PENDING, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
        }
    }

    @Test
    void laterEvidenceOverridesAndOtherPolicyVersionAndOtherAttemptAreIgnored() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.FAIL);
            m.record(attempt("e6", A2, 2));
            m.record(evidence("e7", A2, ObservationState.PASS, "2"));
            m.record(finished("e8", A2));
            m.record(outcome("e9", Outcome.accepted(OutcomeOrigin.VALIDATED, A2)));
            assertEquals(Status.VALIDATED_ACCEPTED, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
        }
    }

    @Test
    void policyV1ObservationDoesNotSatisfyPolicyV2(@TempDir Path other) {
        try (ExecutionMemory m = ExecutionMemory.open(other, SCOPE)) {
            m.record(started("e1"));
            m.record(attempt("e2", A1, 1));
            m.record(evidence("e3", A1, ObservationState.PASS, "1"));
            m.record(finished("e4", A1));
            m.record(outcome("e5", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            assertEquals(Status.ACCEPTED_UNVALIDATED, m.loadExecution(SCOPE, EXEC).orElseThrow().acceptance().status());
        }
    }

    @Test
    void renewedEvidenceIsANewRevisionThatRetainsHistory() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            baseRun(m, ObservationState.PASS);
            m.record(outcome("e6", Outcome.accepted(OutcomeOrigin.VALIDATED, A1)));
            m.record(correctEvidence("e7", "e4", 2, A1, ObservationState.FAIL));
            ExecutionView v = m.loadExecution(SCOPE, EXEC).orElseThrow();
            assertEquals(Status.ACCEPTED_UNVALIDATED, v.acceptance().status());
            assertEquals(7, v.history().size());
        }
    }
}
