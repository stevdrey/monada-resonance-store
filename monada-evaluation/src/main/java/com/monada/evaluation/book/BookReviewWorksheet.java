package com.monada.evaluation.book;

import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.ChunkRef;
import com.monada.evaluation.book.BookQueryDatasetV2.Decision;
import com.monada.evaluation.book.BookUnjudgedSelection.Pair;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The score-blind human review of {@code book-queries-v2} (issue #177). {@link #render} writes a local worksheet that
 * shows, per selected pair, only a neutral pair id, the query, whether v1 declares it answerable, and the verified
 * chunk text. Rank, score, hub frequency, stratum, chunk key, byte offsets and any proposed label are never shown,
 * and the order is a seeded shuffle. The worksheet holds book text, so it must stay outside Git.
 *
 * <p>{@link #apply} reads the filled worksheet and produces {@code review.psv} rows without book text: each quoted
 * span is located in its chunk (whitespace runs match any whitespace run; it must occur exactly once) and turned
 * into a byte span plus SHA-256 of the verified body. A blank decision leaves the pair pending. Nothing here can
 * set the review status; sign-off is a manual manifest edit by the human reviewer.
 */
final class BookReviewWorksheet {
    static final String TITLE = "# book-queries-v2 review worksheet (issue #177) - LOCAL ONLY, DO NOT COMMIT";

    static final String PROTOCOL_LINE = "protocol: ";
    private static final String INSTRUCTIONS = """
        Decide each pair from the query and the chunk text alone. For every entry fill in:

        - `decision:` one of `EVIDENCE` (the chunk answers the query), `CONFOUNDER` (judged non-relevant),
          `UNRESOLVED` (evidence insufficient to decide) or `NO_ANSWER_CONTRADICTED` (only for a query that
          book-queries-v1 declares NO_ANSWER, when this chunk does answer it). Leave it blank to keep the pair
          pending.
        - `grade:` `2` answers directly, `1` partial or supporting (EVIDENCE, NO_ANSWER_CONTRADICTED); `0` for
          CONFOUNDER; empty for UNRESOLVED.
        - `quote:` the exact passage of the chunk the decision rests on (copy it; line breaks may be written as
          spaces). It must occur once in the chunk. For CONFOUNDER quote the misleading passage. Empty for
          UNRESOLVED.
        - `note:` a short paraphrase or, for UNRESOLVED, the reason. No `|` and no book quotation.

        """;

    private static final Pattern ENTRY = Pattern.compile("(?m)^## (UJ-[0-9a-f]{8})$");
    /** Consecutive chunk words a note may not reproduce; the committed notes share at most 3. */
    static final int COPIED_WORDS = 6;
    private static final List<String> FIELDS = List.of("decision", "grade", "quote", "note");
    private static final Pattern FIELD = Pattern.compile("(?m)^(decision|grade|quote|note):[ \\t]*(.*)$");

    private BookReviewWorksheet() {
    }

    static String render(byte[] body, BookQueryDataset base, List<Pair> selection) {
        Map<String, Case> cases = cases(base);
        StringBuilder out = new StringBuilder(preamble(false));
        List<Pair> order = selection.stream()
                .sorted(Comparator.comparing(p -> BookQueryDataset.sha256(
                        (BookUnjudgedSelection.SEED + "worksheet|" + p.pairId()).getBytes(StandardCharsets.UTF_8))))
                .toList();
        for (Pair p : order) {
            Case c = cases.get(p.queryId());
            ChunkRef chunk = base.chunkMap().get(p.chunkKey());
            out.append(context(p, c, chunk, body, false)).append('\n');
            out.append("decision: \ngrade: \nquote: \nnote: \n\n");
        }
        return out.toString();
    }

    /**
     * The immutable head of a worksheet: title, protocol line and instructions. The first renderer ({@code legacy})
     * wrote no protocol line; its instructions were the same.
     */
    static String preamble(boolean legacy) {
        String protocol = legacy ? "" : PROTOCOL_LINE + BookQueryDatasetV2.REVIEW_PROTOCOL + "\n\n";
        return TITLE + "\n\n" + protocol + INSTRUCTIONS;
    }

    /**
     * The read-only part of one entry: heading, query, answerability and chunk text. The first renderer
     * ({@code legacy}) also printed a {@code chunk:} line with the key and byte range; the current one does not, since
     * a repeated key would reveal hub chunks and an offset the story position.
     */
    private static String context(Pair p, Case c, ChunkRef chunk, byte[] body, boolean legacy) {
        StringBuilder out = new StringBuilder("## ").append(p.pairId()).append("\n\n");
        out.append("query: ").append(c.text()).append('\n');
        out.append("declared: ").append(c.answerable() ? "answerable" : "NO_ANSWER (v1: the book does not answer it)")
                .append('\n');
        if (legacy) {
            out.append("chunk: ").append(p.chunkKey()).append(" bytes [").append(chunk.byteStart()).append(',')
                    .append(chunk.byteEnd()).append(")\n");
        }
        return out.append("\n~~~text\n").append(chunkText(body, chunk)).append("\n~~~\n").toString();
    }

    /**
     * The review protocol a filled worksheet was produced under, from its unedited preamble: the current one, or the
     * legacy one (no protocol line; that renderer printed chunk keys and byte ranges). Edited instructions, such as
     * swapped decision meanings, are rejected, since the decisions would not follow the recorded protocol.
     */
    static String protocolOf(String worksheet) {
        if (worksheet.startsWith(preamble(false))) {
            return BookQueryDatasetV2.REVIEW_PROTOCOL;
        }
        check(worksheet.startsWith(preamble(true)), "worksheet title, protocol line or instructions were edited");
        return BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL;
    }

    /** The {@code review.psv} bytes for every decided entry of {@code worksheet}, sorted by pair id. */
    static String apply(String worksheet, byte[] body, BookQueryDataset base, List<Pair> selection) {
        Map<String, Pair> pairs = selection.stream()
                .collect(Collectors.toMap(Pair::pairId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        // The entry layout must match the protocol the preamble declares: a legacy entry (chunk key and byte range) in a
        // current-protocol sheet would claim the stricter blinding while exposing those identifiers.
        boolean legacy = BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL.equals(protocolOf(worksheet));
        Map<String, Case> cases = cases(base);
        Map<String, String> rows = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        Matcher entry = ENTRY.matcher(worksheet);
        int start = entry.find() ? entry.start() : -1;
        while (start >= 0) {
            String id = entry.group(1);
            int next = entry.find() ? entry.start() : -1;
            String section = worksheet.substring(start, next < 0 ? worksheet.length() : next);
            start = next;
            Pair pair = pairs.get(id);
            check(pair != null, id + ": worksheet entry is not in selection.psv");
            check(seen.add(id), id + ": worksheet has this entry twice");
            // The decision must be about the context the renderer showed: an edited query, answerability or chunk text
            // (or two swapped entries) would attach it to a pair the reviewer did not see.
            Case c = cases.get(pair.queryId());
            ChunkRef chunk = base.chunkMap().get(pair.chunkKey());
            check(section.startsWith(context(pair, c, chunk, body, legacy)),
                    id + ": worksheet entry context (query, answerability or chunk text) was edited");
            // Fields come after the chunk text, so a field-like line of book text cannot be read as a decision.
            int fence = section.lastIndexOf("\n~~~\n");
            Map<String, String> fields = fields(fence < 0 ? section : section.substring(fence));
            // Only a present, empty decision means pending: a deleted or misspelled field would otherwise silently drop a
            // recorded decision when review.psv is rewritten.
            List<String> absent = FIELDS.stream().filter(f -> !fields.containsKey(f)).toList();
            check(absent.isEmpty(), id + ": worksheet entry is missing its " + String.join(", ", absent) + " field");
            String decision = fields.getOrDefault("decision", "");
            if (decision.isEmpty()) {
                continue;
            }
            String note = fields.getOrDefault("note", "");
            check(!note.isBlank() && !note.contains("|"), id + ": note must be non-blank and contain no |");
            // review.psv is committed and must hold no book text: a note may name things, not copy a passage.
            check(!copiesChunkText(note, chunkText(body, chunk)), id + ": note reproduces " + COPIED_WORDS
                    + " or more consecutive words of the chunk; paraphrase it, the quote stays local");
            String row;
            Decision d = parseDecision(decision, id);
            if (d == Decision.UNRESOLVED) {
                check(fields.getOrDefault("grade", "").isEmpty() && fields.getOrDefault("quote", "").isEmpty(),
                        id + ": UNRESOLVED takes no grade and no quote");
                row = String.join("|", id, pair.queryId(), pair.chunkKey(), d.name(), "-", "-", "-", "-", note);
            } else {
                String grade = fields.getOrDefault("grade", "");
                long[] span = locate(body, base.chunkMap().get(pair.chunkKey()), fields.getOrDefault("quote", ""), id);
                byte[] bytes = Arrays.copyOfRange(body, (int) span[0], (int) span[1]);
                row = String.join("|", id, pair.queryId(), pair.chunkKey(), d.name(), grade, String.valueOf(span[0]),
                        String.valueOf(span[1]), BookQueryDataset.sha256(bytes), note);
            }
            check(rows.put(id, row) == null, id + ": worksheet decides this pair twice");
        }
        // apply rewrites review.psv rather than merging it: a truncated sheet or a mistyped heading would otherwise
        // silently turn recorded decisions back into pending pairs.
        List<String> missing = pairs.keySet().stream().filter(id -> !seen.contains(id)).toList();
        check(missing.isEmpty(), "worksheet is missing " + missing.size() + " selected entries: " + missing);
        StringBuilder out = new StringBuilder(BookQueryDatasetV2.REVIEW_HEADER).append('\n');
        rows.values().forEach(r -> out.append(r).append('\n'));
        return out.toString();
    }

    /** Whether {@code note} shares a run of {@value #COPIED_WORDS} or more whitespace-separated words with {@code text}. */
    static boolean copiesChunkText(String note, String text) {
        String[] chunkWords = text.trim().split("\\s+");
        Set<String> runs = new HashSet<>();
        for (int i = 0; i + COPIED_WORDS <= chunkWords.length; i++) {
            runs.add(String.join(" ", Arrays.copyOfRange(chunkWords, i, i + COPIED_WORDS)));
        }
        String[] noteWords = note.trim().split("\\s+");
        for (int i = 0; i + COPIED_WORDS <= noteWords.length; i++) {
            if (runs.contains(String.join(" ", Arrays.copyOfRange(noteWords, i, i + COPIED_WORDS)))) {
                return true;
            }
        }
        return false;
    }

    /** The byte span of {@code quote} inside {@code chunk}; whitespace runs in the quote match any whitespace run. */
    static long[] locate(byte[] body, ChunkRef chunk, String quote, String id) {
        String[] words = quote.trim().split("\\s+");
        check(!quote.isBlank(), id + ": a quote from the chunk is required for this decision");
        Pattern pattern = Pattern.compile(Arrays.stream(words).map(Pattern::quote).collect(Collectors.joining("\\s+")));
        String text = chunkText(body, chunk);
        Matcher m = pattern.matcher(text);
        check(m.find(), id + ": quote does not occur in " + chunk.key());
        int from = m.start();
        int to = m.end();
        // Search again from just after the first match's start, so an overlapping repetition also counts.
        check(!m.find(from + 1), id + ": quote occurs more than once in " + chunk.key() + "; quote a longer passage");
        long start = chunk.byteStart() + text.substring(0, from).getBytes(StandardCharsets.UTF_8).length;
        long end = chunk.byteStart() + text.substring(0, to).getBytes(StandardCharsets.UTF_8).length;
        return new long[] {start, end};
    }

    private static String chunkText(byte[] body, ChunkRef chunk) {
        check(chunk.byteEnd() <= body.length, "body is shorter than chunk " + chunk.key());
        return new String(body, (int) chunk.byteStart(), (int) (chunk.byteEnd() - chunk.byteStart()),
                StandardCharsets.UTF_8);
    }

    private static Map<String, String> fields(String section) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(section);
        while (m.find()) {
            check(fields.put(m.group(1), m.group(2).trim()) == null, "field " + m.group(1) + " appears twice");
        }
        return fields;
    }

    private static Decision parseDecision(String value, String id) {
        try {
            return Decision.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(id + ": unknown decision " + value);
        }
    }

    private static Map<String, Case> cases(BookQueryDataset base) {
        return base.cases().stream()
                .collect(Collectors.toMap(Case::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
