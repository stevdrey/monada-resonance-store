package com.monada.evaluation.book;

import com.monada.evaluation.book.BookHubConcentration.Chunk;
import com.monada.evaluation.book.BookHubConcentration.Distribution;
import com.monada.evaluation.book.BookHubConcentration.Occurrence;
import com.monada.evaluation.book.BookHubProbe.ChunkMetrics;
import com.monada.evaluation.book.BookHubProbe.Contribution;
import com.monada.evaluation.book.BookHubProbe.Control;
import com.monada.evaluation.book.BookHubProbe.PairScore;
import com.monada.evaluation.book.BookHubProbe.Result;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Renders a {@link BookHubProbe.Result} as a versioned, deterministic plain-text report (Issue #176): no paths,
 * timings, host details or book text; fixed number formats; chunks in a stated order. It reports observations only;
 * interpreting them is left to {@code HUB-DIAGNOSTICS.md}, which must not claim a cause the numbers do not show.
 */
final class BookHubReport {
    static final String FORMAT = "monada-book-hub-diagnostics";
    static final int FORMAT_VERSION = 1;

    private BookHubReport() {
    }

    static String render(Result result) {
        StringBuilder out = new StringBuilder();
        line(out, "Monada book hub-concentration diagnostics (Issue #176, evaluation only)");
        line(out, "format=" + FORMAT);
        line(out, "formatVersion=" + FORMAT_VERSION);
        line(out, "status=DIAGNOSTIC: counts and measurements of the frozen book-recall-v1 baseline; no ranking,"
                + " encoder or default is changed; correlations are not causes; HOLDOUT is counted only, never used"
                + " to choose controls or anything else");
        line(out, "");
        line(out, "== Inputs ==");
        result.recall().identity().fields().forEach((k, v) -> line(out, k + "=" + v));
        line(out, "baseline.report=" + BookRecallReport.FORMAT + " v" + BookRecallReport.FORMAT_VERSION
                + ", reproduced byte for byte before any diagnostic");
        line(out, "baseline.report.sha256=" + result.baselineSha256());
        line(out, "");
        definitions(out);
        parity(out, result);
        summary(out, result.distribution());
        distribution(out, result.distribution());
        references(out, result.distribution());
        controls(out, result);
        controlMetrics(out, result);
        corpusMetrics(out, result);
        decomposition(out, result);
        notMeasured(out);
        return out.toString();
    }

    private static void definitions(StringBuilder out) {
        line(out, "== Definitions ==");
        line(out, "top-K count = queries that ranked the chunk's atom within the first K of the frozen top-"
                + BookRecallEvaluator.MAX_K + "; repeated text is one atom, counted once per query under its first"
                + " key (aliases listed)");
        line(out, "occurrences = query-hit pairs (a query contributes up to K); distinct = different atoms among them");
        line(out, "labels: E2/E1 judged evidence, C0 judged confounder, U unjudged for that query (not assumed"
                + " irrelevant)");
        line(out, "tokens = production weighted tokens of MonadaMemoryOptions.defaults(): original = normalized"
                + " terms (weight 1.0), expansion = lexical expansions (weight 0.25); stop words already removed");
        line(out, "topTok = occurrences of the chunk's most frequent original token (token not named);"
                + " nz = non-zero buckets of the stored vector; maxAbs = largest |component|; norm = L2 norm");
        line(out, "centroidCos = cosine (double arithmetic) of the stored vector with the mean of all stored chunk"
                + " vectors; a diagnostic of vector geometry, not a production score");
        line(out, "bucket contribution = q[i]*c[i]/(|q||c|) exactly as the production cosine sums it; pos/neg ="
                + " sums of positive/negative contributions; shared = buckets non-zero in both vectors");
        line(out, "cover = distinct original query tokens also among the chunk's original tokens / distinct original"
                + " query tokens");
        line(out, "bucket tokens: query tokens hashing to the bucket (+ = expansion), m = their occurrences in the"
                + " chunk's weighted tokens, o = other distinct chunk tokens colliding in that bucket");
        line(out, "rank (decomposition) = rank among all atoms in the recomputed ranking; ranks 1-"
                + BookRecallEvaluator.MAX_K + " equal production (parity), deeper ranks come from the same"
                + " recomputation");
        line(out, "");
    }

    private static void parity(StringBuilder out, Result result) {
        BookHubProbe.Parity p = result.parity();
        line(out, "== Parity (recomputation vs production) ==");
        line(out, "tolerance=" + fmt(BookHubProbe.SCORE_TOLERANCE, 1) + " (bit-identical: verbatim production"
                + " cosine on the stored vectors)");
        line(out, "queries=" + p.queries() + " comparedHits=" + p.comparedHits() + " maxAbsDifference="
                + fmt(p.maxAbsDifference(), 1) + " rankedAtomsPerQuery=" + p.rankedAtoms() + " storedVectors="
                + p.storedVectors() + " result=PASS (atoms, order incl. atom-id ties, scores)");
        long reencoded = result.metrics().stream().filter(ChunkMetrics::reencodeMatches).count();
        line(out, String.format(Locale.ROOT, "storedNorm min=%.9f max=%.9f (every stored vector is L2-normalized,"
                        + " so norm cannot rank chunks)",
                result.metrics().stream().mapToDouble(ChunkMetrics::norm).min().orElse(0.0),
                result.metrics().stream().mapToDouble(ChunkMetrics::norm).max().orElse(0.0)));
        line(out, "reencode=" + reencoded + "/" + result.metrics().size() + " stored vectors equal the production"
                + " encoding of their chunk text");
        line(out, "");
    }

    private static void summary(StringBuilder out, Distribution d) {
        line(out, "== Concentration summary ==");
        for (String partition : BookHubConcentration.PARTITIONS) {
            int queries = d.queries().get(partition);
            StringBuilder s = new StringBuilder("-- " + partition + ": queries " + queries + ", zero-hit "
                    + d.zeroHit().get(partition));
            for (int k : BookHubConcentration.CUTS) {
                s.append("; top-").append(k).append(" occurrences ").append(d.occurrences().get(partition).get(k))
                        .append(" over ").append(d.distinct().get(partition).get(k)).append(" distinct atoms");
            }
            line(out, s.toString());
            List<Chunk> ordered = d.chunks().stream().sorted(BookHubConcentration.order(partition)).toList();
            int count = ordered.isEmpty() ? 0 : ordered.getFirst().count(partition, 1);
            long tied = ordered.stream().filter(c -> c.count(partition, 1) == count).count();
            if (count > 0 && tied == 1) {
                line(out, "   most frequent top-1: " + ordered.getFirst().key() + " " + count + "/" + queries + " ("
                        + fmt(queries == 0 ? 0.0 : (double) count / queries, 4) + ")");
            } else if (count > 0) {
                line(out, "   no single most frequent top-1: " + tied + " atoms tie at " + count + "/" + queries);
            }
        }
        line(out, "");
    }

    private static void distribution(StringBuilder out, Distribution d) {
        line(out, "== Frequency distribution (every atom in any top-" + BookRecallEvaluator.MAX_K
                + "; order: ALL top-1, top-3, top-10 desc, key asc) ==");
        line(out, "columns: DEV t1/t3/t10 | HOLDOUT t1/t3/t10 | ALL t1/t3/t10 | NO_ANSWER top-1 | key [aliases]");
        for (Chunk c : d.chunks()) {
            StringBuilder s = new StringBuilder();
            for (String partition : BookHubConcentration.PARTITIONS) {
                s.append(String.format(Locale.ROOT, "%2d/%2d/%2d | ", c.count(partition, 1), c.count(partition, 3),
                        c.count(partition, 10)));
            }
            s.append(String.format(Locale.ROOT, "%d | ", c.top1NoAnswer())).append(c.key());
            if (!c.aliases().isEmpty()) {
                s.append(" aliases=").append(c.aliases());
            }
            line(out, s.toString());
        }
        line(out, "");
    }

    private static void references(StringBuilder out, Distribution d) {
        line(out, "== Top-1 references (query split category rank score label) ==");
        for (Chunk c : d.chunks()) {
            if (c.count("ALL", 1) == 0) {
                continue;
            }
            line(out, c.key() + " top-1 " + c.count("ALL", 1) + "/" + d.queries().get("ALL") + ":");
            c.occurrences().stream().filter(o -> o.rank() == 1).forEach(o -> line(out, "  " + occurrence(o)));
        }
        line(out, "");
    }

    private static String occurrence(Occurrence o) {
        return String.format(Locale.ROOT, "%s %-7s %s #%d %.6f %s%s", o.queryId(), o.split(), o.category(),
                o.rank(), o.score(), o.label(), o.answerable() ? "" : " NO_ANSWER");
    }

    private static void controls(StringBuilder out, Result result) {
        line(out, "== Controls (predeclared rules, DEV only) ==");
        for (Control c : result.controls()) {
            line(out, c.role() + " = " + (c.key() == null ? "absent" : c.key()) + "  [rule: " + c.rule() + "]");
            Chunk chunk = c.key() == null ? null : result.distribution().chunk(c.key());
            if (chunk != null) {
                chunk.occurrences().forEach(o -> line(out, "  in top-" + BookRecallEvaluator.MAX_K + ": "
                        + occurrence(o)));
            }
        }
        line(out, "");
    }

    private record Metric(String name, ToDoubleFunction<ChunkMetrics> value, int decimals) {
    }

    private static final List<Metric> METRICS = List.of(
            new Metric("utf8Bytes", m -> m.utf8Bytes(), 0),
            new Metric("codePoints", m -> m.codePoints(), 0),
            new Metric("originalTokens", m -> m.originalTokens(), 0),
            new Metric("distinctOriginalTokens", m -> m.distinctOriginalTokens(), 0),
            new Metric("expansionTokens", m -> m.expansionTokens(), 0),
            new Metric("topTok", m -> m.topTokenCount(), 0),
            new Metric("nz", m -> m.nonZeroBuckets(), 0),
            new Metric("maxAbs", ChunkMetrics::maxAbsComponent, 6),
            new Metric("centroidCos", ChunkMetrics::centroidCosine, 6));

    private static void controlMetrics(StringBuilder out, Result result) {
        int n = result.metrics().size();
        line(out, "== Control chunk metrics (value, rank among " + n + " atoms by value desc; equal values share"
                + " a rank) ==");
        for (Control c : result.controls()) {
            if (c.key() == null) {
                line(out, c.role() + ": absent");
                continue;
            }
            ChunkMetrics m = metric(result, c.key());
            StringBuilder s = new StringBuilder(c.role() + " " + m.key() + " ordinal " + m.ordinal() + ":");
            for (Metric metric : METRICS) {
                s.append(' ').append(metric.name()).append('=').append(fmt(metric.value().applyAsDouble(m),
                        metric.decimals())).append(" (#").append(rank(result.metrics(), m, metric)).append(')');
            }
            line(out, s.toString());
        }
        line(out, "");
    }

    private static void corpusMetrics(StringBuilder out, Result result) {
        line(out, "== Corpus chunk metrics (every atom, chunk order) ==");
        line(out, "columns: ordinal key utf8 cp orig distinct exp topTok nz maxAbs centroidCos | DEV t1 t10 |"
                + " HOLDOUT t1 t10 (observed only)");
        for (ChunkMetrics m : result.metrics()) {
            Chunk c = result.distribution().chunk(m.key());
            line(out, String.format(Locale.ROOT, "%3d %s %5d %5d %4d %4d %3d %3d %3d %.6f %.6f | %d %2d | %d %d%s",
                    m.ordinal(), m.key(), m.utf8Bytes(), m.codePoints(), m.originalTokens(),
                    m.distinctOriginalTokens(), m.expansionTokens(), m.topTokenCount(), m.nonZeroBuckets(),
                    m.maxAbsComponent(), m.centroidCosine(), c == null ? 0 : c.count("DEV", 1),
                    c == null ? 0 : c.count("DEV", 10), c == null ? 0 : c.count("HOLDOUT", 1),
                    c == null ? 0 : c.count("HOLDOUT", 10), m.aliases() == 0 ? "" : " aliases=" + m.aliases()));
        }
        line(out, "");
    }

    private static void decomposition(StringBuilder out, Result result) {
        line(out, "== Score decomposition (DEV queries x controls; top " + BookHubProbe.TOP_BUCKETS
                + " buckets by contribution) ==");
        for (Control c : result.controls()) {
            if (c.key() == null || c.dev().isEmpty()) {
                continue;
            }
            List<PairScore> dev = c.dev();
            line(out, String.format(Locale.ROOT, "-- %s %s: DEV queries %d, rank<=1 %d, rank<=3 %d, rank<=10 %d,"
                            + " mean score %.6f, median rank %s, mean cover %.4f, mean shared %.2f, mean pos %.6f,"
                            + " mean neg %.6f, max residual %s",
                    c.role(), c.key(), dev.size(), within(dev, 1), within(dev, 3), within(dev, 10),
                    mean(dev, PairScore::score), median(dev), mean(dev, p -> p.queryTokens() == 0 ? 0.0
                            : (double) p.coveredTokens() / p.queryTokens()), mean(dev, p -> p.sharedBuckets()),
                    mean(dev, PairScore::positive), mean(dev, PairScore::negative),
                    String.format(Locale.ROOT, "%.3e",
                            dev.stream().mapToDouble(PairScore::residual).max().orElse(0.0))));
            for (PairScore p : dev) {
                line(out, String.format(Locale.ROOT, "  %s%s rank %d score %.6f pos %.6f neg %.6f shared %d"
                                + " cover %d/%d top: %s",
                        p.queryId(), p.answerable() ? "" : " NO_ANSWER", p.rank(), p.score(), p.positive(),
                        p.negative(), p.sharedBuckets(), p.coveredTokens(), p.queryTokens(), buckets(p.top())));
            }
        }
        line(out, "");
    }

    private static String buckets(List<Contribution> top) {
        if (top.isEmpty()) {
            return "-";
        }
        return top.stream().map(b -> String.format(Locale.ROOT, "b%d %+.6f %s m=%d o=%d", b.bucket(), b.value(),
                b.queryTokens().isEmpty() ? "[]" : b.queryTokens().toString().replace(" ", ""), b.chunkMatches(),
                b.otherChunkTokens())).collect(Collectors.joining("; "));
    }

    private static void notMeasured(StringBuilder out) {
        line(out, "== Not measured ==");
        line(out, "- per-token share of a bucket contribution: buckets mix colliding tokens (o > 0), so the cosine is"
                + " attributed to buckets only; no per-token percentage is derived");
        line(out, "- corpus-level token weighting (IDF): the production encoder uses raw weighted counts, so there is"
                + " no IDF to inspect; only per-chunk counts are reported");
        line(out, "- relevance of unjudged (U) hub hits: not adjudicated; a U label is not evidence of irrelevance");
        line(out, "- causation: no intervention was run; the numbers above are observations of one frozen"
                + " configuration");
        line(out, "- HOLDOUT score decompositions: not computed by design (HOLDOUT is reserved for a later final"
                + " check)");
    }

    private static ChunkMetrics metric(Result result, String key) {
        return result.metrics().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    private static int rank(List<ChunkMetrics> all, ChunkMetrics m, Metric metric) {
        double value = metric.value().applyAsDouble(m);
        return 1 + (int) all.stream().filter(x -> metric.value().applyAsDouble(x) > value).count();
    }

    private static int within(List<PairScore> dev, int k) {
        return (int) dev.stream().filter(p -> p.rank() > 0 && p.rank() <= k).count();
    }

    private static double mean(List<PairScore> dev, ToDoubleFunction<PairScore> f) {
        return dev.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    private static String median(List<PairScore> dev) {
        int[] ranks = dev.stream().mapToInt(PairScore::rank).sorted().toArray();
        int n = ranks.length;
        if (n == 0) {
            return "-";
        }
        return n % 2 == 1 ? String.valueOf(ranks[n / 2]) : fmt((ranks[n / 2 - 1] + ranks[n / 2]) / 2.0, 1);
    }

    private static String fmt(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
