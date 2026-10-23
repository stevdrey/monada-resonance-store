package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.core.ResonanceResult;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The fresh-process side of the book latency benchmark (Issue #168): started by {@link BookLatencyBenchmark} in a new
 * JVM, it opens one Store, runs one pass of the queries and prints its own timings, so the parent's JVM state (JIT,
 * loaded classes, heap) cannot reach them. JVM startup happens before {@code main} and is not in these timings; the
 * parent measures the whole process separately. The OS page cache is shared with the parent and is not dropped.
 *
 * <pre>
 * java -cp &lt;classpath&gt; com.monada.evaluation.book.BookLatencyChild --store &lt;dir&gt; --queries &lt;file&gt;
 * </pre>
 *
 * <p>The query file holds one query per line (UTF-8). Output is {@code key=value} lines: {@code openNanos},
 * {@code queryNanos} (comma-separated, query order), {@code resultsDigest} ({@link #digest}) and the child's own
 * {@code jvm.maxHeapMiB}, {@code jvm.gc} and {@code jvm.args} (allowlisted options only, see
 * {@link BookLatencyHost#describeArgs}). Exit 0 on success,
 * 2 on a usage or I/O error.
 */
public final class BookLatencyChild {
    /** Top-K of every timed query, as in the book recall baseline. */
    static final int K = BookRecallEvaluator.MAX_K;
    private static final String USAGE = "usage: --store <store dir> --queries <one query per line>";

    private BookLatencyChild() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 0, Set.of("--store", "--queries"),
                null, err, USAGE);
        if (options == null || !options.containsKey("--store") || !options.containsKey("--queries")) {
            if (options != null) {
                err.println(USAGE);
            }
            return 2;
        }
        try {
            List<String> queries = Files.readAllLines(Path.of(options.get("--queries")), StandardCharsets.UTF_8);
            long start = System.nanoTime();
            MonadaMemory memory = MonadaMemory.open(Path.of(options.get("--store")));
            long open = System.nanoTime() - start;
            List<Long> nanos = new ArrayList<>(queries.size());
            List<List<Hit>> results = new ArrayList<>(queries.size());
            for (String query : queries) {
                long before = System.nanoTime();
                var recall = query(memory, query);
                nanos.add(System.nanoTime() - before);
                results.add(hits(recall));
            }
            out.println("openNanos=" + open);
            out.println("queryNanos=" + nanos.stream().map(String::valueOf).collect(Collectors.joining(",")));
            out.println("resultsDigest=" + digest(results));
            // The child's own configuration, so the parent never reports its settings for these timings.
            out.println("jvm.maxHeapMiB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
            out.println("jvm.gc=" + ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", ")));
            out.println("jvm.args=" + BookLatencyHost.describeArgs(ManagementFactory.getRuntimeMXBean()
                    .getInputArguments()));
            // PrintStream swallows write failures (a full scratch file system): never report success over them.
            if (out.checkError()) {
                err.println("child failure: could not write its report to standard output");
                return 2;
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("child failure: " + e);
            return 2;
        }
    }

    /** The timed call: the public API with default options, top-{@value #K}, no threshold. */
    static List<ResonanceResult> query(MonadaMemory memory, String query) {
        return memory.resonate(query).topK(K).threshold(Double.NEGATIVE_INFINITY).execute().results();
    }

    /** Hits of a query, mapped after the timed call so the mapping is never timed. */
    static List<Hit> hits(List<ResonanceResult> results) {
        return results.stream().map(r -> new Hit(r.atom().id(), r.score())).toList();
    }

    /**
     * SHA-256 over every query's ranked hits, one line {@code queryIndex TAB rank TAB atomId TAB scoreBits} per hit
     * plus an end line per query, so equal digests mean identical ids, order and scores.
     */
    static String digest(List<List<Hit>> results) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (int q = 0; q < results.size(); q++) {
            List<Hit> hits = results.get(q);
            for (int rank = 0; rank < hits.size(); rank++) {
                Hit hit = hits.get(rank);
                sha.update((q + "\t" + (rank + 1) + "\t" + hit.atomId() + "\t"
                        + Long.toHexString(Double.doubleToLongBits(hit.score())) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            sha.update((q + "\tend\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
