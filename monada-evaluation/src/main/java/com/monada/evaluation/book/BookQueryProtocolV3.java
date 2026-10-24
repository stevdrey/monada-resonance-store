package com.monada.evaluation.book;

import com.monada.evaluation.book.BookQueryDataset.Category;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The prospective {@code book-queries-v3} protocol of issue #185 (Phase II 2/10): a slot plan committed before any
 * v3 query is written or any ranking is inspected. Each of the {@value #SLOTS} slots has a fixed category and a fixed
 * {@link Split}, so a later author fills predeclared slots and cannot choose a query, an answer or a split from
 * retrieval output. The plan holds no query text, answer, span, score or book byte.
 *
 * <p>The protocol is a draft until a named human reviewer, who is neither its drafter nor a model, signs it
 * ({@code protocol.status=REVIEWED}). {@link BookQueryDatasetV3} refuses to sign off a dataset over an unsigned
 * protocol. Every defect fails loading with an {@link IllegalStateException}.
 */
record BookQueryProtocolV3(Properties manifest, List<Slot> slots) {

    static final String DATASET = "book-queries-v3";
    static final String FORMAT = "monada-book-queries-prospective";
    static final String PROTOCOL_FILE = "book-queries-v3-protocol.properties";
    static final String PLAN_FILE = "book-queries-v3-plan.psv";
    static final String PLAN_HEADER = "slot|category|split";
    static final String PROVENANCE = "PROSPECTIVE_SCORE_BLIND";
    static final String SPLIT_METHOD = "per-category-sha256-rank-predeclared-final-only-v1";
    static final String SPLIT_SALT = DATASET + "|";
    static final String FINAL_GATE_POLICY = "separate-declared-final-gate-v1";
    static final String PENDING = "PENDING_HUMAN_REVIEW";
    static final String REVIEWED = "REVIEWED";
    /** Fixed by issue #185: 40 slots, 28 DEV and 12 FINAL_ONLY. */
    static final int SLOTS = 40;
    static final int FINAL_ONLY_SLOTS = 12;
    /** Minimums fixed by issue #185; a protocol cannot declare lower ones. */
    static final int MIN_NO_ANSWER = 8;
    static final int MIN_PARAPHRASE = 8;
    static final int MIN_NARRATIVE_MULTI_DISTRACTOR = 8;
    static final Set<Category> NARRATIVE_MULTI_DISTRACTOR =
            Set.of(Category.NARRATIVE_TEMPORAL, Category.MULTI_EVIDENCE, Category.CONFOUNDER);
    /** The v1 corpus and chunk binding that v3 judgments must use, compared with the unchanged v1 manifest. */
    static final List<String> BINDING_KEYS = List.of("corpus", "corpusVersion", "corpusManifest.sha256",
            "source.sizeBytes", "source.sha256", "chunking.policy", "chunkMap.sha256", "chunksFile.sha256");

    private static final Pattern SLOT = Pattern.compile("V3-[A-Z]{3}-[0-9]{2}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** Lower-cased name tokens that identify a model or an automated agent rather than a human. */
    private static final Set<String> MODEL_TOKENS = Set.of("ai", "agent", "assistant", "bot", "chatgpt", "claude",
            "codex", "copilot", "cursor", "devin", "gemini", "gpt", "llama", "llm", "mistral", "model", "openai",
            "anthropic", "windsurf");
    /** Retrieval output must never steer the protocol: no key or column may look like a run result. */
    private static final Pattern SCORE_LIKE = Pattern.compile("(?i).*(score|rank|hit|recall|mrr|ndcg|precision).*");
    private static final List<String> REQUIRED_KEYS = List.of("dataset", "protocolVersion", "format",
            "formatVersion", "provenance", "corpus", "corpusVersion", "corpusManifest.sha256", "source.sizeBytes",
            "source.sha256", "chunking.policy", "chunkMap.sha256", "chunksFile.sha256", "plan.file", "plan.sha256",
            "split.method", "split.salt", "split.digest", "authoring.protocol",
            "authoring.retrievalRunsBeforeFreeze", "finalGate.policy", "finalGate.initialStatus", "queries.status",
            "protocol.draftedBy", "protocol.draftedAt", "protocol.status", "counts.slots", "counts.DEV",
            "counts.FINAL_ONLY", "min.NO_ANSWER", "min.PARAPHRASE", "min.NARRATIVE_MULTI_DISTRACTOR");

    /** {@code DEV}: inspect and tune freely. {@code FINAL_ONLY}: no automated evaluation before the final gate. */
    enum Split { DEV, FINAL_ONLY }

    record Slot(String id, Category category, Split split) {
    }

    BookQueryProtocolV3 {
        Properties copy = new Properties();
        copy.putAll(manifest);
        manifest = copy;
        slots = List.copyOf(slots);
    }

    @Override
    public Properties manifest() {
        Properties copy = new Properties();
        copy.putAll(manifest);
        return copy;
    }

    boolean reviewed() {
        return REVIEWED.equals(manifest.getProperty("protocol.status"));
    }

    Map<String, Slot> slotsById() {
        Map<String, Slot> byId = new LinkedHashMap<>();
        slots.forEach(s -> byId.put(s.id(), s));
        return byId;
    }

    /** Loads the committed protocol and plan from {@code dir} (the {@code book-corpus-v1} contract directory). */
    static BookQueryProtocolV3 load(Path dir) {
        Properties manifest = readProperties(read(dir.resolve(PROTOCOL_FILE)), PROTOCOL_FILE);
        check(PLAN_FILE.equals(manifest.getProperty("plan.file")), "plan.file must be " + PLAN_FILE);
        return parse(manifest, read(dir.resolve(PLAN_FILE)), BookQueryDataset.load().manifest());
    }

    /** Validates a protocol and its plan against the unchanged {@code book-queries-v1} manifest. */
    static BookQueryProtocolV3 parse(Properties manifest, byte[] plan, Properties v1) {
        REQUIRED_KEYS.forEach(key -> require(manifest, key));
        rejectScoreLikeKeys(manifest.stringPropertyNames(), PROTOCOL_FILE);
        check(DATASET.equals(manifest.getProperty("dataset")) && "1".equals(manifest.getProperty("protocolVersion")),
                "protocol is not " + DATASET + " protocol version 1");
        check(FORMAT.equals(manifest.getProperty("format")) && "1".equals(manifest.getProperty("formatVersion")),
                "unsupported protocol format");
        check(PROVENANCE.equals(manifest.getProperty("provenance")), "provenance must be " + PROVENANCE);
        for (String key : BINDING_KEYS) {
            check(manifest.getProperty(key).equals(v1.getProperty(key)),
                    key + " differs from the frozen book-queries-v1 binding");
        }
        check(BookQueryDataset.PROTOCOL.equals(manifest.getProperty("authoring.protocol")),
                "authoring.protocol must be " + BookQueryDataset.PROTOCOL);
        check("0".equals(manifest.getProperty("authoring.retrievalRunsBeforeFreeze")),
                "the plan must be frozen before any retrieval run");
        check(FINAL_GATE_POLICY.equals(manifest.getProperty("finalGate.policy"))
                && "CLOSED".equals(manifest.getProperty("finalGate.initialStatus")),
                "the final gate must follow " + FINAL_GATE_POLICY + " and start CLOSED");
        check("NOT_AUTHORED".equals(manifest.getProperty("queries.status")),
                "the protocol carries no queries: queries.status must be NOT_AUTHORED");
        checkDigest(manifest, "plan.sha256", plan);

        List<Slot> slots = slots(plan);
        validateQuotas(manifest, slots);
        validateSplit(manifest, slots);
        validateSignOff(manifest, "protocol", List.of(manifest.getProperty("protocol.draftedBy")));
        return new BookQueryProtocolV3(manifest, slots);
    }

    /** The predeclared rule: per category, rank slots by SHA-256(salt + slot); the last k are FINAL_ONLY. */
    static Map<String, Split> expectedSplit(Collection<Slot> slots, Map<Category, Integer> finalOnly) {
        Map<String, Split> result = new TreeMap<>();
        for (Category category : Category.values()) {
            List<String> ids = slots.stream().filter(s -> s.category() == category).map(Slot::id)
                    .sorted(Comparator.comparing(id -> BookQueryDataset.sha256(utf8(SPLIT_SALT + id)))).toList();
            int k = finalOnly.getOrDefault(category, 0);
            for (int i = 0; i < ids.size(); i++) {
                result.put(ids.get(i), i >= ids.size() - k ? Split.FINAL_ONLY : Split.DEV);
            }
        }
        return result;
    }

    /** SHA-256 of the sorted {@code slot:SPLIT\n} lines. */
    static String splitDigest(Map<String, Split> splits) {
        StringBuilder lines = new StringBuilder();
        new TreeMap<>(splits).forEach((id, split) -> lines.append(id).append(':').append(split).append('\n'));
        return BookQueryDataset.sha256(utf8(lines.toString()));
    }

    private static List<Slot> slots(byte[] bytes) {
        List<String> lines = lines(bytes, PLAN_FILE);
        check(!lines.isEmpty() && PLAN_HEADER.equals(lines.get(0)), PLAN_FILE + " header must be " + PLAN_HEADER);
        Map<String, Slot> slots = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\\|", -1);
            check(f.length == 3, PLAN_FILE + " row needs 3 columns: " + line);
            check(SLOT.matcher(f[0]).matches(), "malformed slot id: " + f[0]);
            Category category = parseEnum(Category.class, f[1], f[0], "category");
            check(f[0].substring(3, 6).equals(prefix(category)),
                    f[0] + ": slot prefix does not match category " + category);
            Slot slot = new Slot(f[0], category, parseEnum(Split.class, f[2], f[0], "split"));
            check(slots.put(slot.id(), slot) == null, "duplicate slot: " + slot.id());
        }
        return List.copyOf(slots.values());
    }

    /** The v1 id prefix of a category ({@code NOA} for {@code NO_ANSWER}). */
    static String prefix(Category category) {
        return category == Category.NO_ANSWER ? "NOA" : category.name().substring(0, 3);
    }

    private static void validateQuotas(Properties manifest, List<Slot> slots) {
        check(slots.size() == SLOTS, "the plan must have " + SLOTS + " slots, not " + slots.size());
        checkCount(manifest, "counts.slots", slots.size());
        for (Split split : Split.values()) {
            checkCount(manifest, "counts." + split, slots.stream().filter(s -> s.split() == split).count());
        }
        check(Integer.parseInt(manifest.getProperty("counts.FINAL_ONLY")) == FINAL_ONLY_SLOTS,
                "the plan must have " + FINAL_ONLY_SLOTS + " FINAL_ONLY slots");
        Map<Category, Long> perCategory = new EnumMap<>(Category.class);
        slots.forEach(s -> perCategory.merge(s.category(), 1L, Long::sum));
        for (Category category : Category.values()) {
            long n = perCategory.getOrDefault(category, 0L);
            checkCount(manifest, "counts." + category, n);
            checkCount(manifest, "counts." + category + ".FINAL_ONLY",
                    slots.stream().filter(s -> s.category() == category && s.split() == Split.FINAL_ONLY).count());
            check(n > 0, "category " + category + " has no slot");
        }
        long narrative = slots.stream().filter(s -> NARRATIVE_MULTI_DISTRACTOR.contains(s.category())).count();
        checkMinimum(manifest, "min.NO_ANSWER", MIN_NO_ANSWER, perCategory.getOrDefault(Category.NO_ANSWER, 0L));
        checkMinimum(manifest, "min.PARAPHRASE", MIN_PARAPHRASE, perCategory.getOrDefault(Category.PARAPHRASE, 0L));
        checkMinimum(manifest, "min.NARRATIVE_MULTI_DISTRACTOR", MIN_NARRATIVE_MULTI_DISTRACTOR, narrative);
    }

    private static void checkMinimum(Properties manifest, String key, int floor, long actual) {
        check(String.valueOf(floor).equals(manifest.getProperty(key)), key + " must be " + floor);
        check(actual >= floor, key + " is " + floor + " but the plan has " + actual);
    }

    private static void validateSplit(Properties manifest, List<Slot> slots) {
        check(SPLIT_METHOD.equals(manifest.getProperty("split.method")),
                "unsupported split method " + manifest.getProperty("split.method"));
        check(SPLIT_SALT.equals(manifest.getProperty("split.salt")), "unexpected split salt");
        Map<Category, Integer> finalOnly = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            finalOnly.put(category, Integer.parseInt(require(manifest, "counts." + category + ".FINAL_ONLY")));
        }
        Map<String, Split> expected = expectedSplit(slots, finalOnly);
        Map<String, Split> declared = new TreeMap<>();
        for (Slot s : slots) {
            check(expected.get(s.id()) == s.split(), s.id() + ": split " + s.split()
                    + " differs from the predeclared rule (" + expected.get(s.id()) + ")");
            declared.put(s.id(), s.split());
        }
        check(splitDigest(declared).equals(manifest.getProperty("split.digest")), "split.digest does not match");
    }

    /**
     * Shared human sign-off rule for {@code <prefix>.status}: {@value #PENDING} always validates (fail closed);
     * {@value #REVIEWED} needs a named reviewer and approver, an ISO review date, neither a drafter nor a model.
     */
    static void validateSignOff(Properties manifest, String prefix, Collection<String> drafters) {
        String status = require(manifest, prefix + ".status");
        check(status.equals(REVIEWED) || status.equals(PENDING), "unknown " + prefix + ".status " + status);
        if (!status.equals(REVIEWED)) {
            return;
        }
        String reviewer = require(manifest, prefix + ".reviewer").trim();
        String approver = require(manifest, prefix + ".approvedBy").trim();
        isoDate(manifest, prefix + ".reviewedAt");
        for (String human : List.of(reviewer, approver)) {
            check(!looksLikeModel(human), prefix + ": " + human + " is not a human reviewer; a model-only draft"
                    + " cannot be presented as reviewed");
            for (String drafter : drafters) {
                check(!human.equalsIgnoreCase(drafter.trim()),
                        prefix + ": REVIEWED needs a human reviewer and approver other than the drafter " + drafter);
            }
        }
    }

    static boolean looksLikeModel(String name) {
        for (String token : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (MODEL_TOKENS.contains(token) || token.startsWith("gpt") || token.startsWith("claude")) {
                return true;
            }
        }
        return false;
    }

    static LocalDate isoDate(Properties manifest, String key) {
        try {
            return LocalDate.parse(require(manifest, key));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(key + " is not an ISO date: " + manifest.getProperty(key));
        }
    }

    static void rejectScoreLikeKeys(Collection<String> names, String where) {
        for (String name : names) {
            check(!SCORE_LIKE.matcher(name).matches(), where + " must not carry retrieval output: " + name);
        }
    }

    static Properties readProperties(byte[] bytes, String name) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        check(Arrays.equals(utf8(text), bytes), name + " is not valid UTF-8");
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("malformed " + name + ": " + e.getMessage(), e);
        }
        return properties;
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    static List<String> lines(byte[] bytes, String name) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        check(Arrays.equals(utf8(text), bytes), name + " is not valid UTF-8");
        check(text.endsWith("\n") && !text.contains("\r"), name + " must use LF line ends and end with one");
        return new ArrayList<>(List.of(text.substring(0, text.length() - 1).split("\n", -1)));
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String id, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(id + ": unknown " + field + " " + value);
        }
    }

    static void checkDigest(Properties manifest, String key, byte[] bytes) {
        String pinned = require(manifest, key);
        check(SHA256.matcher(pinned).matches(), key + " is not a lowercase SHA-256");
        check(BookQueryDataset.sha256(bytes).equals(pinned), key + " does not match the file");
    }

    static void checkCount(Properties manifest, String key, long actual) {
        check(String.valueOf(actual).equals(require(manifest, key)),
                key + " is " + manifest.getProperty(key) + " but the data has " + actual);
    }

    static String require(Properties manifest, String key) {
        String value = manifest.getProperty(key);
        check(value != null && !value.isBlank(), "manifest is missing " + key);
        return value;
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
