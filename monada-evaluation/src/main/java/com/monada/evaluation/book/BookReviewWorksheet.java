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
import java.util.Locale;
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
    /** Consecutive (normalized) chunk words a note may not reproduce; the committed notes share at most 4. */
    static final int COPIED_WORDS = 6;
    /** The editable tail of an entry: the closing fence, a blank line and the four field lines, values captured. */
    private static final Pattern TAIL = Pattern.compile(
            "\\n~~~\\n\\ndecision:([^\\n]*)\\ngrade:([^\\n]*)\\nquote:([^\\n]*)\\nnote:([^\\n]*)\\n*\\z");
    /** Raw field values (text after each {@code name:}) of an unfilled entry. */
    private static final List<String> BLANK = List.of(" ", " ", " ", " ");

    private BookReviewWorksheet() {
    }

    static String render(byte[] body, BookQueryDataset base, List<Pair> selection) {
        return render(body, base, selection, false, Map.of());
    }

    /**
     * The worksheet with {@code values} (per pair id: the raw text after {@code decision:}, {@code grade:},
     * {@code quote:} and {@code note:}) filled in, in the current or the {@code legacy} layout. A filled sheet is valid
     * only if it equals this rendering of its own values.
     */
    private static String render(byte[] body, BookQueryDataset base, List<Pair> selection, boolean legacy,
                                 Map<String, List<String>> values) {
        Map<String, Case> cases = cases(base);
        StringBuilder out = new StringBuilder(preamble(legacy));
        List<Pair> order = selection.stream()
                .sorted(Comparator.comparing(p -> BookQueryDataset.sha256(
                        (BookUnjudgedSelection.SEED + "worksheet|" + p.pairId()).getBytes(StandardCharsets.UTF_8))))
                .toList();
        for (Pair p : order) {
            List<String> v = values.getOrDefault(p.pairId(), BLANK);
            out.append(context(p, cases.get(p.queryId()), base.chunkMap().get(p.chunkKey()), body, legacy))
                    .append('\n');
            out.append("decision:").append(v.get(0)).append("\ngrade:").append(v.get(1)).append("\nquote:")
                    .append(v.get(2)).append("\nnote:").append(v.get(3)).append("\n\n");
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
        if (headedBy(worksheet, preamble(false))) {
            return BookQueryDatasetV2.REVIEW_PROTOCOL;
        }
        check(headedBy(worksheet, preamble(true)), "worksheet title, protocol line or instructions were edited");
        return BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL;
    }

    /** The whole head up to the first entry must be {@code preamble}: text appended after it is rejected too. */
    private static boolean headedBy(String worksheet, String preamble) {
        return worksheet.startsWith(preamble) && worksheet.startsWith("## ", preamble.length());
    }

    /** The {@code review.psv} bytes for every decided entry of {@code worksheet}, sorted by pair id. */
    static String apply(String worksheet, byte[] body, BookQueryDataset base, List<Pair> selection) {
        Map<String, Pair> pairs = selection.stream()
                .collect(Collectors.toMap(Pair::pairId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        // The entry layout must match the protocol the preamble declares: a legacy entry (chunk key and byte range) in a
        // current-protocol sheet would claim the stricter blinding while exposing those identifiers.
        boolean legacy = BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL.equals(protocolOf(worksheet));
        Map<String, String> rows = new TreeMap<>();
        // The set of entries first: unknown, repeated or missing headings (a truncated sheet, a mistyped heading) are
        // reported as such before any entry is read, since apply rewrites review.psv rather than merging it.
        Set<String> seen = new HashSet<>();
        Matcher heading = ENTRY.matcher(worksheet);
        while (heading.find()) {
            String id = heading.group(1);
            check(pairs.containsKey(id), id + ": worksheet entry is not in selection.psv");
            check(seen.add(id), id + ": worksheet has this entry twice");
        }
        List<String> missing = pairs.keySet().stream().filter(id -> !seen.contains(id)).toList();
        check(missing.isEmpty(), "worksheet is missing " + missing.size() + " selected entries: " + missing);
        // Each entry ends in its four field lines; their raw values are all a reviewer may change.
        Map<String, List<String>> values = new LinkedHashMap<>();
        Matcher entry = ENTRY.matcher(worksheet);
        int start = entry.find() ? entry.start() : -1;
        while (start >= 0) {
            String id = entry.group(1);
            int next = entry.find() ? entry.start() : -1;
            Matcher tail = TAIL.matcher(worksheet.substring(start, next < 0 ? worksheet.length() : next));
            check(tail.find(), id + ": worksheet entry must end with its decision, grade, quote and note lines");
            values.put(id, List.of(tail.group(1), tail.group(2), tail.group(3), tail.group(4)));
            start = next;
        }
        // One invariant instead of piecemeal checks: the sheet must be exactly the rendering of its own values, so any
        // edit to the preamble, a query, the answerability, the chunk text, the order or the layout is rejected.
        requireRendering(worksheet, render(body, base, selection, legacy, values));

        for (Map.Entry<String, List<String>> e : values.entrySet()) {
            String id = e.getKey();
            Pair pair = pairs.get(id);
            ChunkRef chunk = base.chunkMap().get(pair.chunkKey());
            List<String> v = e.getValue();
            String decision = v.get(0).trim();
            if (decision.isEmpty()) {
                continue;
            }
            String note = v.get(3).trim();
            check(!note.isBlank() && !note.contains("|"), id + ": note must be non-blank and contain no |");
            // review.psv is committed and must hold no book text: a note may name things, not copy a passage.
            check(!copiesChunkText(note, chunkText(body, chunk)), id + ": note reproduces " + COPIED_WORDS
                    + " or more consecutive words of the chunk; paraphrase it, the quote stays local");
            String row;
            Decision d = parseDecision(decision, id);
            if (d == Decision.UNRESOLVED) {
                check(v.get(1).isBlank() && v.get(2).isBlank(),
                        id + ": UNRESOLVED takes no grade and no quote");
                row = String.join("|", id, pair.queryId(), pair.chunkKey(), d.name(), "-", "-", "-", "-", note);
            } else {
                String grade = v.get(1).trim();
                long[] span = locate(body, chunk, v.get(2).trim(), id);
                byte[] bytes = Arrays.copyOfRange(body, (int) span[0], (int) span[1]);
                row = String.join("|", id, pair.queryId(), pair.chunkKey(), d.name(), grade, String.valueOf(span[0]),
                        String.valueOf(span[1]), BookQueryDataset.sha256(bytes), note);
            }
            rows.put(id, row);
        }
        StringBuilder out = new StringBuilder(BookQueryDatasetV2.REVIEW_HEADER).append('\n');
        rows.values().forEach(r -> out.append(r).append('\n'));
        return out.toString();
    }

    /**
     * Whether {@code note} shares a run of {@value #COPIED_WORDS} or more words with {@code text}. Words are compared
     * normalized (lowercase, letters and digits only), so a copy with changed case or punctuation still counts.
     */
    static boolean copiesChunkText(String note, String text) {
        List<String> chunkWords = normalizedWords(text);
        Set<List<String>> runs = new HashSet<>();
        for (int i = 0; i + COPIED_WORDS <= chunkWords.size(); i++) {
            runs.add(chunkWords.subList(i, i + COPIED_WORDS));
        }
        List<String> noteWords = normalizedWords(note);
        for (int i = 0; i + COPIED_WORDS <= noteWords.size(); i++) {
            if (runs.contains(noteWords.subList(i, i + COPIED_WORDS))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizedWords(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(w -> w.codePoints().filter(Character::isLetterOrDigit)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString())
                .filter(w -> !w.isEmpty()).toList();
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

    /** Equality up to trailing newlines; otherwise names the first differing line (1-based). */
    private static void requireRendering(String worksheet, String rendered) {
        String actual = worksheet.stripTrailing();
        String expected = rendered.stripTrailing();
        if (actual.equals(expected)) {
            return;
        }
        String[] a = actual.split("\n", -1);
        String[] b = expected.split("\n", -1);
        int line = 0;
        while (line < Math.min(a.length, b.length) && a[line].equals(b[line])) {
            line++;
        }
        throw new IllegalStateException("worksheet differs from its rendering at line " + (line + 1)
                + "; only the decision, grade, quote and note values may be edited");
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
