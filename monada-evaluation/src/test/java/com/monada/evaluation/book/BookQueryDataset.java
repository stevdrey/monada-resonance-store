package com.monada.evaluation.book;

import com.monada.storage.JsonStrings;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The judged query set {@code book-queries-v1} of issue #166 (Book 4/8): hand-written questions over the frozen
 * {@code book-corpus-v1} normalized body, each judged against byte spans and {@code book-chunking-v1} chunk keys.
 * Labels come from reading the text only; nothing here is produced by recall, ranking or a model. Every
 * inconsistency (contradictory, empty or orphan labels, digest drift, a hand-edited split) fails loading with an
 * {@link IllegalStateException} naming the offending case.
 */
record BookQueryDataset(Properties manifest, List<Case> cases, Map<String, ChunkRef> chunkMap) {

    static final String RESOURCE_ROOT = "/com/monada/evaluation/book/book-queries-v1/";
    static final String DATASET = "book-queries-v1";
    static final String PROTOCOL = "MANUAL_READING_OF_NORMALIZED_BODY_NO_RETRIEVAL";
    static final String SPLIT_METHOD = "per-category-sha256-rank-last-third-holdout-v1";
    static final String QUERIES_HEADER = "id|split|category|answerable|text|rationale";
    static final String JUDGMENTS_HEADER = "queryId|chunkKey|byteStart|byteEnd|spanSha256|grade|role|note";

    private static final Pattern ID = Pattern.compile("[A-Z]{3}-[0-9]{2}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> REQUIRED_KEYS = List.of("dataset", "datasetVersion", "format", "formatVersion",
            "corpus", "corpusVersion", "corpusManifest.sha256", "source.sizeBytes", "source.sha256",
            "chunking.policy", "chunkMap.file", "chunkMap.sha256", "chunksFile.sha256", "queries.file",
            "queries.sha256", "judgments.file", "judgments.sha256", "judgment.protocol", "judgment.draftedBy",
            "judgment.reviewStatus", "judgment.retrievalRunsBeforeFreeze", "split.method", "split.salt",
            "split.digest", "counts.queries", "counts.judgments", "counts.DEV", "counts.HOLDOUT");

    enum Split { DEV, HOLDOUT }

    enum Category { LITERAL, PARAPHRASE, NARRATIVE_TEMPORAL, MULTI_EVIDENCE, CONFOUNDER, AMBIGUOUS, NO_ANSWER }

    /** EVIDENCE answers the query (grade 1 partial, 2 direct); CONFOUNDER is judged non-relevant (grade 0). */
    enum Role { EVIDENCE, CONFOUNDER }

    record ChunkRef(String key, long byteStart, long byteEnd) {
    }

    record Judgment(String chunkKey, long byteStart, long byteEnd, String spanSha256, int grade, Role role,
                    String note) {
    }

    record Case(String id, Split split, Category category, boolean answerable, String text, String rationale,
                List<Judgment> judgments) {
        Case {
            judgments = List.copyOf(judgments);
        }

        List<Judgment> evidence() {
            return judgments.stream().filter(j -> j.role() == Role.EVIDENCE).toList();
        }

        List<Judgment> confounders() {
            return judgments.stream().filter(j -> j.role() == Role.CONFOUNDER).toList();
        }
    }

    BookQueryDataset {
        cases = List.copyOf(cases);
        chunkMap = Map.copyOf(chunkMap);
    }

    List<Case> casesOf(Category category) {
        return cases.stream().filter(c -> c.category() == category).toList();
    }

    List<Case> casesOf(Split split) {
        return cases.stream().filter(c -> c.split() == split).toList();
    }

    /** Loads and validates the bundled dataset. */
    static BookQueryDataset load() {
        Properties manifest = new Properties();
        try {
            manifest.load(new StringReader(new String(resource("manifest.properties"), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read manifest.properties", e);
        }
        return parse(manifest, resource(require(manifest, "queries.file")),
                resource(require(manifest, "judgments.file")), resource(require(manifest, "chunkMap.file")));
    }

    static byte[] resource(String name) {
        try (InputStream in = BookQueryDataset.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE_ROOT + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE_ROOT + name, e);
        }
    }

    /** Parses and validates one dataset generation; the manifest pins the digests of the three other files. */
    static BookQueryDataset parse(Properties manifest, byte[] queries, byte[] judgments, byte[] chunkMapBytes) {
        REQUIRED_KEYS.forEach(key -> require(manifest, key));
        check(DATASET.equals(manifest.getProperty("dataset")), "manifest dataset is not " + DATASET);
        check("monada-book-queries".equals(manifest.getProperty("format"))
                && "1".equals(manifest.getProperty("formatVersion")), "unsupported dataset format");
        check("1".equals(manifest.getProperty("datasetVersion")),
                "unsupported datasetVersion " + manifest.getProperty("datasetVersion"));
        check(PROTOCOL.equals(manifest.getProperty("judgment.protocol")),
                "unsupported judgment.protocol " + manifest.getProperty("judgment.protocol"));
        check("0".equals(manifest.getProperty("judgment.retrievalRunsBeforeFreeze")),
                "labels must be frozen before any retrieval run");
        String review = manifest.getProperty("judgment.reviewStatus");
        check(review.equals("REVIEWED") || review.equals("PENDING_HUMAN_REVIEW"), "unknown review status " + review);
        if (review.equals("REVIEWED")) {
            require(manifest, "judgment.reviewer");
            require(manifest, "judgment.reviewedAt");
        }
        check(SPLIT_METHOD.equals(manifest.getProperty("split.method")),
                "unsupported split method " + manifest.getProperty("split.method"));
        check(DATASET.concat("|").equals(manifest.getProperty("split.salt")), "unexpected split salt");
        checkDigest(manifest, "chunkMap.sha256", chunkMapBytes);
        checkDigest(manifest, "queries.sha256", queries);
        checkDigest(manifest, "judgments.sha256", judgments);

        long sourceBytes = Long.parseLong(manifest.getProperty("source.sizeBytes"));
        Map<String, ChunkRef> chunks = chunkMap(manifest, chunkMapBytes, sourceBytes);
        Map<String, Case> parsed = queries(queries);
        Map<String, List<Judgment>> byCase = judgments(judgments, parsed.keySet(), chunks, sourceBytes);

        List<Case> cases = new ArrayList<>();
        for (Case c : parsed.values()) {
            Case full = new Case(c.id(), c.split(), c.category(), c.answerable(), c.text(), c.rationale(),
                    byCase.getOrDefault(c.id(), List.of()));
            validateCase(full);
            cases.add(full);
        }
        validateSplit(manifest, cases);
        validateCounts(manifest, cases);
        return new BookQueryDataset(manifest, cases, chunks);
    }

    /** The frozen split rule: per category, rank ids by SHA-256(salt + id); the last floor(n/3) are HOLDOUT. */
    static Map<String, Split> expectedSplit(Map<String, Category> categories) {
        Map<String, Split> result = new TreeMap<>();
        for (Category category : Category.values()) {
            List<String> ids = categories.entrySet().stream().filter(e -> e.getValue() == category)
                    .map(Map.Entry::getKey).sorted(Comparator.comparing(id -> sha256(utf8(DATASET + "|" + id))))
                    .toList();
            int holdout = ids.size() / 3;
            for (int i = 0; i < ids.size(); i++) {
                result.put(ids.get(i), i >= ids.size() - holdout ? Split.HOLDOUT : Split.DEV);
            }
        }
        return result;
    }

    /** SHA-256 of the sorted {@code id:SPLIT\n} lines. */
    static String splitDigest(Map<String, Split> splits) {
        StringBuilder lines = new StringBuilder();
        new TreeMap<>(splits).forEach((id, split) -> lines.append(id).append(':').append(split).append('\n'));
        return sha256(utf8(lines.toString()));
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, ChunkRef> chunkMap(Properties manifest, byte[] bytes, long sourceBytes) {
        List<String> lines = lines(bytes, "chunk map");
        check(!lines.isEmpty(), "chunk map is empty");
        Map<String, String> header = sidecarHeader(lines.get(0));
        check("monada-book-chunk-sidecar".equals(header.get("format")) && "1".equals(header.get("formatVersion")),
                "chunk map is not a format 1 chunk sidecar");
        expect(header, "corpus", manifest.getProperty("corpus"));
        expect(header, "corpusVersion", manifest.getProperty("corpusVersion"));
        expect(header, "sourceBytes", manifest.getProperty("source.sizeBytes"));
        expect(header, "sourceSha256", manifest.getProperty("source.sha256"));
        expect(header, "manifestSha256", manifest.getProperty("corpusManifest.sha256"));
        expect(header, "policy.id", manifest.getProperty("chunking.policy"));
        expect(header, "chunksFileSha256", manifest.getProperty("chunksFile.sha256"));
        check(Integer.parseInt(header.get("chunkCount")) == lines.size() - 1,
                "chunk map header chunkCount does not match its entries");

        Map<String, ChunkRef> chunks = new LinkedHashMap<>();
        String keyPrefix = manifest.getProperty("corpus") + "/" + manifest.getProperty("chunking.policy") + "/";
        for (String line : lines.subList(1, lines.size())) {
            Map<String, String> entry = JsonStrings.parseFlat(line, "chunk map");
            String key = entry.get("key");
            check(key != null && key.startsWith(keyPrefix), "chunk map key outside corpus/policy: " + key);
            ChunkRef ref = new ChunkRef(key, Long.parseLong(entry.get("byteStart")),
                    Long.parseLong(entry.get("byteEnd")));
            check(0 <= ref.byteStart() && ref.byteStart() < ref.byteEnd() && ref.byteEnd() <= sourceBytes,
                    "chunk map span out of source bounds: " + key);
            check(chunks.put(key, ref) == null, "duplicate chunk key in chunk map: " + key);
        }
        return chunks;
    }

    /** Flat fields of the sidecar header, with the nested {@code policy} object lifted to {@code policy.*}. */
    private static Map<String, String> sidecarHeader(String line) {
        String marker = "\"policy\":{";
        int start = line.indexOf(marker);
        check(start >= 0, "chunk map header has no policy object");
        int close = line.indexOf('}', start);
        check(close > start, "chunk map header policy object is not closed");
        Map<String, String> header = new LinkedHashMap<>(JsonStrings.parseFlat(
                line.substring(0, start) + "\"policy\":null" + line.substring(close + 1), "chunk map header"));
        JsonStrings.parseFlat(line.substring(start + marker.length() - 1, close + 1), "chunk map policy")
                .forEach((name, value) -> header.put("policy." + name, value));
        return header;
    }

    private static Map<String, Case> queries(byte[] bytes) {
        List<String> lines = lines(bytes, "queries.psv");
        check(!lines.isEmpty() && QUERIES_HEADER.equals(lines.get(0)), "queries.psv header must be " + QUERIES_HEADER);
        Map<String, Case> cases = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\\|", -1);
            check(f.length == 6, "queries.psv row needs 6 columns: " + line);
            String id = f[0];
            check(ID.matcher(id).matches(), "malformed query id: " + id);
            Category category = parseEnum(Category.class, f[2], id, "category");
            check(f[3].equals("true") || f[3].equals("false"), id + ": answerable must be true or false");
            boolean answerable = Boolean.parseBoolean(f[3]);
            check(answerable == (category != Category.NO_ANSWER),
                    id + ": answerable must be false exactly for NO_ANSWER");
            check(!f[4].isBlank() && !f[5].isBlank(), id + ": query text and rationale must not be blank");
            Case c = new Case(id, parseEnum(Split.class, f[1], id, "split"), category, answerable, f[4], f[5],
                    List.of());
            check(cases.put(id, c) == null, "duplicate query id: " + id);
        }
        check(!cases.isEmpty(), "queries.psv has no queries");
        return cases;
    }

    private static Map<String, List<Judgment>> judgments(byte[] bytes, Set<String> ids, Map<String, ChunkRef> chunks,
                                                         long sourceBytes) {
        List<String> lines = lines(bytes, "judgments.psv");
        check(!lines.isEmpty() && JUDGMENTS_HEADER.equals(lines.get(0)),
                "judgments.psv header must be " + JUDGMENTS_HEADER);
        Map<String, List<Judgment>> byCase = new LinkedHashMap<>();
        Set<String> seenChunks = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\\|", -1);
            check(f.length == 8, "judgments.psv row needs 8 columns: " + line);
            String id = f[0];
            check(ids.contains(id), "orphan judgment for unknown query: " + id);
            ChunkRef chunk = chunks.get(f[1]);
            check(chunk != null, id + ": orphan judgment for unknown chunk key " + f[1]);
            long start = parseLong(f[2], id);
            long end = parseLong(f[3], id);
            check(0 <= start && start < end && end <= sourceBytes, id + ": span [" + start + "," + end
                    + ") is empty or outside the source");
            check(chunk.byteStart() <= start && end <= chunk.byteEnd(), id + ": span [" + start + "," + end
                    + ") is not inside chunk " + chunk.key());
            check(SHA256.matcher(f[4]).matches(), id + ": spanSha256 is not a lowercase SHA-256");
            // Kept as long until validated, so an out-of-range value cannot wrap into a valid grade.
            long grade = parseLong(f[5], id);
            Role role = parseEnum(Role.class, f[6], id, "role");
            check(role == Role.EVIDENCE ? grade == 1 || grade == 2 : grade == 0,
                    id + ": contradictory label, " + role + " with grade " + grade + " on " + chunk.key());
            check(!f[7].isBlank(), id + ": judgment note must not be blank");
            check(seenChunks.add(id + "\u0000" + chunk.key()),
                    id + ": contradictory label, chunk " + chunk.key() + " is judged more than once");
            byCase.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new Judgment(chunk.key(), start, end, f[4], (int) grade, role, f[7]));
        }
        return byCase;
    }

    private static void validateCase(Case c) {
        int evidence = c.evidence().size();
        if (!c.answerable()) {
            check(evidence == 0, c.id() + ": NO_ANSWER case must not have evidence");
            return;
        }
        check(evidence > 0, c.id() + ": empty label, answerable case has no evidence");
        check(c.evidence().stream().anyMatch(j -> j.grade() == 2), c.id() + ": no evidence directly answers it");
        switch (c.category()) {
            case MULTI_EVIDENCE, AMBIGUOUS -> check(evidence >= 2,
                    c.id() + ": " + c.category() + " needs evidence in at least two chunks");
            case CONFOUNDER -> check(!c.confounders().isEmpty(), c.id() + ": CONFOUNDER case has no confounder");
            default -> { }
        }
    }

    private static void validateSplit(Properties manifest, List<Case> cases) {
        Map<String, Category> categories = new LinkedHashMap<>();
        Map<String, Split> declared = new TreeMap<>();
        cases.forEach(c -> {
            categories.put(c.id(), c.category());
            declared.put(c.id(), c.split());
        });
        Map<String, Split> expected = expectedSplit(categories);
        for (Case c : cases) {
            check(expected.get(c.id()) == c.split(), c.id() + ": split " + c.split() + " differs from the frozen rule ("
                    + expected.get(c.id()) + ")");
        }
        check(splitDigest(declared).equals(manifest.getProperty("split.digest")), "split.digest does not match");
    }

    private static void validateCounts(Properties manifest, List<Case> cases) {
        checkCount(manifest, "counts.queries", cases.size());
        checkCount(manifest, "counts.judgments", cases.stream().mapToInt(c -> c.judgments().size()).sum());
        for (Split split : Split.values()) {
            checkCount(manifest, "counts." + split, cases.stream().filter(c -> c.split() == split).count());
        }
        Map<Category, Long> perCategory = new EnumMap<>(Category.class);
        cases.forEach(c -> perCategory.merge(c.category(), 1L, Long::sum));
        for (Category category : Category.values()) {
            checkCount(manifest, "counts." + category, perCategory.getOrDefault(category, 0L));
        }
    }

    private static void checkCount(Properties manifest, String key, long actual) {
        check(String.valueOf(actual).equals(require(manifest, key)),
                key + " is " + manifest.getProperty(key) + " but the data has " + actual);
    }

    private static void checkDigest(Properties manifest, String key, byte[] bytes) {
        check(sha256(bytes).equals(manifest.getProperty(key)), key + " does not match the file");
    }

    private static void expect(Map<String, String> header, String field, String expected) {
        check(expected.equals(header.get(field)), "chunk map " + field + " is " + header.get(field)
                + ", expected " + expected);
    }

    private static List<String> lines(byte[] bytes, String name) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        check(Arrays.equals(utf8(text), bytes), name + " is not valid UTF-8");
        check(text.endsWith("\n") && !text.contains("\r"), name + " must use LF line ends and end with one");
        return List.of(text.substring(0, text.length() - 1).split("\n", -1));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String id, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(id + ": unknown " + field + " " + value);
        }
    }

    private static long parseLong(String value, String id) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(id + ": not a number: " + value);
        }
    }

    private static String require(Properties manifest, String key) {
        String value = manifest.getProperty(key);
        check(value != null && !value.isBlank(), "manifest is missing " + key);
        return value;
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
