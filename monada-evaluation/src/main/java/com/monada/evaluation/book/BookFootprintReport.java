package com.monada.evaluation.book;

import com.monada.evaluation.book.BookFootprintBenchmark.Check;
import com.monada.evaluation.book.BookFootprintBenchmark.Experiment;
import com.monada.evaluation.book.BookFootprintBenchmark.PhaseResult;
import com.monada.evaluation.book.BookFootprintBenchmark.Result;
import com.monada.evaluation.book.BookFootprintBenchmark.ScaleResult;
import com.monada.evaluation.book.BookFootprintSnapshot.Role;
import com.monada.storage.audit.StorageIntegrityStatistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link BookFootprintBenchmark.Result} (Issue #169) as a {@code key=value} report and a Markdown summary.
 *
 * <p>The report's sections, in order: {@code == Deterministic ==} (inputs, logical counts, the bytes of every file
 * whose content is a pure function of the chunk texts, and every check) is byte-identical across runs on one OS;
 * {@code == Measured ==} holds the atom-segment and total bytes and every ratio built on them, because each atom
 * record carries its creation {@code Instant}, whose text form varies by up to 9 characters (0, 3, 6 or 9 fraction
 * digits); {@code == Host ==} and {@code == Memory ==} identify the run and never gate it. The report holds no book
 * text: only counts, sizes, digests and statuses.
 */
final class BookFootprintReport {
    static final String VERSION = "book-footprint-v1";

    private BookFootprintReport() {
    }

    static String render(Result result) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(VERSION).append(" (Issue #169; evaluation only; exploratory)\n");
        out.append("== Deterministic ==\n");
        result.inputs().forEach((k, v) -> line(out, "input." + k, v));
        line(out, "api", BookFootprintBenchmark.API);
        line(out, "ladder", String.join(",", result.scales().stream().map(s -> s.selected().scale().spec()).toList()));
        for (ScaleResult scale : result.scales()) {
            String p = "scale." + scale.selected().scale().id();
            line(out, p + ".kind", scale.selected().scale().label());
            line(out, p + ".chunks", scale.selected().texts().size());
            line(out, p + ".uniqueAtoms", scale.selected().uniqueAtoms());
            for (PhaseResult phase : scale.phases()) {
                deterministic(out, p + "." + phase.phase(), phase);
            }
        }
        for (Experiment experiment : result.experiments()) {
            String p = "experiment." + experiment.id();
            line(out, p + ".description", experiment.description());
            for (PhaseResult phase : experiment.phases()) {
                deterministic(out, p + "." + phase.phase(), phase);
            }
        }
        line(out, "checks.total", result.checks().size());
        line(out, "checks.failed", result.failures().size());
        line(out, "checks.status", result.passed() ? "PASS" : "FAIL");
        for (Check check : result.checks()) {
            line(out, "check." + check.name(), (check.passed() ? "PASS " : "FAIL ") + "expected=" + check.expected()
                    + " actual=" + check.actual());
        }

        out.append("== Measured ==\n");
        line(out, "note", "atom-segment bytes vary by up to 9 bytes per record across runs (createdAt Instant"
                + " fraction digits); totals and ratios inherit that; not a compression ratio");
        for (ScaleResult scale : result.scales()) {
            String p = "scale." + scale.selected().scale().id();
            PhaseResult previous = null;
            for (PhaseResult phase : scale.phases()) {
                measured(out, p + "." + phase.phase(), phase, previous);
                previous = phase;
            }
        }
        for (Experiment experiment : result.experiments()) {
            PhaseResult previous = null;
            for (PhaseResult phase : experiment.phases()) {
                // Only consecutive phases of one Store are deltas; INTRA_BATCH_DUPLICATES phases are separate Stores.
                measured(out, "experiment." + experiment.id() + "." + phase.phase(), phase,
                        experiment.id().equals("OVERLAP") ? previous : null);
                previous = phase;
            }
        }

        out.append("== Host ==\n");
        result.host().forEach((k, v) -> line(out, "host." + k.replace(' ', '_'), v));

        out.append("== Memory ==\n");
        for (ScaleResult scale : result.scales()) {
            String p = "scale." + scale.selected().scale().id();
            for (PhaseResult phase : scale.phases()) {
                memory(out, p + "." + phase.phase(), phase.memory());
            }
            line(out, p + ".retainedHeapEstimate.APPROXIMATE", scale.retainedHeapEstimate());
        }
        for (Experiment experiment : result.experiments()) {
            for (PhaseResult phase : experiment.phases()) {
                memory(out, "experiment." + experiment.id() + "." + phase.phase(), phase.memory());
            }
        }
        return out.toString();
    }

    private static void deterministic(StringBuilder out, String p, PhaseResult phase) {
        BookFootprintSnapshot s = phase.snapshot();
        StorageIntegrityStatistics st = s.statistics();
        line(out, p + ".inputTexts", phase.inputTexts());
        line(out, p + ".inputTextBytes", phase.inputTextBytes());
        line(out, p + ".newAtoms", phase.newAtoms());
        line(out, p + ".newTextBytes", phase.newTextBytes());
        line(out, p + ".uniqueTextBytes", phase.uniqueTextBytes());
        line(out, p + ".uniqueTextCodePoints", phase.uniqueTextCodePoints());
        line(out, p + ".audit", s.audit().status() + " (info=" + s.audit().info() + " warnings="
                + s.audit().warnings() + " errors=" + s.audit().errors() + " fatal=" + s.audit().fatal() + ")");
        line(out, p + ".atoms", "unique=" + st.activeUniqueAtoms() + " physical=" + st.atomLogPhysicalRecords()
                + " historyDuplicates=" + st.atomHistoryDuplicates());
        line(out, p + ".vectorIndex", "entries=" + st.vectorIndexEntries() + " uniqueIds=" + st.uniqueVectorIndexIds()
                + " duplicateIds=" + st.duplicateVectorIndexIds() + " duplicateOffsets=" + st.duplicateVectorOffsets()
                + " atomsWithVector=" + st.activeAtomsWithVector() + " atomsWithoutVector="
                + st.activeAtomsWithoutVector() + " orphanVectors=" + st.vectorEntriesWithoutActiveAtom());
        line(out, p + ".feedbackRecords", st.feedbackLogRecords());
        line(out, p + ".dimensions", s.dimensions());
        line(out, p + ".vectorFrameBytes", s.frameBytes());
        for (Role role : List.of(Role.MANIFEST, Role.VECTORS, Role.VECTOR_INDEX, Role.FEEDBACK)) {
            line(out, p + ".bytes." + role.key, bytes(s.files().get(role)));
        }
        line(out, p + ".bytes.other", s.other().isEmpty() ? "none" : s.other().toString());
        line(out, p + ".unchangedFromPreviousPhase", BookFootprintBenchmark.yesNo(phase.unchanged()));
    }

    private static void measured(StringBuilder out, String p, PhaseResult phase, PhaseResult previous) {
        BookFootprintSnapshot s = phase.snapshot();
        long atoms = s.statistics().activeUniqueAtoms();
        line(out, p + ".bytes.atoms", bytes(s.files().get(Role.ATOMS)));
        line(out, p + ".bytes.total", s.totalBytes());
        line(out, p + ".storeBytesPerUniqueAtom", ratio(s.totalBytes(), atoms));
        line(out, p + ".storeBytesPerUniqueTextByte", ratio(s.totalBytes(), phase.uniqueTextBytes()));
        line(out, p + ".storeBytesPerUniqueTextCodePoint", ratio(s.totalBytes(), phase.uniqueTextCodePoints()));
        line(out, p + ".atomBytesPerUniqueTextByte", ratio(s.bytes(Role.ATOMS), phase.uniqueTextBytes()));
        line(out, p + ".vectorShareOfTotal", ratio(s.bytes(Role.VECTORS), s.totalBytes()));
        if (previous != null) {
            long delta = s.totalBytes() - previous.snapshot().totalBytes();
            line(out, p + ".deltaBytes.total", delta);
            line(out, p + ".deltaBytes.atoms", s.bytes(Role.ATOMS) - previous.snapshot().bytes(Role.ATOMS));
            line(out, p + ".appendAmplification", ratio(delta, phase.newTextBytes()));
        }
    }

    private static void memory(StringBuilder out, String p, BookMemorySampler.Sample sample) {
        if (sample == null) {
            line(out, p + ".memory", "NOT_SAMPLED (no API call in this phase)");
            return;
        }
        line(out, p + ".heap.usedBytes", sample.heapUsed());
        line(out, p + ".heap.committedBytes", sample.heapCommitted());
        line(out, p + ".heap.poolPeakSumBytes", sample.heapPoolPeakSum());
        line(out, p + ".heap.usedAfterGcBytes", sample.heapUsedAfterGc());
        line(out, p + ".nonHeap.committedBytes", sample.nonHeapCommitted());
        line(out, p + ".allocation.threadBytes", sample.allocatedBytes());
        line(out, p + ".rss.residentKiB", sample.rssKiB());
        line(out, p + ".rss.processPeakKiB", sample.rssPeakKiB());
    }

    /** The Markdown summary: one row per phase, the checks verdict and the memory samples. */
    static String summary(Result result) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(VERSION).append(" summary\n\n");
        out.append("Checks: **").append(result.passed() ? "PASS" : "FAIL").append("** (")
                .append(result.checks().size() - result.failures().size()).append(" / ")
                .append(result.checks().size()).append(" passed).\n");
        for (Check failed : result.failures()) {
            out.append("- FAIL `").append(failed.name()).append("`: expected ").append(failed.expected())
                    .append(", got ").append(failed.actual()).append('\n');
        }
        out.append("\n## Disk, per phase\n\n");
        out.append("Bytes. `atoms` and `total` vary by a few bytes per record across runs (creation timestamps).\n\n");
        out.append("| Store | Phase | Source text bytes | Unique chunks | Atoms (unique/physical) | Dim | manifest |"
                + " atoms | vectors | vectorIndex | feedback | total | B/atom | B/text byte | Append amp. |"
                + " Unchanged |\n");
        out.append("| --- | --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |"
                + " --- |\n");
        for (ScaleResult scale : result.scales()) {
            String name = scale.selected().scale().spec() + " " + scale.selected().scale().kind();
            rows(out, name, scale.phases(), true);
        }
        for (Experiment experiment : result.experiments()) {
            rows(out, experiment.id(), experiment.phases(), experiment.id().equals("OVERLAP"));
        }
        out.append("\n## Memory, per phase (informational; host-specific)\n\n");
        out.append("Heap and RSS are different measures and are not interchangeable. RSS peak is the process-lifetime"
                + " high-water mark. Allocation is this thread's only.\n\n");
        out.append("| Store | Phase | Heap used MiB | Heap committed MiB | Heap pool-peak sum MiB | Heap after GC MiB |"
                + " Thread alloc MiB | RSS MiB | RSS peak MiB |\n");
        out.append("| --- | --- | --: | --: | --: | --: | --: | --: | --: |\n");
        for (ScaleResult scale : result.scales()) {
            memoryRows(out, scale.selected().scale().spec(), scale.phases());
        }
        for (Experiment experiment : result.experiments()) {
            memoryRows(out, experiment.id(), experiment.phases());
        }
        out.append("\nRetained heap of one reopened, queried instance (APPROXIMATE, after advisory GC): ");
        List<String> retained = new ArrayList<>();
        for (ScaleResult scale : result.scales()) {
            retained.add(scale.selected().scale().spec() + " " + mib(scale.retainedHeapEstimate()) + " MiB");
        }
        out.append(String.join(", ", retained)).append(".\n");
        return out.toString();
    }

    private static void rows(StringBuilder out, String store, List<PhaseResult> phases, boolean deltas) {
        PhaseResult previous = null;
        for (PhaseResult phase : phases) {
            BookFootprintSnapshot s = phase.snapshot();
            StorageIntegrityStatistics st = s.statistics();
            String amplification = deltas && previous != null
                    ? ratio(s.totalBytes() - previous.snapshot().totalBytes(), phase.newTextBytes()) : "n/a";
            out.append("| ").append(store).append(" | ").append(phase.phase())
                    .append(" | ").append(phase.uniqueTextBytes())
                    .append(" | ").append(phase.expectedUniqueAtoms())
                    .append(" | ").append(st.activeUniqueAtoms()).append('/').append(st.atomLogPhysicalRecords())
                    .append(" | ").append(s.dimensions());
            for (Role role : Role.values()) {
                out.append(" | ").append(bytes(s.files().get(role)));
            }
            out.append(" | ").append(s.totalBytes())
                    .append(" | ").append(ratio(s.totalBytes(), st.activeUniqueAtoms()))
                    .append(" | ").append(ratio(s.totalBytes(), phase.uniqueTextBytes()))
                    .append(" | ").append(amplification)
                    .append(" | ").append(previous == null || !deltas ? "—"
                            : BookFootprintBenchmark.yesNo(phase.unchanged()))
                    .append(" |\n");
            previous = phase;
        }
    }

    private static void memoryRows(StringBuilder out, String store, List<PhaseResult> phases) {
        for (PhaseResult phase : phases) {
            BookMemorySampler.Sample m = phase.memory();
            if (m == null) {
                continue;
            }
            out.append("| ").append(store).append(" | ").append(phase.phase())
                    .append(" | ").append(mib(m.heapUsed()))
                    .append(" | ").append(mib(m.heapCommitted()))
                    .append(" | ").append(mib(m.heapPoolPeakSum()))
                    .append(" | ").append(mib(m.heapUsedAfterGc()))
                    .append(" | ").append(mibOf(m.allocatedBytes(), 1024 * 1024))
                    .append(" | ").append(mibOf(m.rssKiB(), 1024))
                    .append(" | ").append(mibOf(m.rssPeakKiB(), 1024))
                    .append(" |\n");
        }
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    /** A numeric string scaled to MiB, or the unsupported/unknown label unchanged. */
    private static String mibOf(String value, long perMiB) {
        if (!value.matches("-?[0-9]+")) {
            return value.startsWith("UNSUPPORTED") ? "UNSUPPORTED" : "UNKNOWN";
        }
        return String.format(Locale.ROOT, "%.1f", Long.parseLong(value) / (double) perMiB);
    }

    private static String bytes(long value) {
        return value == BookFootprintSnapshot.ABSENT ? "absent" : Long.toString(value);
    }

    static String ratio(long numerator, long denominator) {
        return denominator == 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", numerator / (double) denominator);
    }

    private static void line(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    /** The deterministic section of a rendered report: everything before {@code == Measured ==}. */
    static String deterministicSection(String report) {
        int end = report.indexOf("== Measured ==\n");
        return end < 0 ? report : report.substring(0, end);
    }
}
