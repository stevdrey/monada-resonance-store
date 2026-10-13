package com.monada.evaluation.execution;

import static com.monada.evaluation.execution.ComparisonFixtures.POLICY;
import static com.monada.evaluation.execution.ComparisonFixtures.PROV;
import static com.monada.evaluation.execution.ComparisonFixtures.usage;
import static com.monada.evaluation.execution.Run.A1;
import static com.monada.evaluation.execution.Run.A2;
import static com.monada.evaluation.execution.Run.SCOPE;
import static com.monada.evaluation.execution.Run.TASK;
import static com.monada.evaluation.execution.Run.book;
import static com.monada.evaluation.execution.Run.usd;
import static org.junit.jupiter.api.Assertions.*;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.evaluation.execution.AnnotatedSample.PlacementStatus;
import com.monada.evaluation.execution.ComparisonPolicy.Direction;
import com.monada.evaluation.execution.ComparisonPolicy.Metric;
import com.monada.evaluation.execution.ComparisonPolicy.Objective;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cost and comparison annotations of an export (issue #101). Prices (USD per 1M tokens): input 2, output 10, so
 * 10,000 in / 1,000 out costs 0.02 + 0.01 = 0.03.
 */
class ExperienceExportReportTest {
    @TempDir Path root;
    long directOutcome;

    private Run run(String exec) {
        return new Run(root, SCOPE, TASK, ExecutionId.of(exec), PROV, POLICY, exec + "-");
    }

    @BeforeEach
    void record() {
        try (Run direct = run("direct")) { // 10 s, 0.03 USD, validated
            direct.initial(A1);
            direct.stage(A1, "work", 0, 10, usage(10_000, 1_000));
            direct.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            direct.finish(A1, AttemptResult.COMPLETED);
            directOutcome = direct.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        }
        try (Run repaired = run("repaired")) { // 10 s + 30 s, 0.03 + 0.06 = 0.09 USD, validated
            repaired.initial(A1);
            repaired.stage(A1, "implement", 0, 10, usage(10_000, 1_000));
            repaired.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
            repaired.finish(A1, AttemptResult.FAILED);
            repaired.follow(A2, 2, AttemptReason.REPAIR, A1);
            repaired.stage(A2, "repair", 20, 50, usage(20_000, 2_000));
            repaired.evidence(A2, QualityDimension.TESTS, ObservationState.PASS);
            repaired.finish(A2, AttemptResult.COMPLETED);
            repaired.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
        }
        try (Run cheap = run("cheap")) { // cheap, but its mandatory TESTS evidence failed
            cheap.initial(A1);
            cheap.stage(A1, "work", 0, 5, usage(1_000, 100));
            cheap.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
            cheap.finish(A1, AttemptResult.COMPLETED);
            cheap.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) { // a current projection beside the ledger
            m.rebuildProjection(SCOPE);
        }
    }

    static ComparisonPolicy policy() {
        return new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
                new Objective("cost", new Metric.Cost(Currency.getInstance("USD")), Direction.MINIMIZE,
                        BigDecimal.ZERO),
                new Objective("effort", new Metric.Effort(), Direction.MINIMIZE, BigDecimal.ZERO)));
    }

    static ExportRequest everything(int pageSize) {
        return ExportRequest.first(SCOPE, ExportCutoff.latest(), pageSize, ExportView.ADAPTER_READY,
                ExportEvidencePolicy.executionPolicyOnly());
    }

    static AnnotatedSample of(ExperienceExportReport r, String exec) {
        return r.samples().stream().filter(s -> s.sample().executionId().value().equals(exec)).findFirst()
                .orElseThrow();
    }

    @Test
    void costAndParetoAreAddedBesideAnUnchangedExport() {
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, SCOPE)) {
            ExperienceExportReport report = ExperienceExportReport.build(m, everything(2), book(usd()),
                    Optional.of(policy()));
            assertEquals(List.of("direct", "repaired", "cheap"),
                    report.samples().stream().map(s -> s.sample().executionId().value()).toList());
            assertEquals(2, report.pages().size());

            AnnotatedSample direct = of(report, "direct");
            assertEquals(ExperienceSample.Label.SUCCESS, direct.sample().label());
            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, direct.cost().status());
            Run.assertDecimal("0.03", direct.cost().amounts().get(0).unrounded());
            assertEquals(PlacementStatus.FRONT, direct.comparison().status());

            // the failed first attempt is part of the chain: 0.03 + 0.06
            AnnotatedSample repaired = of(report, "repaired");
            assertEquals(ExperienceSample.Label.SUCCESS, repaired.sample().label());
            Run.assertDecimal("0.09", repaired.cost().amounts().get(0).unrounded());
            assertEquals(PlacementStatus.DOMINATED, repaired.comparison().status());
            assertEquals(List.of("dominated by scope-1/direct"), repaired.comparison().details());

            // cheap but failing a mandatory gate: no cost to an accepted outcome, never a winner
            AnnotatedSample cheap = of(report, "cheap");
            assertEquals(ExperienceSample.Label.UNLABELED, cheap.sample().label());
            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, cheap.cost().status());
            assertTrue(cheap.cost().amounts().isEmpty());
            assertEquals(PlacementStatus.EXCLUDED, cheap.comparison().status());
            assertEquals(ExecutionComparator.INTERPRETATION, report.comparison().get().interpretation());

            // annotations never change the exported samples or their digest
            ExperienceExportReport plain = ExperienceExportReport.build(m, everything(2), PriceBook.empty(),
                    Optional.empty());
            assertEquals(report.exportDigest(), plain.exportDigest());
            assertEquals(report.pages(), plain.pages());
            assertEquals(List.of(PlacementStatus.NOT_REQUESTED), plain.samples().stream()
                    .map(s -> s.comparison().status()).distinct().toList());
            assertEquals(CostToAcceptedOutcome.Status.PARTIAL, of(plain, "direct").cost().status()); // no prices
            assertTrue(plain.comparison().isEmpty());

            // same report page after page, whatever the page size
            ExperienceExportReport single = ExperienceExportReport.build(m, everything(500), book(usd()),
                    Optional.of(policy()));
            assertEquals(report.samples(), single.samples());
        }
    }

    @Test
    void costIsComputedAsOfTheExportCutoff() {
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, SCOPE)) {
            ExperienceExportReport beforeAcceptance = ExperienceExportReport.build(m,
                    ExportRequest.first(SCOPE, ExportCutoff.atSequence(directOutcome - 1), 10,
                            ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly()),
                    book(usd()), Optional.empty());
            assertEquals(1, beforeAcceptance.samples().size());
            AnnotatedSample direct = of(beforeAcceptance, "direct");
            assertEquals(ExperienceSample.Label.UNLABELED, direct.sample().label()); // outcome not yet recorded
            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, direct.cost().status());

            ExperienceExportReport atAcceptance = ExperienceExportReport.build(m,
                    ExportRequest.first(SCOPE, ExportCutoff.atSequence(directOutcome), 10, ExportView.ADAPTER_READY,
                            ExportEvidencePolicy.executionPolicyOnly()), book(usd()), Optional.empty());
            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, of(atAcceptance, "direct").cost().status());
            assertEquals(1, atAcceptance.samples().size()); // later executions had not started
        }
    }

    @Test
    void buildingAReportLeavesEveryStorageFileByteIdentical() throws Exception {
        Map<String, String> before = digests();
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            ExperienceExportReport.build(m, everything(1), book(usd()), Optional.of(policy()));
        }
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, SCOPE)) {
            ExperienceExportReport.build(m, everything(3), book(usd()), Optional.of(policy())).summary();
        }
        assertEquals(before, digests());
    }

    private Map<String, String> digests() throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        assertTrue(out.keySet().stream().anyMatch(f -> f.endsWith("projection.current")), out.keySet().toString());
        assertTrue(out.keySet().stream().anyMatch(f -> f.contains("feedback")), out.keySet().toString());
        return out;
    }

    @Test
    void summaryIsDeterministicAndStatesReasonsAndLowerBounds() {
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, SCOPE)) {
            String a = ExperienceExportReport.build(m, everything(2), book(usd()), Optional.of(policy())).summary();
            String b = ExperienceExportReport.build(m, everything(2), book(usd()), Optional.of(policy())).summary();
            assertEquals(a, b);
            assertTrue(a.contains("direct label=SUCCESS eligible=true acceptance=VALIDATED_ACCEPTED "
                    + "cost=AVAILABLE USD 0.030000 placement=FRONT"), a);
            assertTrue(a.contains("cheap label=UNLABELED eligible=false acceptance=ACCEPTED_UNVALIDATED "
                    + "cost=UNAVAILABLE placement=EXCLUDED reasons=[GATE_FAIL(TESTS)]"), a);
            assertTrue(a.startsWith("schema=execution-sample/1 scope=scope-1 view=ADAPTER_READY cutoff="), a);
            String partial = ExperienceExportReport.build(m, everything(2), PriceBook.empty(), Optional.empty())
                    .summary();
            assertFalse(partial.contains(" USD "), partial);
        }
    }

    @Test
    void commandLineExportIsReadOnlyDeterministicAndReportsErrors() throws Exception {
        Map<String, String> before = digests();
        Map<String, String> props = Map.of("monada.execution.dir", root.toString(),
                "monada.execution.scope", "scope-1", "monada.execution.pageSize", "2");
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, ExperienceExportMain.run(props, new PrintStream(out1, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertEquals(0, ExperienceExportMain.run(props, new PrintStream(out2, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertEquals(out1.toString(StandardCharsets.UTF_8), out2.toString(StandardCharsets.UTF_8));
        String text = out1.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("pages=2 samples=3 exportDigest="), text);
        assertEquals("", err.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream json = new ByteArrayOutputStream();
        Map<String, String> jsonProps = new TreeMap<>(props);
        jsonProps.put("monada.execution.output", "json");
        jsonProps.put("monada.execution.view", "DIAGNOSTIC");
        jsonProps.put("monada.execution.gates", "SECURITY");
        assertEquals(0, ExperienceExportMain.run(jsonProps, new PrintStream(json, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        String body = json.toString(StandardCharsets.UTF_8);
        // two pages -> one valid document: an array, never consecutive top-level objects
        assertTrue(body.startsWith("[\n  {\n    \"schema\": \"execution-sample/1\""), body);
        assertTrue(body.endsWith("  }\n]\n"), body);
        assertEquals(2, body.split("\n  \\{\n", -1).length - 1);
        assertEquals(1, body.split("\n  \\},\n  \\{\n", -1).length - 1);
        assertTrue(body.contains("\"view\": \"DIAGNOSTIC\"") && body.contains("\"additionalGates\": [\n      \"SECURITY\""));

        // a pinned cutoff reproduces the same export
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            Map<String, String> pinned = new TreeMap<>(props);
            pinned.put("monada.execution.cutoff", Long.toString(directOutcome));
            assertEquals(0, ExperienceExportMain.run(pinned, new PrintStream(o, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8)));
            outputs.add(o.toString(StandardCharsets.UTF_8));
        }
        assertEquals(outputs.get(0), outputs.get(1));
        assertTrue(outputs.get(0).contains("samples=1 "), outputs.get(0));

        assertEquals(before, digests());
    }

    @Test
    void commandLineRejectsBadUsageWithoutCreatingAnything() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(1, ExperienceExportMain.run(Map.of(), out, errors));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("usage:"));
        Path missing = root.resolve("absent");
        assertEquals(1, ExperienceExportMain.run(Map.of("monada.execution.dir", missing.toString(),
                "monada.execution.scope", "scope-1"), out, errors));
        assertFalse(Files.exists(missing));
        assertEquals(1, ExperienceExportMain.run(Map.of("monada.execution.dir", root.toString(),
                "monada.execution.scope", "scope-1", "monada.execution.view", "NOPE"), out, errors));
        assertEquals(1, ExperienceExportMain.run(Map.of("monada.execution.dir", root.toString(),
                "monada.execution.scope", "scope-1", "monada.execution.pageSize", "0"), out, errors));
        assertEquals(1, ExperienceExportMain.run(Map.of("monada.execution.dir", root.toString(),
                "monada.execution.scope", "scope-1", "monada.execution.output", "xml"), out, errors));
        assertEquals(1, ExperienceExportMain.run(Map.of("monada.execution.dir", root.toString(),
                "monada.execution.scope", "scope-1", "monada.execution.cutoff", "999999"), out, errors));
    }
}
