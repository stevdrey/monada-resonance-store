package com.monada.evaluation.book;

import com.monada.core.KnowledgeAtom;
import com.monada.storage.JsonStrings;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one explicit new note of the book feedback experiment's Phase C (Issue #170): a short, verified fact that the
 * frozen corpus does not contain, written in original wording for this experiment (no book text). The host adds it
 * with {@code MonadaMemory.remember}; the Store never decides on its own that it is missing or true.
 *
 * <p>The Store keeps no atom metadata (ADR 0004), so the note's provenance lives in an external sidecar line keyed by
 * its content-derived atom id, like the chunk sidecar. Lifecycle limits are stated, not solved: adding the note
 * replaces, deletes or versions nothing, and every original chunk stays queryable.
 */
final class BookFeedbackNote {
    static final String FORMAT = "monada-book-note-sidecar";
    static final int FORMAT_VERSION = 1;
    static final String NOTE_ID = "book-note-v1/authorship";
    static final String TEXT = "Lewis Carroll was the pen name of Charles Lutwidge Dodgson, the Oxford mathematician"
            + " who wrote Alice's Adventures in Wonderland, first published in 1865.";
    static final String SOURCE = "https://www.britannica.com/biography/Lewis-Carroll";
    static final String WORDING = "ORIGINAL_WORDING_NO_BOOK_TEXT";
    /** {@code VERIFIED} once a human checked the statement against {@link #SOURCE}; until then the note is pending. */
    static final String VERIFICATION_STATUS = "VERIFIED";
    static final String VERIFIED_BY = "Steven Rey Sequeira";
    static final String VERIFIED_AT = "2026-10-23";
    static final String LIFECYCLE = "APPEND_ONLY: no replacement, deletion or version resolution; original chunks"
            + " remain queryable";
    /** Predeclared probes whose answer is the note; the note is not required to rank first. */
    static final List<String> PROBES = List.of(
            "What was the real name of the writer known as Lewis Carroll?",
            "In which year was Alice's Adventures in Wonderland first published?");

    private BookFeedbackNote() {
    }

    static String atomId() {
        return KnowledgeAtom.text(TEXT).id();
    }

    /**
     * The note must be new: its atom id is no chunk's and no chunk contains its text (so remembering it adds exactly
     * one atom and cannot be mistaken for existing content).
     */
    static void preflight(BookChunkFiles.Loaded chunks, Set<String> chunkAtoms) throws BookInputException {
        if (chunkAtoms.contains(atomId())) {
            throw new BookInputException(BookInputException.Reason.INVALID_INPUT,
                    "the note " + NOTE_ID + " is already a chunk of the corpus");
        }
        for (BookChunkFiles.Entry entry : chunks.entries()) {
            if (entry.text().contains(TEXT)) {
                throw new BookInputException(BookInputException.Reason.INVALID_INPUT,
                        "the note " + NOTE_ID + " occurs in chunk " + entry.key());
            }
        }
    }

    /** The provenance sidecar line for the note's atom (no paths, no timestamps). */
    static String sidecarLine(String atomId) {
        Map<String, String> fields = fields(atomId);
        StringBuilder line = new StringBuilder("{");
        fields.forEach((k, v) -> line.append(line.length() > 1 ? "," : "").append('"').append(k).append("\":\"")
                .append(JsonStrings.escape(v)).append('"'));
        return line.append("}\n").toString();
    }

    /** Parses a sidecar line back; it must carry exactly the fields {@link #sidecarLine} writes for {@code atomId}. */
    static boolean readsBack(String line, String atomId) {
        Map<String, String> parsed = JsonStrings.parseFlat(line.strip(), "note sidecar");
        return parsed.equals(fields(atomId));
    }

    private static Map<String, String> fields(String atomId) {
        byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("format", FORMAT);
        f.put("formatVersion", String.valueOf(FORMAT_VERSION));
        f.put("noteId", NOTE_ID);
        f.put("atomId", atomId);
        f.put("sha256", VerifiedBookSource.sha256(bytes));
        f.put("utf8Bytes", String.valueOf(bytes.length));
        f.put("source", SOURCE);
        f.put("wording", WORDING);
        f.put("verificationStatus", VERIFICATION_STATUS);
        f.put("verifiedBy", VERIFIED_BY);
        f.put("verifiedAt", VERIFIED_AT);
        f.put("lifecycle", LIFECYCLE);
        return f;
    }
}
