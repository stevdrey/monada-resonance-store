package com.monada.evaluation.book;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Fixed, versioned chunking parameters. The policy is part of every chunk key and is written into the sidecar
 * header, so a change to any value must use a new {@link #id()}.
 *
 * @param id                    stable policy identifier, part of every chunk key
 * @param chapterHeadingPattern regular expression a whole line must match to start a chapter (no implicit trimming)
 * @param maxChunkBytes         upper bound on the UTF-8 size of every emitted chunk, overlap included
 * @param overlapBytes          maximum UTF-8 size of text repeated from the previous chunk of the same section
 */
public record BookChunkingPolicy(String id, String chapterHeadingPattern, int maxChunkBytes, int overlapBytes) {
    /** Smallest allowed bound; it keeps every code point (at most 4 UTF-8 bytes) far below the limit. */
    public static final int MIN_CHUNK_BYTES = 16;

    /**
     * Policy v1 for {@code book-corpus-v1}: chapters start at lines that are exactly {@code CHAPTER <roman>.} (the
     * indented contents list does not match), chunks are at most 2048 UTF-8 bytes, no overlap.
     */
    public static final BookChunkingPolicy V1 = new BookChunkingPolicy("book-chunking-v1", "CHAPTER [IVXLC]+\\.",
            2048, 0);

    public BookChunkingPolicy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chapterHeadingPattern, "chapterHeadingPattern");
        if (!id.matches("[a-z0-9][a-z0-9.-]*")) {
            throw new IllegalArgumentException("policy id must match [a-z0-9][a-z0-9.-]*: " + id);
        }
        try {
            Pattern.compile(chapterHeadingPattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid chapter heading pattern: " + e.getMessage(), e);
        }
        if (maxChunkBytes < MIN_CHUNK_BYTES) {
            throw new IllegalArgumentException("maxChunkBytes must be >= " + MIN_CHUNK_BYTES + ": " + maxChunkBytes);
        }
        if (overlapBytes < 0 || overlapBytes >= maxChunkBytes / 2) {
            throw new IllegalArgumentException("overlapBytes must be in [0, maxChunkBytes / 2): " + overlapBytes);
        }
    }

    Pattern headingPattern() {
        return Pattern.compile(chapterHeadingPattern);
    }

    /** UTF-8 budget of the non-overlapping part of a chunk, so that overlap never pushes a chunk past the bound. */
    int coreLimitBytes() {
        return maxChunkBytes - overlapBytes;
    }
}
