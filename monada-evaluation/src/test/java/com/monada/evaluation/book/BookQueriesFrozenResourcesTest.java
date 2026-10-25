package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The frozen {@code book-queries-v1} and {@code book-queries-v2} files and every committed book report stay
 * byte-identical (issues #185, #186). The v3 queries, judgments and receipts live only in their own resource
 * directory ({@link BookQueryDatasetV3Test}); the contract directory keeps just the protocol and the plan.
 */
class BookQueriesFrozenResourcesTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
    private static final String RESOURCES = "monada-evaluation/src/main/resources/com/monada/evaluation/book/";
    private static final String DOCS = "docs/experiments/book-corpus-v1/";
    private static final Map<String, String> FROZEN = new TreeMap<>(Map.ofEntries(
            Map.entry(RESOURCES + "book-queries-v1/.gitattributes",
                    "f3d12483dbf81a7cefab18a231f4d41254f3f913a4280fcea94ed01711f6df5f"),
            Map.entry(RESOURCES + "book-queries-v1/chunk-map.jsonl",
                    "d3c62fe220a5e8b7ec584c06f44469ec887f08a71d8ed87a94d65f4ec709f017"),
            Map.entry(RESOURCES + "book-queries-v1/judgments.psv",
                    "5f129b6e0b8cb0565d9ba13cb6a57c67238403e7cde77e45c702068d6b163968"),
            Map.entry(RESOURCES + "book-queries-v1/manifest.properties",
                    "ec4efab61f53dfdaf15e31304e2d39d9815e821042224cc806c8d8a3b7ea3bbc"),
            Map.entry(RESOURCES + "book-queries-v1/queries.psv",
                    "9b1b0b222f26932622c9fe38a8d4351a0a637bd41f49319d882152a44fe05e14"),
            Map.entry(RESOURCES + "book-queries-v2/.gitattributes",
                    "f3d12483dbf81a7cefab18a231f4d41254f3f913a4280fcea94ed01711f6df5f"),
            Map.entry(RESOURCES + "book-queries-v2/manifest.properties",
                    "35d9af0213c0a3e20277c1aa8045cb83b6ee9992637ffc0aa284c7870c5823cb"),
            Map.entry(RESOURCES + "book-queries-v2/review.psv",
                    "37dc4627c26edd9a0ff49d1f766672cb2c91293025ab8d9ef64bd415d13b40e6"),
            Map.entry(RESOURCES + "book-queries-v2/selection.psv",
                    "c095247df3d57b568afc15fa12eee9b5efa9bc9ac74f9f7dedd6ecfa9463418d"),
            Map.entry(DOCS + "book-feedback-v1-report.txt",
                    "52771d3528a8c1d1411aeddcd56405fa0f04bd17ba73701693cde67e89c992ea"),
            Map.entry(DOCS + "book-footprint-v1-report.txt",
                    "beefc295c9d40e709a82902c87754c99ea0d9e283eca1b86174a950e192f22c6"),
            Map.entry(DOCS + "book-footprint-v1-summary.md",
                    "f13a548daddcd6a4c94b49ea8cb4322e118cd77f3654d2b7573be19f29a27d6f"),
            Map.entry(DOCS + "book-hub-diagnostics-v1-report.txt",
                    "6c5e98e62a088aad9e990cd548643b82beb80afe6e6b4e290b116003946c0793"),
            Map.entry(DOCS + "book-judgment-coverage-v2-report.txt",
                    "00660a3e08b98806530d874c08eacb94044b4cd7b82803bb5f29d6d6225418f7"),
            Map.entry(DOCS + "book-latency-v1-report.txt",
                    "73f53117571d51c6a53c3107bad272bddda5091a971ac7b3183b7999f6cf3874"),
            Map.entry(DOCS + "book-latency-v1-summary.md",
                    "3fbec89f8f9b1b6e426f1c6a86781cc8b3d3f0adcefb7b954df13de6f1278e7b"),
            Map.entry(DOCS + "book-recall-v1-report.txt",
                    "86501f764e985835487a90b05ce5dbbdc2821455c94a97aaf5d443bba1a2ffd2"),
            Map.entry(DOCS + "book-unjudged-review-v2-report.txt",
                    "5263019587d9a2fc837bc5ee925393f1923933177a2f93f831eb888c4fbfb376")));

    @Test
    void frozenQuerySetsAndReportsAreByteIdentical() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (String path : FROZEN.keySet()) {
            actual.put(path, BookQueryDataset.sha256(Files.readAllBytes(ROOT.resolve(path))));
        }
        assertEquals(FROZEN, actual);
    }

    @Test
    void theFrozenQuerySetsGainNoFile() throws Exception {
        for (String dir : new String[] {"book-queries-v1", "book-queries-v2"}) {
            try (Stream<Path> files = Files.list(ROOT.resolve(RESOURCES + dir))) {
                var names = files.map(p -> RESOURCES + dir + "/" + p.getFileName()).collect(Collectors.toSet());
                assertEquals(FROZEN.keySet().stream().filter(k -> k.startsWith(RESOURCES + dir + "/"))
                        .collect(Collectors.toSet()), names, dir);
            }
        }
    }

    @Test
    void theContractDirectoryHoldsNoV3QueryOrGoldDecision() throws Exception {
        try (Stream<Path> files = Files.list(ROOT.resolve(DOCS))) {
            var v3 = files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("book-queries-v3-"))
                    .collect(Collectors.toSet());
            assertEquals(java.util.Set.of(BookQueryProtocolV3.PROTOCOL_FILE, BookQueryProtocolV3.PLAN_FILE), v3);
        }
        var e = assertThrows(IllegalStateException.class, () -> BookQueryDatasetV3.load(ROOT.resolve(DOCS)));
        assertTrue(e.getMessage().contains("not authored yet"), e.getMessage());
    }
}
