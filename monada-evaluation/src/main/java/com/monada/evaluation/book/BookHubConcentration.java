package com.monada.evaluation.book;

import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookRecallEvaluator.CaseResult;
import com.monada.evaluation.book.BookRecallEvaluator.RankedHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How often each retrieved chunk appears at the top of the frozen recall results (Issue #176, evaluation only). A
 * pure count over {@link CaseResult}s: nothing is re-ranked. Counts are per stored atom, under its relevance key (the
 * first chunk key with that text), so a repeated passage counts once per query; its other keys are listed as aliases.
 */
final class BookHubConcentration {
    /** The cut-offs counted; each is at most {@link BookRecallEvaluator#MAX_K}. */
    static final List<Integer> CUTS = List.of(1, 3, 10);
    /** Partition names in report order. */
    static final List<String> PARTITIONS = List.of("DEV", "HOLDOUT", "ALL");

    private BookHubConcentration() {
    }

    /** One query that retrieved the chunk within the top-{@code 10}. */
    record Occurrence(String queryId, Split split, String category, boolean answerable, int rank, double score,
                      String label) {
    }

    /**
     * One retrieved atom.
     *
     * @param counts partition name to cut-off to the number of queries that ranked it within that cut-off
     * @param top1NoAnswer NO_ANSWER queries (any split) that ranked it first
     */
    record Chunk(String key, String atomId, List<String> aliases, Map<String, Map<Integer, Integer>> counts,
                 int top1NoAnswer, List<Occurrence> occurrences) {
        Chunk {
            aliases = List.copyOf(aliases);
            occurrences = List.copyOf(occurrences);
        }

        int count(String partition, int cut) {
            return counts.get(partition).get(cut);
        }
    }

    /**
     * The whole distribution.
     *
     * @param queries      partition name to the number of queries (the denominator of every count)
     * @param zeroHit      partition name to the number of queries that returned no hit at all
     * @param occurrences  partition name to cut-off to query-hit occurrences (each query contributes up to K)
     * @param distinct     partition name to cut-off to distinct atoms seen
     * @param chunks       every atom seen in any top-10, sorted by ALL top-1, top-3, top-10 descending, then key
     */
    record Distribution(Map<String, Integer> queries, Map<String, Integer> zeroHit,
                        Map<String, Map<Integer, Integer>> occurrences, Map<String, Map<Integer, Integer>> distinct,
                        List<Chunk> chunks) {
        Distribution {
            chunks = List.copyOf(chunks);
        }

        Chunk chunk(String key) {
            return chunks.stream().filter(c -> c.key().equals(key) || c.aliases().contains(key)).findFirst()
                    .orElse(null);
        }
    }

    static Distribution of(List<CaseResult> cases) {
        Map<String, Integer> queries = new LinkedHashMap<>();
        Map<String, Integer> zeroHit = new LinkedHashMap<>();
        Map<String, Map<Integer, Integer>> occurrences = new LinkedHashMap<>();
        Map<String, Map<Integer, Set<String>>> distinctAtoms = new LinkedHashMap<>();
        for (String partition : PARTITIONS) {
            queries.put(partition, 0);
            zeroHit.put(partition, 0);
            occurrences.put(partition, zeros());
            Map<Integer, Set<String>> sets = new TreeMap<>();
            CUTS.forEach(k -> sets.put(k, new LinkedHashSet<>()));
            distinctAtoms.put(partition, sets);
        }
        Map<String, Builder> builders = new LinkedHashMap<>();
        for (CaseResult c : cases) {
            List<String> partitions = List.of(c.query().split().name(), "ALL");
            for (String partition : partitions) {
                queries.merge(partition, 1, Integer::sum);
                if (c.hits().isEmpty()) {
                    zeroHit.merge(partition, 1, Integer::sum);
                }
            }
            for (RankedHit hit : c.hits()) {
                Builder b = builders.computeIfAbsent(hit.atomId(), id -> new Builder(hit));
                b.occurrences.add(new Occurrence(c.query().id(), c.query().split(), c.query().category().name(),
                        c.answerable(), hit.rank(), hit.score(), hit.label()));
                if (hit.rank() == 1 && !c.answerable()) {
                    b.top1NoAnswer++;
                }
                for (int k : CUTS) {
                    if (hit.rank() <= k) {
                        for (String partition : partitions) {
                            b.counts.get(partition).merge(k, 1, Integer::sum);
                            occurrences.get(partition).merge(k, 1, Integer::sum);
                            distinctAtoms.get(partition).get(k).add(hit.atomId());
                        }
                    }
                }
            }
        }
        Map<String, Map<Integer, Integer>> distinct = new LinkedHashMap<>();
        distinctAtoms.forEach((partition, sets) -> {
            Map<Integer, Integer> sizes = new TreeMap<>();
            sets.forEach((k, set) -> sizes.put(k, set.size()));
            distinct.put(partition, sizes);
        });
        List<Chunk> chunks = new ArrayList<>();
        builders.values().forEach(b -> chunks.add(b.build()));
        chunks.sort(order("ALL"));
        return new Distribution(queries, zeroHit, occurrences, distinct, chunks);
    }

    /** Top-1, top-3, top-10 counts of {@code partition} descending, then relevance key ascending. */
    static Comparator<Chunk> order(String partition) {
        Comparator<Chunk> order = Comparator.comparingInt((Chunk c) -> -c.count(partition, 1))
                .thenComparingInt(c -> -c.count(partition, 3))
                .thenComparingInt(c -> -c.count(partition, 10));
        return order.thenComparing(Chunk::key);
    }

    private static Map<Integer, Integer> zeros() {
        Map<Integer, Integer> counts = new TreeMap<>();
        CUTS.forEach(k -> counts.put(k, 0));
        return counts;
    }

    private static final class Builder {
        final String key;
        final String atomId;
        final List<String> aliases;
        final Map<String, Map<Integer, Integer>> counts = new LinkedHashMap<>();
        final List<Occurrence> occurrences = new ArrayList<>();
        int top1NoAnswer;

        Builder(RankedHit hit) {
            key = hit.key();
            atomId = hit.atomId();
            aliases = hit.keys().subList(1, hit.keys().size());
            for (String partition : PARTITIONS) {
                counts.put(partition, zeros());
            }
        }

        Chunk build() {
            Map<String, Map<Integer, Integer>> frozen = new LinkedHashMap<>();
            counts.forEach((p, m) -> frozen.put(p, Map.copyOf(m)));
            return new Chunk(key, atomId, aliases, frozen, top1NoAnswer, occurrences);
        }
    }
}
