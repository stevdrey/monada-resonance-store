package com.monada.evaluation.book;

import com.monada.evaluation.book.BookLatencyBenchmark.Condition;
import com.monada.evaluation.book.BookLatencyBenchmark.Config;
import com.monada.evaluation.book.BookLatencyBenchmark.Result;
import com.monada.evaluation.book.BookLatencyBenchmark.ScaleResult;
import com.monada.evaluation.book.BookLatencyBenchmark.Series;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Renders a book latency run (Issue #168) twice: a machine-readable {@code key=value} report and a short Markdown
 * summary. The report's first part ({@link #renderDeterministic}) depends only on the inputs and the configuration,
 * so two runs over the same corpus and options produce it byte for byte; host facts and timings follow it.
 */
final class BookLatencyReport {
    static final String FORMAT = "monada-book-latency-report";
    static final int FORMAT_VERSION = 1;
    static final String TIMINGS_HEADER = "== Host ==";

    private BookLatencyReport() {
    }

    static String render(Result result) {
        StringBuilder out = new StringBuilder(renderDeterministic(result));
        out.append(TIMINGS_HEADER).append('\n');
        result.host().forEach((k, v) -> line(out, "host." + k.replace(' ', '_'), v));
        out.append("\n== Timings (informational; microseconds unless named otherwise) ==\n");
        for (ScaleResult scale : result.scales()) {
            String prefix = "scale." + scale.selected().scale().id() + ".";
            line(out, prefix + "storeBytes", String.valueOf(scale.storeBytes()));
            for (Series series : scale.series()) {
                BookLatencyStats s = series.stats();
                String key = prefix + series.key() + ".";
                line(out, key + "timed", series.timed());
                line(out, key + "n", String.valueOf(s.n()));
                line(out, key + "minMicros", micros(s.min(), s.n()));
                line(out, key + "p50Micros", BookLatencyStats.micros(s.p50(), s.n(), BookLatencyStats.MIN_P50));
                line(out, key + "p95Micros", BookLatencyStats.micros(s.p95(), s.n(), BookLatencyStats.MIN_P95));
                line(out, key + "p99Micros", BookLatencyStats.micros(s.p99(), s.n(), BookLatencyStats.MIN_P99));
                line(out, key + "maxMicros", micros(s.max(), s.n()));
                line(out, key + "meanMicros", BookLatencyStats.fmt(s.mean() / 1_000.0));
                line(out, key + "stddevMicros", BookLatencyStats.fmt(s.stddev() / 1_000.0));
                if (series.phase().equals("ingest.rememberAll")) {
                    BookLatencyScale.Selected sel = scale.selected();
                    line(out, key + "chunksPerSecond", BookLatencyStats.fmt(
                            BookLatencyStats.perSecond((double) sel.texts().size() * s.n(), s.totalNanos())));
                    line(out, key + "mibPerSecond", fmt3(BookLatencyStats.perSecond(
                            (double) sel.textBytes() * s.n() / (1024.0 * 1024.0), s.totalNanos())));
                } else if (series.phase().equals("query")) {
                    line(out, key + "queriesPerSecond", BookLatencyStats.fmt(
                            BookLatencyStats.perSecond(s.n(), s.totalNanos())));
                }
            }
        }
        return out.toString();
    }

    /** Everything that identifies the run and does not depend on the clock or the host. */
    static String renderDeterministic(Result result) {
        Config config = result.config();
        StringBuilder out = new StringBuilder();
        out.append("Monada book latency report (Issue #168, Book 6/8)\n");
        line(out, "format", FORMAT);
        line(out, "formatVersion", String.valueOf(FORMAT_VERSION));
        line(out, "status", "EXPLORATORY: wall-clock timings from one host; informational only, no threshold, gate"
                + " or SLA; OS cold cache never measured");
        out.append("\n== Inputs ==\n");
        result.inputs().forEach((k, v) -> line(out, k, v));
        line(out, "queries.count", String.valueOf(result.queries().size()));
        line(out, "queries.textsSha256", VerifiedBookSource.sha256(String.join("\n", result.queries())
                .getBytes(StandardCharsets.UTF_8)));
        line(out, "queries.order", "dataset order by query id; every pass runs all of them in this order");

        out.append("\n== Config ==\n");
        line(out, "ladder", config.ladder().stream().map(BookLatencyScale::spec).collect(Collectors.joining(",")));
        line(out, "selection", "p% keeps ordinals i with floor((i+1)p/100) > floor(ip/100) (evenly spaced);"
                + " xN keeps every chunk plus N-1 replicas '[replica r] ' + text");
        line(out, "ingest", config.ingestWarmups() + " warmup + " + config.ingestTrials()
                + " measured trials, each a new empty Store directory");
        line(out, "open", config.openWarmups() + " warmup + " + config.openTrials()
                + " measured opens of the last ingested Store, each followed by one timed first query");
        line(out, "query", config.queryWarmupPasses() + " warmup + " + config.queryPasses()
                + " measured passes over every query on one instance");
        line(out, "freshProcess", result.childClasspathStatus());
        line(out, "api", BookLatencyBenchmark.API);
        line(out, "profile", BookRecallEvaluator.PROFILE + " (feedback-free Stores)");
        line(out, "timer", "System.nanoTime() around each call; one sample per call");
        line(out, "percentiles", "nearest-rank, no interpolation; p50 n>=" + BookLatencyStats.MIN_P50 + ", p95 n>="
                + BookLatencyStats.MIN_P95 + ", p99 n>=" + BookLatencyStats.MIN_P99 + ", else n/a;"
                + " stddev is the sample one (n-1)");
        line(out, "conditions", "IN_PROCESS new empty dir; IN_PROCESS_REPEATED repeated opens in this JVM;"
                + " IN_PROCESS_NEW_INSTANCE first query on a just-opened instance; IN_PROCESS_WARM after warmup"
                + " passes; STANDALONE_DIAGNOSTIC encode only; FRESH_PROCESS_FIRST first child JVM of a scale;"
                + " FRESH_PROCESS_REPEATED later child JVMs. Page cache: " + BookLatencyHost.PAGE_CACHE);
        line(out, "excluded", "chunking, corpus verification, parity passes, directory setup and cleanup, report"
                + " writing, JVM startup (except processWall)");
        line(out, "unmeasured", "OS cold cache; storage durability beyond what the API itself does; JIT and GC"
                + " attribution; concurrent queries; ANN or caches (none exist)");

        out.append("\n== Scales ==\n");
        for (ScaleResult scale : result.scales()) {
            BookLatencyScale.Selected sel = scale.selected();
            String prefix = "scale." + sel.scale().id() + ".";
            line(out, prefix + "spec", sel.scale().spec());
            line(out, prefix + "kind", sel.scale().label());
            line(out, prefix + "chunks", String.valueOf(sel.texts().size()));
            line(out, prefix + "uniqueAtoms", String.valueOf(sel.uniqueAtoms()));
            line(out, prefix + "textBytes", String.valueOf(sel.textBytes()));
            line(out, prefix + "ordinalsSha256", VerifiedBookSource.sha256(sel.ordinals().stream()
                    .map(String::valueOf).collect(Collectors.joining(",")).getBytes(StandardCharsets.UTF_8)));
            line(out, prefix + "dimensions", String.valueOf(scale.dimensions()));
            line(out, prefix + "resultsSha256", scale.resultsDigest());
            line(out, prefix + "freshProcess", scale.freshProcess());
            line(out, prefix + "samples", scale.series().stream().map(s -> s.key() + "=" + s.nanos().size())
                    .collect(Collectors.joining(" ")));
        }

        out.append("\n== Parity (top-").append(BookLatencyChild.K).append(" ids, order and scores) ==\n");
        line(out, "parity.status", result.parity().passed() ? "PASS" : "FAIL");
        line(out, "parity.checks", String.valueOf(result.parity().checks()));
        line(out, "parity.mismatches", String.valueOf(result.parity().mismatches()));
        for (int i = 0; i < result.parity().details().size(); i++) {
            line(out, "parity.mismatch." + i, result.parity().details().get(i));
        }
        out.append('\n');
        return out.toString();
    }

    /** A short human summary: one row per scale, the in-process and fresh-process medians and tails. */
    static String summary(Result result) {
        StringBuilder out = new StringBuilder();
        out.append("# Book latency summary (Issue #168)\n\n");
        out.append("Exploratory, one host, informational only. Page cache: warm or unknown (never dropped); no row is"
                + " an OS cold-cache measurement. Percentiles are nearest-rank; `n/a` means too few samples.\n\n");
        Map<String, String> host = result.host();
        out.append("Host: ").append(host.get("cpu")).append(", ").append(host.get("processors"))
                .append(" processors, ").append(host.get("java.vm")).append(", GC ").append(host.get("gc"))
                .append(", max heap ").append(host.get("max heap")).append(", scratch ")
                .append(host.get("scratch file store")).append(".\n\n");
        out.append("Parity: ").append(result.parity().passed() ? "PASS" : "FAIL").append(" (")
                .append(result.parity().checks()).append(" top-").append(BookLatencyChild.K)
                .append(" comparisons, ").append(result.parity().mismatches()).append(" mismatches).\n\n");
        out.append("| scale | kind | chunks | text KiB | store KiB | ingest p50 ms | ingest chunks/s | open p50 µs"
                + " (in-process) | first query p50 µs | query p50 / p95 / p99 µs (n) | fresh open µs first / repeated p50"
                + " | fresh query p50 / p95 µs (n, repeated) |\n");
        out.append("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (ScaleResult scale : result.scales()) {
            BookLatencyScale.Selected sel = scale.selected();
            BookLatencyStats ingest = stats(scale, "ingest.rememberAll", Condition.IN_PROCESS);
            BookLatencyStats open = stats(scale, "open", Condition.IN_PROCESS_REPEATED);
            BookLatencyStats first = stats(scale, "firstQuery", Condition.IN_PROCESS_NEW_INSTANCE);
            BookLatencyStats query = stats(scale, "query", Condition.IN_PROCESS_WARM);
            Optional<BookLatencyStats> freshFirst = find(scale, "open", Condition.FRESH_PROCESS_FIRST);
            Optional<BookLatencyStats> freshOpen = find(scale, "open", Condition.FRESH_PROCESS_REPEATED);
            Optional<BookLatencyStats> freshQuery = find(scale, "query", Condition.FRESH_PROCESS_REPEATED);
            out.append("| ").append(sel.scale().spec())
                    .append(" | ").append(sel.scale().kind())
                    .append(" | ").append(sel.texts().size())
                    .append(" | ").append(BookLatencyStats.fmt(sel.textBytes() / 1024.0))
                    .append(" | ").append(BookLatencyStats.fmt(scale.storeBytes() / 1024.0))
                    .append(" | ").append(ms(ingest))
                    .append(" | ").append(BookLatencyStats.fmt(BookLatencyStats.perSecond(
                            (double) sel.texts().size() * ingest.n(), ingest.totalNanos())))
                    .append(" | ").append(p50(open))
                    .append(" | ").append(p50(first))
                    .append(" | ").append(tails(query, true))
                    .append(" | ").append(freshFirst.map(BookLatencyReport::p50).orElse("not run")).append(" / ")
                    .append(freshOpen.map(BookLatencyReport::p50).orElse("n/a"))
                    .append(" | ").append(freshQuery.map(s -> tails(s, false)).orElse("n/a"))
                    .append(" |\n");
        }
        out.append("\nREAL rows ingest the frozen chunks unmodified; DERIVED rows add replica-tagged copies (real"
                + " vocabulary and length, not real-book shape). The machine-readable report holds every series.\n");
        return out.toString();
    }

    private static BookLatencyStats stats(ScaleResult scale, String phase, Condition condition) {
        return find(scale, phase, condition).orElseThrow();
    }

    private static Optional<BookLatencyStats> find(ScaleResult scale, String phase, Condition condition) {
        return scale.series().stream().filter(s -> s.phase().equals(phase) && s.condition() == condition)
                .findFirst().map(Series::stats);
    }

    private static String ms(BookLatencyStats s) {
        return s.p50().isPresent() ? String.format(Locale.ROOT, "%.2f", s.p50().getAsLong() / 1_000_000.0) : "n/a";
    }

    private static String p50(BookLatencyStats s) {
        return BookLatencyStats.micros(s.p50(), s.n(), BookLatencyStats.MIN_P50);
    }

    private static String tails(BookLatencyStats s, boolean withP99) {
        String p95 = s.p95().isPresent() ? BookLatencyStats.fmt(s.p95().getAsLong() / 1_000.0) : "n/a";
        String p99 = s.p99().isPresent() ? BookLatencyStats.fmt(s.p99().getAsLong() / 1_000.0) : "n/a";
        return p50(s) + " / " + p95 + (withP99 ? " / " + p99 : "") + " (" + s.n() + ")";
    }

    private static String micros(long nanos, int n) {
        return n == 0 ? "n/a" : BookLatencyStats.fmt(nanos / 1_000.0);
    }

    private static String fmt3(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value.replace('\n', ' ')).append('\n');
    }

    /** The deterministic part of a rendered report: everything before {@link #TIMINGS_HEADER}. */
    static String deterministicPart(String report) {
        int at = report.indexOf(TIMINGS_HEADER + "\n");
        return at < 0 ? report : report.substring(0, at);
    }

    static List<String> keys(String report) {
        return report.lines().filter(l -> l.indexOf('=') > 0).map(l -> l.substring(0, l.indexOf('='))).toList();
    }
}
