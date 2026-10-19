package com.monada.evaluation.book;

import com.monada.evaluation.book.BookChunk.SectionKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic, chapter-aware chunker over a {@link VerifiedBookSource}. Pure function of the source text and the
 * {@link BookChunkingPolicy}; no clock, randomness, locale, hash-order or network dependency.
 *
 * <ol>
 *   <li>Sections: every line that wholly matches the chapter heading pattern starts a chapter that runs to the next
 *   heading. Text before the first heading is section 0, front matter (emitted only if it has non-whitespace text).
 *   A chapter is a structural boundary only; nothing is inferred from its content.</li>
 *   <li>Paragraphs: maximal runs of non-blank lines inside a section, with leading and trailing whitespace excluded.</li>
 *   <li>Packing: whole paragraphs are packed greedily while the span from the first paragraph start to the last
 *   paragraph end fits the core budget. Chunks never cross sections.</li>
 *   <li>Oversized paragraphs are split at the last whitespace within the budget; if there is none, at the last code
 *   point boundary that is not followed by a combining mark (never inside a surrogate pair). A combining sequence
 *   longer than the budget is rejected as invalid input.</li>
 *   <li>Overlap (when the policy has any): a chunk after the first of its section starts earlier, at the earliest
 *   word start within {@code overlapBytes} that is not before the previous chunk's core start (the previous core
 *   start itself is eligible, so a short previous core can be repeated whole).</li>
 * </ol>
 *
 * <p>Every chunk text is exactly a substring of the body that starts and ends with non-whitespace. After chunking,
 * an invariant check proves every non-whitespace code point of the body lies inside at least one chunk.
 */
public final class BookChunker {
    private BookChunker() {
    }

    /** Chunking outcome: the verified source, the policy, the section structure and the chunks in source order. */
    public record Result(VerifiedBookSource source, BookChunkingPolicy policy, boolean frontMatter, int chapterCount,
                         List<BookChunk> chunks) {
        public Result {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(policy, "policy");
            chunks = List.copyOf(chunks);
        }
    }

    private record Span(int start, int end) {
    }

    private record Section(int ordinal, SectionKind kind, String heading, int start, int end) {
    }

    /** Whitespace as used throughout chunking: Java whitespace or a Unicode space separator (e.g. NBSP). */
    static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /**
     * @throws BookInputException if a combining character sequence alone exceeds the chunk budget, so it cannot be
     *                            split without detaching a combining mark
     */
    public static Result chunk(VerifiedBookSource source, BookChunkingPolicy policy) throws BookInputException {
        return new Chunking(source, policy).run();
    }

    /** Per-run state: offset tables of the body and the chunks emitted so far. */
    private static final class Chunking {
        private final VerifiedBookSource source;
        private final BookChunkingPolicy policy;
        private final String text;
        /**
         * UTF-8 byte offset of each char index. A surrogate pair's 4 bytes are counted at its low surrogate, so the
         * index between the two surrogates has the same offset as the pair start.
         */
        private final long[] byteAt;
        /** Code point offset of each char index (a surrogate pair counts at its low surrogate). */
        private final long[] codePointAt;
        /** Char index at which each line starts, ascending. */
        private final int[] lineStarts;
        private final List<BookChunk> chunks = new ArrayList<>();
        private final Map<String, String> firstKeyBySha = new HashMap<>();

        Chunking(VerifiedBookSource source, BookChunkingPolicy policy) {
            this.source = Objects.requireNonNull(source, "source");
            this.policy = Objects.requireNonNull(policy, "policy");
            this.text = source.text();
            int n = text.length();
            byteAt = new long[n + 1];
            codePointAt = new long[n + 1];
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < n; i++) {
                char c = text.charAt(i);
                long bytes;
                long codePoints = 1;
                if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text.charAt(i + 1))) {
                    bytes = 0;
                    codePoints = 0;
                } else if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(text.charAt(i - 1))) {
                    bytes = 4;
                } else if (c < 0x80) {
                    bytes = 1;
                } else if (c < 0x800) {
                    bytes = 2;
                } else {
                    bytes = 3;
                }
                byteAt[i + 1] = byteAt[i] + bytes;
                codePointAt[i + 1] = codePointAt[i] + codePoints;
                if (c == '\n' && i + 1 < n) {
                    starts.add(i + 1);
                }
            }
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
            if (byteAt[n] != source.sizeBytes()) {
                throw new IllegalStateException("UTF-8 size mismatch: " + byteAt[n] + " != " + source.sizeBytes());
            }
        }

        Result run() throws BookInputException {
            List<Section> sections = sections();
            for (Section section : sections) {
                chunkSection(section);
            }
            boolean frontMatter = !sections.isEmpty() && sections.getFirst().kind() == SectionKind.FRONT_MATTER;
            int chapters = (int) sections.stream().filter(s -> s.kind() == SectionKind.CHAPTER).count();
            verify(sections);
            return new Result(source, policy, frontMatter, chapters, chunks);
        }

        private List<Section> sections() {
            Pattern heading = policy.headingPattern();
            List<Integer> headingLines = new ArrayList<>();
            for (int line = 0; line < lineStarts.length; line++) {
                if (heading.matcher(lineText(line)).matches()) {
                    headingLines.add(line);
                }
            }
            List<Section> sections = new ArrayList<>();
            int firstHeadingStart = headingLines.isEmpty() ? text.length() : lineStarts[headingLines.getFirst()];
            if (trim(0, firstHeadingStart) != null) {
                sections.add(new Section(0, SectionKind.FRONT_MATTER, null, 0, firstHeadingStart));
            }
            for (int k = 0; k < headingLines.size(); k++) {
                int line = headingLines.get(k);
                int end = k + 1 < headingLines.size() ? lineStarts[headingLines.get(k + 1)] : text.length();
                sections.add(new Section(k + 1, SectionKind.CHAPTER, lineText(line), lineStarts[line], end));
            }
            return sections;
        }

        private String lineText(int line) {
            int start = lineStarts[line];
            int end = text.indexOf('\n', start);
            return text.substring(start, end < 0 ? text.length() : end);
        }

        private void chunkSection(Section section) throws BookInputException {
            int limit = policy.coreLimitBytes();
            List<Span> cores = new ArrayList<>();
            Span current = null;
            for (Span paragraph : paragraphs(section)) {
                if (bytes(paragraph.start(), paragraph.end()) > limit) {
                    if (current != null) {
                        cores.add(current);
                        current = null;
                    }
                    cores.addAll(split(paragraph, limit));
                } else if (current == null) {
                    current = paragraph;
                } else if (bytes(current.start(), paragraph.end()) <= limit) {
                    current = new Span(current.start(), paragraph.end());
                } else {
                    cores.add(current);
                    current = paragraph;
                }
            }
            if (current != null) {
                cores.add(current);
            }
            for (int i = 0; i < cores.size(); i++) {
                Span core = cores.get(i);
                int start = i == 0 ? core.start() : overlapStart(cores.get(i - 1).start(), core.start());
                emit(section, i, new Span(start, core.end()));
            }
        }

        private List<Span> paragraphs(Section section) {
            List<Span> paragraphs = new ArrayList<>();
            int runStart = -1;
            int pos = section.start();
            while (pos < section.end()) {
                int newline = text.indexOf('\n', pos);
                int lineEnd = newline < 0 || newline > section.end() ? section.end() : newline;
                boolean blank = trim(pos, lineEnd) == null;
                if (!blank && runStart < 0) {
                    runStart = pos;
                } else if (blank && runStart >= 0) {
                    paragraphs.add(trim(runStart, pos));
                    runStart = -1;
                }
                pos = lineEnd + 1;
            }
            if (runStart >= 0) {
                paragraphs.add(trim(runStart, section.end()));
            }
            return paragraphs;
        }

        /** {@code [start, end)} without leading and trailing whitespace, or {@code null} if nothing is left. */
        private Span trim(int start, int end) {
            int s = start;
            while (s < end && isSpace(text.charAt(s))) {
                s++;
            }
            int e = end;
            while (e > s && isSpace(text.charAt(e - 1))) {
                e--;
            }
            return s < e ? new Span(s, e) : null;
        }

        private List<Span> split(Span paragraph, int limit) throws BookInputException {
            List<Span> pieces = new ArrayList<>();
            int pos = paragraph.start();
            while (pos < paragraph.end()) {
                if (bytes(pos, paragraph.end()) <= limit) {
                    pieces.add(new Span(pos, paragraph.end()));
                    break;
                }
                int fit = lastFittingIndex(pos, paragraph.end(), limit);
                int cut = -1;
                for (int s = fit; s > pos; s--) {
                    if (isSpace(text.charAt(s))) {
                        cut = s;
                        break;
                    }
                }
                if (cut < 0) {
                    cut = hardCut(pos, fit);
                }
                Span piece = trim(pos, cut);
                if (piece == null) {
                    throw new IllegalStateException("empty piece while splitting at char " + pos);
                }
                pieces.add(piece);
                pos = cut;
                while (pos < paragraph.end() && isSpace(text.charAt(pos))) {
                    pos++;
                }
            }
            return pieces;
        }

        /** Largest char index {@code i} in {@code [from, to]} with {@code bytes(from, i) <= limit}. */
        private int lastFittingIndex(int from, int to, int limit) {
            int lo = from;
            int hi = to;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (bytes(from, mid) <= limit) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }

        /**
         * A cut at or before {@code fit}: never inside a surrogate pair and never before a combining mark. A base
         * character whose combining sequence alone exceeds the budget cannot be split that way, so the input is
         * rejected rather than emitting a chunk that starts with a detached mark.
         */
        private int hardCut(int from, int fit) throws BookInputException {
            int cut = boundaryAtOrBefore(fit);
            int preferred = cut;
            while (preferred > from && isCombiningMark(text.codePointAt(preferred))) {
                preferred = boundaryAtOrBefore(preferred - 1);
            }
            if (preferred > from) {
                return preferred;
            }
            throw new BookInputException(BookInputException.Reason.INVALID_INPUT, "combining character sequence at"
                    + " byte offset " + byteAt[from] + " is longer than the chunk budget of " + policy.coreLimitBytes()
                    + " bytes and cannot be split without detaching a combining mark");
        }

        private int boundaryAtOrBefore(int index) {
            if (index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index))
                    && Character.isHighSurrogate(text.charAt(index - 1))) {
                return index - 1;
            }
            return index;
        }

        private int overlapStart(int previousCoreStart, int coreStart) {
            if (policy.overlapBytes() == 0) {
                return coreStart;
            }
            // The previous core start is itself a word start (cores begin with non-whitespace), so a short previous
            // core that fits the budget is repeated whole.
            for (int s = previousCoreStart; s < coreStart; s++) {
                boolean wordStart = s == previousCoreStart
                        || (!isSpace(text.charAt(s)) && isSpace(text.charAt(s - 1)));
                if (wordStart && bytes(s, coreStart) <= policy.overlapBytes()) {
                    return s;
                }
            }
            return coreStart;
        }

        private long bytes(int start, int end) {
            return byteAt[end] - byteAt[start];
        }

        private void emit(Section section, int chunkInSection, Span span) {
            String chunkText = text.substring(span.start(), span.end());
            String sha = VerifiedBookSource.sha256(chunkText.getBytes(StandardCharsets.UTF_8));
            String key = String.format(Locale.ROOT, "%s/%s/s%03d/c%04d", source.corpus(), policy.id(),
                    section.ordinal(), chunkInSection);
            String duplicateOf = firstKeyBySha.putIfAbsent(sha, key);
            chunks.add(new BookChunk(key, chunks.size(), section.ordinal(), section.kind(), section.heading(),
                    chunkInSection, byteAt[span.start()], byteAt[span.end()], codePointAt[span.start()],
                    codePointAt[span.end()], lineOf(span.start()), lineOf(span.end() - 1), sha, duplicateOf,
                    chunkText));
        }

        private int lineOf(int index) {
            int found = Arrays.binarySearch(lineStarts, index);
            return (found >= 0 ? found : -found - 2) + 1;
        }

        /** Bugs, not input errors: every invariant the sidecar promises is re-checked against the body. */
        private void verify(List<Section> sections) {
            boolean[] covered = new boolean[text.length()];
            long previousStart = -1;
            long previousEnd = -1;
            for (BookChunk chunk : chunks) {
                int start = indexOfByte(chunk.byteStart());
                int end = indexOfByte(chunk.byteEnd());
                Section section = sections.stream().filter(s -> s.ordinal() == chunk.sectionOrdinal()).findFirst()
                        .orElseThrow();
                if (start < section.start() || end > section.end() || start >= end) {
                    throw new IllegalStateException("chunk " + chunk.key() + " escapes its section");
                }
                // Overlap may repeat a whole previous chunk (equal starts), but every chunk must end further on.
                if (chunk.byteStart() < previousStart || chunk.byteEnd() <= previousEnd) {
                    throw new IllegalStateException("chunk " + chunk.key() + " is out of source order");
                }
                if (chunk.utf8Bytes() > policy.maxChunkBytes()) {
                    throw new IllegalStateException("chunk " + chunk.key() + " exceeds maxChunkBytes");
                }
                if (isSpace(text.codePointAt(start)) || isSpace(text.codePointBefore(end))) {
                    throw new IllegalStateException("chunk " + chunk.key() + " has untrimmed whitespace");
                }
                previousStart = chunk.byteStart();
                previousEnd = chunk.byteEnd();
                Arrays.fill(covered, start, end, true);
            }
            for (int i = 0; i < text.length(); i++) {
                if (!covered[i] && !isSpace(text.charAt(i))) {
                    throw new IllegalStateException("non-whitespace char at index " + i + " is in no chunk");
                }
            }
        }

        private int indexOfByte(long byteOffset) {
            int found = Arrays.binarySearch(byteAt, byteOffset);
            if (found < 0) {
                throw new IllegalStateException("byte offset " + byteOffset + " is not a char boundary");
            }
            // Equal offsets occur only around a high surrogate; the boundary is the first index with that offset.
            while (found > 0 && byteAt[found - 1] == byteOffset) {
                found--;
            }
            return found;
        }
    }
}
