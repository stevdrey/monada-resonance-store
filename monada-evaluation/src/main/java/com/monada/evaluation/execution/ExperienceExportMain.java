package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSampleJson;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only command line export of one scope of an execution ledger (contract v1, section 21.5):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runExecutionSampleExport -Dmonada.execution.dir=&lt;ledger&gt; -Dmonada.execution.scope=&lt;scope&gt;
 * </pre>
 *
 * <p>Optional properties: {@code monada.execution.cutoff} (ledger sequence, default latest),
 * {@code monada.execution.pageSize} (default 100), {@code monada.execution.view} ({@code ADAPTER_READY} or
 * {@code DIAGNOSTIC}), {@code monada.execution.gates} (comma separated extra gate dimensions) and
 * {@code monada.execution.output} ({@code summary}, the default, or {@code json} for the canonical pages). The
 * ledger is opened read-only: nothing is created, locked or written. Prices are a library-level input, so the
 * command line reports cost as unavailable or partial unless an embedding application supplies a price book.
 * Exit code 0 on success, 1 on a usage or ledger error.
 */
public final class ExperienceExportMain {
    private ExperienceExportMain() {
    }

    public static void main(String[] args) {
        Map<String, String> props = new java.util.TreeMap<>();
        System.getProperties().stringPropertyNames().stream().filter(k -> k.startsWith("monada.execution."))
                .forEach(k -> props.put(k, System.getProperty(k)));
        System.exit(run(props, System.out, System.err));
    }

    static int run(Map<String, String> props, PrintStream out, PrintStream err) {
        String dir = props.get("monada.execution.dir");
        String scope = props.get("monada.execution.scope");
        if (dir == null || scope == null) {
            err.println("usage: -Dmonada.execution.dir=<ledger root> -Dmonada.execution.scope=<scope id> "
                    + "[-Dmonada.execution.cutoff=<sequence>] [-Dmonada.execution.pageSize=<1..500>] "
                    + "[-Dmonada.execution.view=ADAPTER_READY|DIAGNOSTIC] [-Dmonada.execution.gates=TESTS,SECURITY] "
                    + "[-Dmonada.execution.output=summary|json]");
            return 1;
        }
        try {
            ScopeId scopeId = ScopeId.of(scope);
            ExportCutoff cutoff = props.containsKey("monada.execution.cutoff")
                    ? ExportCutoff.atSequence(Long.parseLong(props.get("monada.execution.cutoff")))
                    : ExportCutoff.latest();
            int pageSize = Integer.parseInt(props.getOrDefault("monada.execution.pageSize", "100"));
            ExportView view = ExportView.valueOf(props.getOrDefault("monada.execution.view", "ADAPTER_READY"));
            EnumSet<QualityDimension> gates = EnumSet.noneOf(QualityDimension.class);
            String configured = props.getOrDefault("monada.execution.gates", "");
            if (!configured.isBlank()) {
                for (String g : configured.split(",")) {
                    gates.add(QualityDimension.valueOf(g.trim()));
                }
            }
            String output = props.getOrDefault("monada.execution.output", "summary");
            if (!output.equals("summary") && !output.equals("json")) {
                err.println("monada.execution.output must be summary or json");
                return 1;
            }
            try (ExecutionMemory memory = ExecutionMemory.openReadOnly(Path.of(dir), scopeId)) {
                ExperienceExportReport report = ExperienceExportReport.build(memory,
                        ExportRequest.first(scopeId, cutoff, pageSize, view, new ExportEvidencePolicy(gates)),
                        PriceBook.empty(), Optional.empty());
                if (output.equals("json")) {
                    report.pages().forEach(p -> out.print(ExperienceSampleJson.toJson(p)));
                } else {
                    out.println("note: no price book on the command line; cost shows usage coverage only");
                    out.print(report.summary());
                }
            }
            return 0;
        } catch (RuntimeException e) {
            err.println("export failed: " + e.getMessage());
            return 1;
        }
    }
}
