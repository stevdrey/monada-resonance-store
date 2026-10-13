package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportRequest;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.execution.AnnotatedSample.Placement;
import com.monada.evaluation.execution.AnnotatedSample.PlacementStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A complete export of one scope with evaluation annotations (contract v1, sections 12 and 21). The pages are
 * exactly what {@link ExecutionMemory#exportSamples} returns; cost and Pareto placement are added here because
 * {@code monada-api} must not depend on {@code monada-evaluation}. {@link #exportDigest()} covers the sample
 * pages only, so adding prices or a comparison policy never changes it.
 *
 * <p>Read-only: it reads memory, computes in memory and writes nothing. It never records feedback and never
 * trains or routes anything; {@link ExecutionComparator#INTERPRETATION} applies to every comparison.
 */
public record ExperienceExportReport(ScopeId scope, List<ExperienceSamplePage> pages, List<AnnotatedSample> samples,
                                     Optional<ComparisonReport> comparison, String exportDigest) {

    public ExperienceExportReport {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(exportDigest, "exportDigest");
        pages = List.copyOf(pages);
        samples = List.copyOf(samples);
    }

    /**
     * Pages through the whole export that starts with {@code first} and annotates each sample as of the cutoff the
     * first page resolved. {@code first} must be a first-page request ({@link ExportRequest#first}), never a
     * {@link ExportRequest#resume} continuation. {@code policy} is optional: without it every placement is {@code NOT_REQUESTED}.
     */
    public static ExperienceExportReport build(ExecutionMemory memory, ExportRequest first, PriceBook prices,
                                               Optional<ComparisonPolicy> policy) {
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(policy, "policy");
        if (first.cursor().isPresent()) {
            throw new IllegalArgumentException("a complete report starts from a first-page request, not from a "
                    + "continuation: it would annotate only the remaining pages");
        }
        List<ExperienceSamplePage> pages = new ArrayList<>();
        ExperienceSamplePage page = memory.exportSamples(first);
        pages.add(page);
        while (page.hasMore()) {
            page = memory.exportSamples(ExportRequest.resume(page.next().get(), first.pageSize()));
            pages.add(page);
        }
        ScopeId scope = first.scope();
        long cutoff = pages.get(0).ledger().cutoffSequence();

        List<ExperienceSample> exported = pages.stream().flatMap(p -> p.samples().stream()).toList();
        List<ComparisonCandidate> candidates = new ArrayList<>();
        for (ExperienceSample s : exported) {
            ExecutionView view = memory.loadExecution(scope, s.executionId(), cutoff).orElseThrow();
            candidates.add(ComparisonCandidate.of(scope, view, prices, List.of()));
        }
        Optional<ComparisonReport> report = policy.map(p -> ExecutionComparator.report(candidates, p));
        Map<ExecutionRef, Placement> placements = report.map(ExperienceExportReport::placements).orElse(Map.of());

        List<AnnotatedSample> annotated = new ArrayList<>();
        for (int i = 0; i < exported.size(); i++) {
            ComparisonCandidate c = candidates.get(i);
            annotated.add(new AnnotatedSample(exported.get(i), c.summary().cost(),
                    placements.getOrDefault(c.ref(), Placement.notRequested())));
        }
        return new ExperienceExportReport(scope, pages, annotated, report, digestOf(pages));
    }

    private static Map<ExecutionRef, Placement> placements(ComparisonReport report) {
        Map<ExecutionRef, Placement> out = new HashMap<>();
        for (ComparisonReport.ParetoGroup g : report.groups()) {
            g.front().forEach(r -> out.put(r, new Placement(PlacementStatus.FRONT, List.of())));
            g.dominated().forEach(d -> out.put(d.execution(), new Placement(PlacementStatus.DOMINATED,
                    d.dominatedBy().stream().map(r -> "dominated by " + r).toList())));
            g.unassessed().forEach(u -> out.put(u.execution(), new Placement(PlacementStatus.UNASSESSED,
                    u.reasons().stream().map(String::valueOf).toList())));
            g.excluded().forEach(x -> out.put(x.execution(), new Placement(PlacementStatus.EXCLUDED,
                    x.reasons().stream().map(String::valueOf).toList())));
        }
        return out;
    }

    private static String digestOf(List<ExperienceSamplePage> pages) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (ExperienceSamplePage p : pages) {
                sha.update((p.digest() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    /** Plain-text, deterministic summary: one header, the export digest and one line per sample. */
    public String summary() {
        ExperienceSamplePage first = pages.get(0);
        StringBuilder out = new StringBuilder();
        out.append("schema=").append(first.schema()).append(" scope=").append(scope.value())
                .append(" view=").append(first.view()).append(" cutoff=").append(first.ledger().cutoffSequence())
                .append(" ledgerPrefixDigest=").append(first.ledger().prefixDigest()).append('\n');
        out.append("pages=").append(pages.size()).append(" samples=").append(samples.size())
                .append(" exportDigest=").append(exportDigest).append('\n');
        for (AnnotatedSample a : samples) {
            ExperienceSample s = a.sample();
            out.append(s.executionId().value()).append(" label=").append(s.label())
                    .append(" eligible=").append(s.eligibility().eligible())
                    .append(" acceptance=").append(s.acceptance())
                    .append(" cost=").append(a.cost().status());
            for (CurrencyCost c : a.cost().amounts()) {
                out.append(' ').append(c.currency().getCurrencyCode()).append(' ')
                        .append(c.amount().toPlainString()).append(a.cost().isLowerBound() ? " (lower bound)" : "");
            }
            out.append(" placement=").append(a.comparison().status());
            if (!s.eligibility().eligible()) {
                out.append(" reasons=").append(s.eligibility().reasons().stream()
                        .map(r -> r.code() + r.dimension().map(d -> "(" + d + ")").orElse("")).toList());
            }
            out.append('\n');
        }
        return out.toString();
    }
}
