package com.monada.api.execution;

import static com.monada.api.execution.ExperienceSampleExportTest.exportAll;
import static com.monada.api.execution.ExperienceSampleExportTest.samples;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExperienceSample.Label;
import com.monada.api.execution.ExperienceSample.SampleAttempt;
import com.monada.api.execution.ExperienceSample.UsageState;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.UsageKind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executable example of how a host or a Neuron adapter consumes exported samples (contract v1, section 21.6).
 *
 * <p>Ownership shown here: Store hands out neutral, provenance-preserving observations; the <b>adapter</b> (in
 * the consumer's own repository, not here) decides what to learn from them. This test therefore contains no
 * learning, routing or scoring, no network and no dependency on Neuron or Forge: it only reads samples and maps
 * them to plain rows the way a consumer's own data loader would. It calls neither {@code feedback()} nor any
 * write operation of the memory.
 */
class AdapterConsumerExampleTest {
    static final ScopeId SCOPE = ScopeId.of("scope-1");
    @TempDir Path root;

    /** What a consumer might keep per sample; the shape is the consumer's choice, not Store's. */
    record Row(String execution, String label, String route, boolean usageFullyKnown, String exactSource) {
    }

    /** A consumer reading rule: use only eligible samples, keep the ineligible ones aside with their reasons. */
    static Row toRow(ExperienceSample s) {
        // the route that produced the accepted result, or the last attempt's route for a failure
        SampleAttempt attempt = s.attempts().stream().filter(SampleAttempt::accepted).findFirst()
                .orElse(s.attempts().get(s.attempts().size() - 1));
        String route = attempt.stages().isEmpty() ? "none-recorded" : attempt.stages().get(0).route().worker()
                + "/" + attempt.stages().get(0).route().model().orElse("unknown-model");
        boolean known = !attempt.stages().isEmpty() && attempt.stages().stream().allMatch(st -> st.usage().stream()
                .filter(u -> u.kind() == UsageKind.INPUT_TOKENS || u.kind() == UsageKind.OUTPUT_TOKENS)
                .allMatch(u -> u.state() == UsageState.REPORTED || u.state() == UsageState.ESTIMATED));
        return new Row(s.executionId().value(), s.label().name(), route, known,
                s.execution().eventId().value() + "@" + s.execution().revision());
    }

    @Test
    void aConsumerSplitsEligibleSamplesFromIneligibleOnesWithoutAnyAdaptation() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            new SampleLedger(memory, SCOPE, "p").standardRun();
            long sequenceBefore = memory.history(SCOPE).highWatermark();

            // 1. pull bounded pages with an explicit cutoff; persist nothing but the cursor token, if you like
            List<ExperienceSample> all = samples(exportAll(memory, ExportRequest.first(SCOPE,
                    ExportCutoff.atSequence(sequenceBefore), 3, ExportView.ADAPTER_READY,
                    ExportEvidencePolicy.executionPolicyOnly())));

            // 2. the adapter's own policy: eligible samples become rows, the rest is kept with its reasons
            // (a success counts once per group of repeated work: keep successOrdinal 1 only)
            List<Row> rows = all.stream().filter(s -> s.eligibility().eligible())
                    .filter(s -> s.successOrdinal().orElse(1) == 1).map(AdapterConsumerExampleTest::toRow).toList();
            List<String> skipped = all.stream().filter(s -> !s.eligibility().eligible())
                    .map(s -> s.executionId().value() + ": " + s.eligibility().reasons().get(0).code()).toList();

            // x-ok2 repeats the validated work of x-ok (successOrdinal 2), so this consumer drops it
            assertEquals(List.of("x-ok:SUCCESS:worker-b/large:false", "x-rej:FAILURE:worker-a/small:false"),
                    rows.stream().map(r -> r.execution() + ":" + r.label() + ":" + r.route() + ":"
                            + r.usageFullyKnown()).toList());
            assertEquals(List.of("x-pending: OUTCOME_PENDING", "x-unk: GATE_UNKNOWN",
                    "x-claim: OUTCOME_IMPORTED_CLAIM", "x-run: OUTCOME_PENDING"), skipped);
            assertTrue(rows.stream().allMatch(r -> r.exactSource().endsWith("@1")));

            // 3. the unknown-usage success is still a success, but the consumer can see its usage is incomplete
            Row success = rows.get(0);
            assertEquals(Label.SUCCESS.name(), success.label());
            assertTrue(!success.usageFullyKnown());

            // 4. Store did nothing on the consumer's behalf: no append, no feedback, no projection change
            assertEquals(sequenceBefore, memory.history(SCOPE).highWatermark());
        }
    }
}
