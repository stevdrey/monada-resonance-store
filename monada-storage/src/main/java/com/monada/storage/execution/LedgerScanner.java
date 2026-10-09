package com.monada.storage.execution;

import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Streams one scope segment line by line, validating framing, digest, sequence, schema, scope, canonical
 * encoding and the ordering/reference rules. It never modifies anything and never repairs: a record is
 * accepted only while every earlier line was valid, so {@link Result#records()} is always the longest
 * valid prefix. Later lines are still examined for framing, digest, sequence and schema problems so the
 * diagnostics are as complete as possible.
 */
final class LedgerScanner {
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    record Result(List<LedgerRecord> records, List<LedgerDiagnostic> diagnostics, LedgerState state,
                  boolean tornTail) {
        boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == LedgerDiagnostic.Severity.ERROR);
        }

        static Result empty() {
            return new Result(List.of(), List.of(), new LedgerState(), false);
        }
    }

    private LedgerScanner() {
    }

    /** Copies as much of {@code source[from, from+length)} as still fits into {@code line}; returns the new size. */
    private static int append(byte[] line, int stored, byte[] source, int from, int length) {
        int room = Math.min(length, line.length - stored);
        if (room > 0) {
            System.arraycopy(source, from, line, stored, room);
        }
        return stored + Math.max(room, 0);
    }

    /** {@code scope} may be null when the scope id is unknown: the per-record scope check is then skipped. */
    static Result scan(Path segment, String displayName, ScopeId scope) throws IOException {
        Scan scan = new Scan(displayName, scope);
        try (InputStream in = Files.newInputStream(segment)) {
            byte[] chunk = new byte[1 << 16];
            // Only the first MAX_LINE_BYTES - 1 bytes of a line are kept; the rest is counted, not stored.
            byte[] line = new byte[RecordLine.MAX_LINE_BYTES - 1];
            int stored = 0;
            long contentBytes = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                int start = 0;
                for (int i = 0; i < read; i++) {
                    if (chunk[i] != '\n') {
                        continue;
                    }
                    int segmentLength = i - start;
                    stored = append(line, stored, chunk, start, segmentLength);
                    contentBytes += segmentLength;
                    scan.line(Arrays.copyOf(line, stored), contentBytes + 1 > RecordLine.MAX_LINE_BYTES);
                    stored = 0;
                    contentBytes = 0;
                    start = i + 1;
                }
                int tail = read - start;
                stored = append(line, stored, chunk, start, tail);
                contentBytes += tail;
            }
            if (contentBytes > 0) {
                if (contentBytes + 1 > RecordLine.MAX_LINE_BYTES) {
                    scan.error(LedgerDiagnosticCategory.OVERSIZED_RECORD, OptionalLong.empty(),
                            "final record already exceeds " + RecordLine.MAX_LINE_BYTES + " bytes");
                }
                scan.tornTail(Arrays.copyOf(line, stored));
            }
        }
        Collections.sort(scan.diagnostics);
        return new Result(List.copyOf(scan.records), List.copyOf(scan.diagnostics), scan.state, scan.torn);
    }

    private static final class Scan {
        final String file;
        final ScopeId scope;
        final List<LedgerRecord> records = new ArrayList<>();
        final List<LedgerDiagnostic> diagnostics = new ArrayList<>();
        final LedgerState state = new LedgerState();
        long lineNo = 1;
        long expected = 1;
        boolean expectedKnown = true;
        boolean broken;
        boolean torn;

        Scan(String file, ScopeId scope) {
            this.file = file;
            this.scope = scope;
        }

        void error(LedgerDiagnosticCategory category, OptionalLong sequence, String message) {
            diagnostics.add(LedgerDiagnostic.error(category, file, lineNo, sequence, message));
            broken = true;
        }

        void tornTail(byte[] prefix) {
            torn = true;
            diagnostics.add(LedgerDiagnostic.warning(LedgerDiagnosticCategory.TORN_TAIL, file, lineNo,
                    tornSequence(prefix), "incomplete final record (no terminating line feed); ignored, not repaired"));
        }

        void line(byte[] content, boolean oversized) {
            try {
                process(content, oversized);
            } finally {
                lineNo++;
            }
        }

        private void process(byte[] content, boolean oversized) {
            if (oversized) {
                expectedKnown = false;
                error(LedgerDiagnosticCategory.OVERSIZED_RECORD, OptionalLong.empty(),
                        "record exceeds " + RecordLine.MAX_LINE_BYTES + " bytes");
                return;
            }
            List<byte[]> fields = splitTabs(content);
            if (fields.size() != 5) {
                expectedKnown = false;
                error(LedgerDiagnosticCategory.MALFORMED_RECORD, OptionalLong.empty(),
                        "expected 5 tab-separated fields, found " + fields.size());
                return;
            }
            if (!RecordLine.MAGIC.equals(ascii(fields.get(0)))) {
                expectedKnown = false;
                error(LedgerDiagnosticCategory.MALFORMED_RECORD, OptionalLong.empty(), "unknown record codec marker");
                return;
            }
            OptionalLong sequence = canonicalLong(ascii(fields.get(1)));
            if (sequence.isEmpty() || sequence.getAsLong() < 1) {
                // No usable sequence, but the rest of the line is still examined (it is independent evidence).
                expectedKnown = false;
                error(LedgerDiagnosticCategory.MALFORMED_RECORD, OptionalLong.empty(), "invalid sequence field");
                sequence = OptionalLong.empty();
            } else {
                // A sequence error ends the valid prefix (broken) but the line is still examined: framing,
                // digest, encoding and schema defects are independent diagnostics.
                checkSequence(sequence.getAsLong());
            }
            byte[] payload = fields.get(4);
            OptionalLong length = canonicalLong(ascii(fields.get(2)));
            if (length.isEmpty() || length.getAsLong() != payload.length) {
                error(LedgerDiagnosticCategory.MALFORMED_RECORD, sequence,
                        "payload length field does not match the payload (" + payload.length + " bytes present)");
            }
            String digest = ascii(fields.get(3));
            if (!HEX64.matcher(digest).matches() || !digest.equals(RecordLine.sha256Hex(payload))) {
                error(LedgerDiagnosticCategory.DIGEST_MISMATCH, sequence, "payload SHA-256 does not match the record");
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
            } catch (CharacterCodingException e) {
                error(LedgerDiagnosticCategory.MALFORMED_RECORD, sequence, "payload is not valid UTF-8");
                return;
            }
            ExecutionEvent event;
            try {
                event = EventPayloadCodec.decode(text);
            } catch (EventPayloadCodec.PayloadException e) {
                error(e.category(), sequence, e.getMessage());
                return;
            }
            if (scope != null && !event.scopeId().equals(scope)) {
                error(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, sequence,
                        "record belongs to scope '" + event.scopeId() + "', not '" + scope + "'");
            }
            if (!Arrays.equals(EventPayloadCodec.encode(event), payload)) {
                error(LedgerDiagnosticCategory.MALFORMED_RECORD, sequence, "payload is not in canonical form");
                return;
            }
            if (broken) {
                return; // later lines are only checked structurally once the valid prefix has ended
            }
            var violation = state.check(event);
            if (violation.isPresent()) {
                error(violation.get().category(), sequence, violation.get().message());
                return;
            }
            state.apply(sequence.getAsLong(), event, payload);
            records.add(new LedgerRecord(sequence.getAsLong(), event));
        }

        /** Reports a duplicate or gapped sequence and re-synchronises the expected next sequence. */
        private void checkSequence(long seq) {
            if (expectedKnown && seq < expected) {
                error(LedgerDiagnosticCategory.DUPLICATE_SEQUENCE, OptionalLong.of(seq),
                        "sequence " + seq + " repeats or goes backwards (expected " + expected + ")");
                return;
            }
            if (expectedKnown && seq > expected) {
                error(LedgerDiagnosticCategory.SEQUENCE_GAP, OptionalLong.of(seq),
                        "sequence jumps from " + (expected - 1) + " to " + seq);
                expected = seq + 1;
                return;
            }
            expected = seq + 1;
            expectedKnown = true;
        }
    }

    /** Sequence of an incomplete record, only when its field is complete (terminated by a TAB) and canonical. */
    private static OptionalLong tornSequence(byte[] prefix) {
        List<byte[]> parts = splitTabs(prefix);
        if (parts.size() < 3 || !RecordLine.MAGIC.equals(ascii(parts.get(0)))) {
            return OptionalLong.empty();
        }
        OptionalLong sequence = canonicalLong(ascii(parts.get(1)));
        return sequence.isPresent() && sequence.getAsLong() >= 1 ? sequence : OptionalLong.empty();
    }

    private static List<byte[]> splitTabs(byte[] content) {
        List<byte[]> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\t') {
                parts.add(Arrays.copyOfRange(content, start, i));
                start = i + 1;
            }
        }
        parts.add(Arrays.copyOfRange(content, start, content.length));
        return parts;
    }

    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /** Parses a non-negative decimal without sign, leading zeros or spaces. */
    private static OptionalLong canonicalLong(String text) {
        try {
            long value = Long.parseLong(text);
            return Long.toString(value).equals(text) && value >= 0 ? OptionalLong.of(value) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
