package com.monada.evaluation.book;

import java.util.Objects;

/**
 * One emitted chunk and its provenance. {@link #text()} is exactly the source substring
 * {@code [codePointStart, codePointEnd)} (equivalently UTF-8 bytes {@code [byteStart, byteEnd)}) of the verified
 * normalized body. The {@link #key()} is positional, so repeated text gets distinct keys even though its
 * content-derived Store atom ID is the same.
 *
 * @param key            {@code <corpus>/<policy>/s<section:03>/c<chunkInSection:04>}
 * @param ordinal        zero-based position among all chunks
 * @param sectionOrdinal 0 for front matter, {@code k} for the k-th chapter heading
 * @param sectionKind    front matter or chapter
 * @param sectionHeading the chapter heading line, or {@code null} for front matter
 * @param chunkInSection zero-based position inside the section
 * @param byteStart      inclusive UTF-8 byte offset in the normalized body
 * @param byteEnd        exclusive UTF-8 byte offset
 * @param codePointStart inclusive code point offset
 * @param codePointEnd   exclusive code point offset
 * @param lineStart      1-based line of the first character
 * @param lineEnd        1-based line of the last character
 * @param sha256         lowercase hex SHA-256 of the UTF-8 text
 * @param duplicateOf    key of the first earlier chunk with identical text, or {@code null}
 * @param text           the chunk text
 */
public record BookChunk(String key, int ordinal, int sectionOrdinal, SectionKind sectionKind, String sectionHeading,
                        int chunkInSection, long byteStart, long byteEnd, long codePointStart, long codePointEnd,
                        int lineStart, int lineEnd, String sha256, String duplicateOf, String text) {
    /** Structural section kinds; a chapter is a heading boundary, not an inferred topic. */
    public enum SectionKind {
        FRONT_MATTER,
        CHAPTER
    }

    public BookChunk {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sectionKind, "sectionKind");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("empty chunk " + key);
        }
    }

    public long utf8Bytes() {
        return byteEnd - byteStart;
    }
}
