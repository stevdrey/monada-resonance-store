package com.monada.evaluation.book;

import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.Split;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The predeclared, deterministic selection of formerly unjudged DEV hits for the {@code book-queries-v2} review
 * (issue #177). The only input is the per-query trace of the frozen {@code book-recall-v1-report.txt}, pinned by
 * SHA-256; HOLDOUT hits are parsed for coverage counts but never enter the pool. The rule is fixed before any new
 * label exists and never looks at a proposed label: it uses rank, the per-chunk count of DEV unjudged occurrences
 * and a seeded SHA-256 order only.
 *
 * <p>Strata, applied in order, no pair twice ({@link Stratum}): every rank-1 pair; per DEV query its best
 * remaining rank-2/3 pair; one remaining pair for each of the {@value #HUB_CHUNKS} chunks with the most DEV
 * unjudged occurrences; then a seeded fill up to {@value #TARGET} pairs (all eligible pairs when fewer).
 */
final class BookUnjudgedSelection {
    static final String RULE = "dev-unjudged-top1-top3-hub-seeded-v1";
    static final String SEED = "book-queries-v2|unjudged-review-v1|";
    static final String BASELINE_REPORT = "docs/experiments/book-corpus-v1/book-recall-v1-report.txt";
    static final String BASELINE_SHA256 = "86501f764e985835487a90b05ce5dbbdc2821455c94a97aaf5d443bba1a2ffd2";
    /**
     * SHA-256 of the receipt this rule and seed produce from the pinned baseline. It is pinned here, not only in the
     * editable manifest, so a different receipt cannot be accepted by refreshing digests; the selection test re-derives
     * the receipt and checks this value.
     */
    static final String RECEIPT_SHA256 = "c095247df3d57b568afc15fa12eee9b5efa9bc9ac74f9f7dedd6ecfa9463418d";
    static final int TARGET = 60;
    static final int HUB_CHUNKS = 4;
    static final String HEADER = "pairId|queryId|split|category|chunkKey|atomId|stratum|rank|score|chunkDevUnjudged";

    private static final String TRACE_SECTION = "== Per-query trace (all hits, raw atom ids) ==";
    private static final Pattern CASE_LINE = Pattern.compile("([A-Z]{3}-[0-9]{2}) (DEV|HOLDOUT) ([A-Z_]+) failure=.*");
    private static final Pattern HIT_LINE = Pattern.compile(
            "  #([0-9]+) ([0-9.Ee+-]+|-?Infinity|NaN) (E2|E1|C0|U) +(\\S+) atom=(\\S+)(?: sameText=\\[(.*)])?");
    private static final Pattern PAIR_ID = Pattern.compile("UJ-[0-9a-f]{8}");

    enum Stratum { TOP1, TOP3, HUB, SAMPLE }

    /** One ranked hit of the frozen trace; {@code chunkKey} is the canonical (first) key of the hit's atom. */
    record Hit(String queryId, Split split, Category category, int rank, String score, String label,
               String chunkKey, String atomId, List<String> keys) {
        Hit {
            keys = List.copyOf(keys);
        }

        boolean unjudged() {
            return label.equals("U");
        }
    }

    /** One selected (query, canonical chunk) pair; rank, score and hub count are hidden from the reviewer. */
    record Pair(String pairId, String queryId, Split split, Category category, String chunkKey, String atomId,
                Stratum stratum, int rank, String score, int chunkDevUnjudged) {
    }

    private BookUnjudgedSelection() {
    }

    /** Neutral, content-derived pair id: it reveals neither stratum nor rank. */
    static String pairId(String queryId, String chunkKey) {
        return "UJ-" + BookQueryDataset.sha256(utf8(SEED + "pair|" + queryId + "|" + chunkKey)).substring(0, 8);
    }

    /** Seeded order used for the HUB pick and the SAMPLE fill. */
    static String sampleKey(String queryId, String chunkKey) {
        return BookQueryDataset.sha256(utf8(SEED + queryId + "|" + chunkKey));
    }

    /**
     * Parses every hit of the trace and cross-checks it against {@code base}: split, category and the v1 label of
     * each hit must agree, so a trace of another dataset generation is rejected.
     */
    static List<Hit> parseTrace(byte[] report, BookQueryDataset base) {
        return parseTrace(report, base, BASELINE_SHA256);
    }

    /** As {@link #parseTrace(byte[], BookQueryDataset)} against another pin; tests use it for edited traces. */
    static List<Hit> parseTrace(byte[] report, BookQueryDataset base, String pinnedSha256) {
        check(pinnedSha256.equals(BookQueryDataset.sha256(report)),
                "baseline report SHA-256 is " + BookQueryDataset.sha256(report) + ", pinned " + pinnedSha256);
        String text = new String(report, StandardCharsets.UTF_8);
        int at = text.indexOf("\n" + TRACE_SECTION + "\n");
        check(at >= 0, "baseline report has no per-query trace section");
        Map<String, Case> cases = base.cases().stream()
                .collect(Collectors.toMap(Case::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Hit> hits = new ArrayList<>();
        Set<String> seenCases = new HashSet<>();
        Set<String> seenAtoms = new HashSet<>();
        Case current = null;
        for (String line : text.substring(at + TRACE_SECTION.length() + 2).split("\n")) {
            Matcher head = CASE_LINE.matcher(line);
            if (head.matches()) {
                current = cases.get(head.group(1));
                check(current != null, "trace names an unknown query " + head.group(1));
                check(current.split().name().equals(head.group(2)) && current.category().name().equals(head.group(3)),
                        current.id() + ": trace split/category differs from book-queries-v1");
                check(seenCases.add(current.id()), current.id() + ": traced twice");
                continue;
            }
            Matcher hit = HIT_LINE.matcher(line);
            if (!hit.matches()) {
                continue;
            }
            check(current != null, "trace hit before any query: " + line);
            String key = hit.group(4);
            check(base.chunkMap().containsKey(key), current.id() + ": trace hit on unknown chunk " + key);
            List<String> keys = hit.group(6) == null ? List.of(key) : List.of(hit.group(6).split(", "));
            check(keys.getFirst().equals(key), current.id() + ": hit key is not the first of its sameText keys");
            check(seenAtoms.add(current.id() + "|" + hit.group(5)), current.id() + ": atom ranked twice");
            Hit h = new Hit(current.id(), current.split(), current.category(), Integer.parseInt(hit.group(1)),
                    hit.group(2), hit.group(3), key, hit.group(5), keys);
            check(h.label().equals(expectedLabel(current, keys)),
                    current.id() + ": trace label " + h.label() + " on " + key + " differs from book-queries-v1");
            hits.add(h);
        }
        check(seenCases.equals(cases.keySet()), "trace does not cover every book-queries-v1 query");
        return List.copyOf(hits);
    }

    /** The predeclared selection over {@code hits} (see the class comment). */
    static List<Pair> select(List<Hit> hits) {
        return select(hits, BookUnjudgedSelection::pairId);
    }

    /** {@link #select(List)} with another pair-id function; tests use it to force an id collision. */
    static List<Pair> select(List<Hit> hits, BiFunction<String, String, String> ids) {
        List<Hit> eligible = hits.stream().filter(h -> h.split() == Split.DEV && h.unjudged())
                .sorted(Comparator.comparing(Hit::queryId).thenComparing(Hit::chunkKey)).toList();
        Map<String, Integer> perChunk = new TreeMap<>();
        eligible.forEach(h -> perChunk.merge(h.chunkKey(), 1, Integer::sum));

        // Keyed by (query, chunk), not by pair id: two distinct pairs whose ids collide must fail, not be skipped.
        Map<String, Pair> chosen = new LinkedHashMap<>();
        // TOP1: every rank-1 unjudged DEV pair.
        eligible.stream().filter(h -> h.rank() == 1).forEach(h -> take(chosen, h, Stratum.TOP1, perChunk, ids));
        // TOP3: per DEV query, its best remaining pair at rank 2 or 3.
        eligible.stream().map(Hit::queryId).distinct().forEach(q -> eligible.stream()
                .filter(h -> h.queryId().equals(q) && h.rank() >= 2 && h.rank() <= 3 && open(chosen, h))
                .min(Comparator.comparingInt(Hit::rank))
                .ifPresent(h -> take(chosen, h, Stratum.TOP3, perChunk, ids)));
        // HUB: one remaining pair for each of the most frequent unjudged DEV chunks; none left means none taken.
        perChunk.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(HUB_CHUNKS)
                .forEach(e -> eligible.stream().filter(h -> h.chunkKey().equals(e.getKey()) && open(chosen, h))
                        .min(Comparator.comparing(h -> sampleKey(h.queryId(), h.chunkKey())))
                        .ifPresent(h -> take(chosen, h, Stratum.HUB, perChunk, ids)));
        // SAMPLE: seeded fill of the rest.
        eligible.stream().filter(h -> open(chosen, h))
                .sorted(Comparator.comparing(h -> sampleKey(h.queryId(), h.chunkKey())))
                .limit(Math.max(0, TARGET - chosen.size()))
                .forEach(h -> take(chosen, h, Stratum.SAMPLE, perChunk, ids));
        return List.copyOf(chosen.values());
    }

    /** The committed receipt bytes, in selection order. */
    static String render(List<Pair> pairs) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Pair p : pairs) {
            out.append(String.join("|", p.pairId(), p.queryId(), p.split().name(), p.category().name(), p.chunkKey(),
                    p.atomId(), p.stratum().name(), String.valueOf(p.rank()), p.score(),
                    String.valueOf(p.chunkDevUnjudged()))).append('\n');
        }
        return out.toString();
    }

    /** Parses a receipt; structural checks only, the dataset cross-checks live in {@link BookQueryDatasetV2}. */
    static List<Pair> parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // A lenient decode would turn invalid bytes into U+FFFD, so the parsed notes would differ from the pinned bytes.
        check(Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes), "selection.psv is not valid UTF-8");
        check(text.endsWith("\n") && !text.contains("\r"), "selection.psv must use LF line ends and end with one");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        check(HEADER.equals(lines[0]), "selection.psv header must be " + HEADER);
        List<Pair> pairs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            check(f.length == 10, "selection.psv row needs 10 columns: " + lines[i]);
            check(PAIR_ID.matcher(f[0]).matches() && f[0].equals(pairId(f[1], f[4])),
                    "selection.psv pair id " + f[0] + " does not match " + f[1] + " " + f[4]);
            check(ids.add(f[0]), "selection.psv selects pair " + f[0] + " twice");
            try {
                pairs.add(new Pair(f[0], f[1], Split.valueOf(f[2]), Category.valueOf(f[3]), f[4], f[5],
                        Stratum.valueOf(f[6]), Integer.parseInt(f[7]), f[8], Integer.parseInt(f[9])));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("selection.psv row is malformed: " + lines[i]);
            }
        }
        return List.copyOf(pairs);
    }

    private static String expectedLabel(Case c, List<String> keys) {
        return c.judgments().stream().filter(j -> keys.contains(j.chunkKey())).findFirst()
                .map(j -> j.role() == BookQueryDataset.Role.EVIDENCE ? "E" + j.grade() : "C0").orElse("U");
    }

    private static boolean open(Map<String, Pair> chosen, Hit h) {
        return !chosen.containsKey(h.queryId() + "|" + h.chunkKey());
    }

    private static void take(Map<String, Pair> chosen, Hit h, Stratum stratum, Map<String, Integer> perChunk,
                             BiFunction<String, String, String> ids) {
        String id = ids.apply(h.queryId(), h.chunkKey());
        check(chosen.values().stream().noneMatch(p -> p.pairId().equals(id)),
                "pair id collision: " + id + " for " + h.queryId() + " " + h.chunkKey());
        check(chosen.put(h.queryId() + "|" + h.chunkKey(), new Pair(id, h.queryId(), h.split(), h.category(),
                h.chunkKey(), h.atomId(), stratum, h.rank(), h.score(), perChunk.get(h.chunkKey()))) == null,
                "pair selected twice: " + id);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
