package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A seeded Store from the original {@code book-chunking-fixture-v1} text plus a small judged dataset bound to it
 * (Issue #167 tests). The fixture policy yields 10 chunks; {@code s002/c0006} repeats the text of {@code s002/c0003}.
 * Judged spans are synthetic; only their containment in the chunk is checked by the dataset loader.
 */
final class BookRecallFixture {
    static final String FIXTURE = "/book-chunking-fixture-v1/";
    static final BookChunkingPolicy POLICY = new BookChunkingPolicy("fixture-max200", "CHAPTER [IVXLC]+\\.", 200, 0);
    static final String KEY = "book-chunking-fixture/fixture-max200/";
    static final String SPAN = "b".repeat(64);

    /** {@code id|category|text|rationale}; the split and answerable columns are derived. */
    static final List<String> QUERIES = List.of(
            "LIT-01|LITERAL|How many gulls circled the breakwater?|Counted in chapter one.",
            "LIT-02|LITERAL|The tide turns twice a day.|Repeated line, stored once.",
            "LIT-03|LITERAL|When did the cafe lights blink across the water?|Stated in chapter one.",
            "MUL-01|MULTI_EVIDENCE|Where does a lantern appear?|In the song and in the keeper's entry.",
            "CON-01|CONFOUNDER|Which song names the rope and the oar?|The indented song.",
            "NOA-01|NO_ANSWER|What colour is the keeper's coat?|Never described.");

    /** {@code queryId|chunk suffix|byteStart|byteEnd|grade|role|note}. */
    static final List<String> JUDGMENTS = List.of(
            "LIT-01|s001/c0000|194|275|2|EVIDENCE|gull count",
            "LIT-02|s002/c0006|984|1011|2|EVIDENCE|the repeated line, second occurrence",
            "LIT-03|s001/c0000|277|327|2|EVIDENCE|cafe lights at dusk",
            "MUL-01|s001/c0001|363|422|1|EVIDENCE|lantern in the song",
            "MUL-01|s002/c0002|647|717|2|EVIDENCE|lantern along the shore",
            "CON-01|s001/c0001|363|422|2|EVIDENCE|the song",
            "CON-01|s002/c0002|647|717|0|CONFOUNDER|lantern without the song",
            "NOA-01|s002/c0001|446|646|0|CONFOUNDER|keeper mentioned, coat never described");

    private BookRecallFixture() {
    }

    /** Seeds {@code dir/name} from the fixture with {@code policy}. */
    static Path seededWork(Path dir, String name, BookChunkingPolicy policy) throws Exception {
        Path inputs = Files.createDirectories(dir.resolve(name + "-inputs"));
        BookChunker.Result result = BookChunker.chunk(VerifiedBookSource.load(copy(inputs, "manifest.properties"),
                copy(inputs, "body.txt")), policy);
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve(name), List.of());
        assertTrue(BookStoreReadback.seed(result, work).passed());
        return work;
    }

    private static Path copy(Path dir, String name) throws IOException {
        try (InputStream in = BookRecallFixture.class.getResourceAsStream(FIXTURE + name)) {
            assertNotNull(in, "missing fixture " + name);
            Path target = dir.resolve(name);
            Files.write(target, in.readAllBytes());
            return target;
        }
    }

    /** The fixture dataset bound to the chunk set in {@code work}. */
    static BookQueryDataset dataset(Path work) throws Exception {
        return dataset(work, QUERIES, JUDGMENTS);
    }

    static BookQueryDataset dataset(Path work, List<String> queryRows, List<String> judgmentRows) throws Exception {
        byte[] chunkMap = Files.readAllBytes(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR)
                .resolve(BookChunkOutputWriter.SIDECAR_FILE));
        Map<String, String> header = BookChunkFiles.header(new String(chunkMap, StandardCharsets.UTF_8).lines()
                .findFirst().orElseThrow());
        String prefix = header.get("corpus") + "/" + header.get("policy.id") + "/";

        Map<String, BookQueryDataset.Category> categories = new LinkedHashMap<>();
        for (String row : queryRows) {
            String[] f = row.split("\\|", -1);
            categories.put(f[0], BookQueryDataset.Category.valueOf(f[1]));
        }
        Map<String, BookQueryDataset.Split> splits = BookQueryDataset.expectedSplit(categories);
        List<String> queries = new ArrayList<>(List.of(BookQueryDataset.QUERIES_HEADER));
        Map<String, Integer> counts = new TreeMap<>();
        for (String row : queryRows) {
            String[] f = row.split("\\|", -1);
            BookQueryDataset.Split split = splits.get(f[0]);
            queries.add(f[0] + "|" + split + "|" + f[1] + "|" + !f[1].equals("NO_ANSWER") + "|" + f[2] + "|" + f[3]);
            counts.merge(split.name(), 1, Integer::sum);
            counts.merge(f[1], 1, Integer::sum);
        }
        List<String> judgments = new ArrayList<>(List.of(BookQueryDataset.JUDGMENTS_HEADER));
        for (String row : judgmentRows) {
            String[] f = row.split("\\|", -1);
            judgments.add(f[0] + "|" + prefix + f[1] + "|" + f[2] + "|" + f[3] + "|" + SPAN + "|" + f[4] + "|" + f[5]
                    + "|" + f[6]);
        }
        byte[] q = bytes(queries);
        byte[] j = bytes(judgments);

        Properties m = new Properties();
        m.setProperty("dataset", BookQueryDataset.DATASET);
        m.setProperty("datasetVersion", "1");
        m.setProperty("format", "monada-book-queries");
        m.setProperty("formatVersion", "1");
        m.setProperty("corpus", header.get("corpus"));
        m.setProperty("corpusVersion", header.get("corpusVersion"));
        m.setProperty("corpusManifest.sha256", header.get("manifestSha256"));
        m.setProperty("source.sizeBytes", header.get("sourceBytes"));
        m.setProperty("source.sha256", header.get("sourceSha256"));
        m.setProperty("chunking.policy", header.get("policy.id"));
        m.setProperty("chunkMap.file", "chunk-map.jsonl");
        m.setProperty("chunkMap.sha256", BookQueryDataset.sha256(chunkMap));
        m.setProperty("chunksFile.sha256", header.get("chunksFileSha256"));
        m.setProperty("queries.file", "queries.psv");
        m.setProperty("queries.sha256", BookQueryDataset.sha256(q));
        m.setProperty("judgments.file", "judgments.psv");
        m.setProperty("judgments.sha256", BookQueryDataset.sha256(j));
        m.setProperty("judgment.protocol", BookQueryDataset.PROTOCOL);
        m.setProperty("judgment.grades", BookQueryDataset.GRADES);
        m.setProperty("judgment.draftedBy", "test");
        m.setProperty("judgment.reviewStatus", "PENDING_HUMAN_REVIEW");
        m.setProperty("judgment.retrievalRunsBeforeFreeze", "0");
        m.setProperty("split.method", BookQueryDataset.SPLIT_METHOD);
        m.setProperty("split.salt", BookQueryDataset.DATASET + "|");
        m.setProperty("split.digest", BookQueryDataset.splitDigest(splits));
        for (String name : List.of("DEV", "HOLDOUT")) {
            m.setProperty("counts." + name, String.valueOf(counts.getOrDefault(name, 0)));
        }
        for (BookQueryDataset.Category category : BookQueryDataset.Category.values()) {
            m.setProperty("counts." + category, String.valueOf(counts.getOrDefault(category.name(), 0)));
        }
        m.setProperty("counts.queries", String.valueOf(queryRows.size()));
        m.setProperty("counts.judgments", String.valueOf(judgmentRows.size()));
        return BookQueryDataset.parse(m, q, j, chunkMap);
    }

    /** The content-derived atom id of the chunk {@code suffix} (for example {@code s001/c0000}) in {@code work}. */
    static String atomId(Path work, String suffix) throws Exception {
        for (BookChunkFiles.Entry entry : BookChunkFiles.load(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR))
                .entries()) {
            if (entry.key().endsWith("/" + suffix)) {
                return KnowledgeAtom.text(entry.text()).id();
            }
        }
        throw new IllegalArgumentException("no chunk " + suffix);
    }

    private static byte[] bytes(List<String> lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
