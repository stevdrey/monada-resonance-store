package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookHubConcentration.Chunk;
import com.monada.evaluation.book.BookHubConcentration.Distribution;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import com.monada.evaluation.book.BookRecallEvaluator.Result;
import com.monada.evaluation.book.BookRecallEvaluator.RetrieverFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hub-concentration counts over scripted recall results of the seeded fixture: per-partition denominators, top-1/3/10
 * counts, NO_ANSWER top-1, zero-hit queries, repeated-text aliases counted once, tied scores and the deterministic
 * order, plus the predeclared DEV-only control rules.
 */
class BookHubConcentrationTest {
    @TempDir
    Path dir;

    private Path work;
    private BookQueryDataset dataset;

    @BeforeEach
    void seed() throws Exception {
        work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        dataset = BookRecallFixture.dataset(work);
    }

    private static String key(String suffix) {
        return BookRecallFixture.KEY + suffix;
    }

    /** Scripted retrieval: query text to chunk suffixes, all with the same score (a tie ordered by atom id). */
    private RetrieverFactory tied(Map<String, List<String>> ranking) throws Exception {
        Map<String, List<Hit>> hits = new HashMap<>();
        for (var entry : ranking.entrySet()) {
            List<Hit> list = new ArrayList<>();
            for (String suffix : entry.getValue()) {
                list.add(new Hit(BookRecallFixture.atomId(work, suffix), 0.5));
            }
            list.sort((a, b) -> a.atomId().compareTo(b.atomId()));
            hits.put(entry.getKey(), list);
        }
        return copy -> (query, k) -> hits.getOrDefault(query, List.of());
    }

    /** Scripted retrieval: query text to chunk suffixes, scored 0.9, 0.8, ... in that order. */
    private RetrieverFactory ranked(Map<String, List<String>> ranking) throws Exception {
        Map<String, List<Hit>> hits = new HashMap<>();
        for (var entry : ranking.entrySet()) {
            List<Hit> list = new ArrayList<>();
            for (int i = 0; i < entry.getValue().size(); i++) {
                list.add(new Hit(BookRecallFixture.atomId(work, entry.getValue().get(i)), 0.9 - 0.1 * i));
            }
            hits.put(entry.getKey(), list);
        }
        return copy -> (query, k) -> hits.getOrDefault(query, List.of());
    }

    /** s001/c0001 is the hub: top-1 for three queries, one of them NO_ANSWER; LIT-03 returns nothing. */
    private Map<String, List<String>> hubScript() {
        return Map.of(
                "How many gulls circled the breakwater?", List.of("s001/c0001", "s001/c0000", "s002/c0003"),
                "The tide turns twice a day.", List.of("s002/c0003", "s001/c0001"),
                "Where does a lantern appear?", List.of("s001/c0001", "s002/c0002"),
                "Which song names the rope and the oar?", List.of("s002/c0002", "s001/c0001", "s001/c0000",
                        "s000/c0000"),
                "What colour is the keeper's coat?", List.of("s001/c0001", "s002/c0001"));
    }

    @Test
    void countsTopKPerPartitionWithDenominators() throws Exception {
        Result result = BookRecallEvaluator.evaluate(work, dataset, ranked(hubScript()), dir);
        Distribution d = BookHubConcentration.of(result.cases());

        int dev = (int) result.cases().stream().filter(c -> c.query().split() == BookQueryDataset.Split.DEV).count();
        assertEquals(6, d.queries().get("ALL"));
        assertEquals(dev, d.queries().get("DEV"));
        assertEquals(6 - dev, d.queries().get("HOLDOUT"));
        assertEquals(1, d.zeroHit().get("ALL"), "LIT-03 returned no hit and still counts in the denominator");

        Chunk hub = d.chunks().getFirst();
        assertEquals(key("s001/c0001"), hub.key(), "the hub sorts first");
        assertEquals(3, hub.count("ALL", 1));
        assertEquals(5, hub.count("ALL", 3));
        assertEquals(5, hub.count("ALL", 10));
        assertEquals(1, hub.top1NoAnswer());
        assertEquals(hub.count("ALL", 1), hub.count("DEV", 1) + hub.count("HOLDOUT", 1));
        assertEquals(List.of("CON-01", "LIT-01", "LIT-02", "MUL-01", "NOA-01"),
                hub.occurrences().stream().map(BookHubConcentration.Occurrence::queryId).toList());

        assertEquals(5, d.occurrences().get("ALL").get(1), "one top-1 per query with hits");
        assertEquals(13, d.occurrences().get("ALL").get(10), "every query-hit pair");
        assertEquals(6, d.distinct().get("ALL").get(10), "distinct atoms, not occurrences");
    }

    @Test
    void repeatedTextIsOneAtomWithAliases() throws Exception {
        Result result = BookRecallEvaluator.evaluate(work, dataset, ranked(hubScript()), dir);
        Chunk repeated = BookHubConcentration.of(result.cases()).chunk(key("s002/c0006"));

        assertEquals(key("s002/c0003"), repeated.key(), "counted under the first key of its text");
        assertEquals(List.of(key("s002/c0006")), repeated.aliases());
        assertEquals(2, repeated.count("ALL", 10), "LIT-01 and LIT-02 retrieve the shared atom once each");
        assertEquals(1, repeated.count("ALL", 1));
    }

    @Test
    void tiesAndOrderAreDeterministic() throws Exception {
        Map<String, List<String>> script = Map.of(
                "How many gulls circled the breakwater?", List.of("s000/c0000", "s001/c0000"),
                "Where does a lantern appear?", List.of("s000/c0000", "s001/c0000"));
        Result result = BookRecallEvaluator.evaluate(work, dataset, tied(script), dir);
        Distribution d = BookHubConcentration.of(result.cases());

        String first = result.cases().stream().filter(c -> !c.hits().isEmpty()).findFirst().orElseThrow()
                .hits().getFirst().key();
        assertEquals(first, d.chunks().getFirst().key(), "tied scores keep production's atom-id order at rank 1");
        assertEquals(2, d.chunks().getFirst().count("ALL", 1));
        assertEquals(0, d.chunks().get(1).count("ALL", 1));
        assertEquals(2, d.chunks().get(1).count("ALL", 3));
        assertEquals(4, d.zeroHit().get("ALL"));
        assertEquals(d, BookHubConcentration.of(result.cases()), "same input, same distribution");
    }

    @Test
    void noHitsGiveAnEmptyDistribution() throws Exception {
        Result result = BookRecallEvaluator.evaluate(work, dataset, copy -> (query, k) -> List.of(), dir);
        Distribution d = BookHubConcentration.of(result.cases());

        assertTrue(d.chunks().isEmpty());
        assertEquals(6, d.zeroHit().get("ALL"));
        assertEquals(0, d.occurrences().get("ALL").get(1));
        List<BookHubProbe.Selected> controls = BookHubProbe.controls(d, result.cases(), Map.of());
        controls.stream().filter(c -> !c.role().equals("JUDGED_EVIDENCE"))
                .forEach(c -> assertNull(c.key(), c.role() + " needs a retrieved chunk"));
        assertEquals(key("s001/c0000"), controls.getLast().key(), "judged evidence needs no hit");
    }

    @Test
    void controlsFollowTheDevOnlyRules() throws Exception {
        Result result = BookRecallEvaluator.evaluate(work, dataset, ranked(hubScript()), dir);
        Distribution d = BookHubConcentration.of(result.cases());
        Map<String, String> relevance = new HashMap<>();
        for (BookChunkFiles.Entry e : BookChunkFiles.load(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR))
                .entries()) {
            relevance.put(e.key(), e.key().endsWith("s002/c0006") ? key("s002/c0003") : e.key());
        }
        List<BookHubProbe.Selected> controls = BookHubProbe.controls(d, result.cases(), relevance);

        assertEquals(List.of("HUB", "RUNNER_UP", "TOP1_EVIDENCE", "JUDGED_EVIDENCE"),
                controls.stream().map(BookHubProbe.Selected::role).toList());
        List<String> keys = controls.stream().map(BookHubProbe.Selected::key).filter(k -> k != null).toList();
        assertEquals(keys.size(), keys.stream().distinct().count(), "no chunk is two controls");
        for (BookHubProbe.Selected c : controls) {
            if (c.key() == null) {
                continue;
            }
            Chunk chunk = d.chunk(c.key());
            if (c.role().equals("HUB") || c.role().equals("RUNNER_UP")) {
                assertTrue(chunk.count("DEV", 10) > 0, "chosen from DEV results only: " + c);
            }
        }
        assertEquals(controls, BookHubProbe.controls(d, result.cases(), relevance), "rules are deterministic");
    }
}
