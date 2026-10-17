package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The #158 phase 2 ledger scan (direct digest check, reused decoder, instant fast path, cheaper encode) must give,
 * for every input byte sequence, the same accepted records, the same diagnostics (category, line, sequence and
 * message), the same torn-tail flag and the same ledger state as the pre-#158 scan ({@link LegacyLedgerScanner}).
 */
class LedgerScannerEquivalenceTest {
    private static final int MAX = RecordLine.MAX_LINE_BYTES;

    @TempDir
    Path root;

    private int files;

    /** Scans {@code bytes} with both scanners and requires equal results; returns the new one. */
    private LedgerScanner.Result assertSameScan(byte[] bytes, ScopeId scope) throws IOException {
        Path file = root.resolve("events-" + (files++ % 4) + ".log");
        Files.write(file, bytes);
        LedgerScanner.Result legacy = LegacyLedgerScanner.scan(file, "seg", scope);
        LedgerScanner.Result current = LedgerScanner.scan(file, "seg", scope);
        assertEquals(legacy.records(), current.records());
        assertEquals(legacy.diagnostics(), current.diagnostics());
        assertEquals(legacy.tornTail(), current.tornTail());
        assertEquals(legacy.state().lastSequence(), current.state().lastSequence());
        for (LedgerRecord record : legacy.records()) {
            LedgerState.Entry old = legacy.state().find(record.event().envelope().eventId()).orElseThrow();
            LedgerState.Entry now = current.state().find(record.event().envelope().eventId()).orElseThrow();
            assertEquals(old.sequence(), now.sequence());
            assertArrayEquals(old.payload(), now.payload());
        }
        return current;
    }

    private LedgerScanner.Result assertSameScan(byte[] bytes) throws IOException {
        assertSameScan(bytes, null);
        assertSameScan(bytes, ScopeId.of("other-scope"));
        return assertSameScan(bytes, Events.SCOPE);
    }

    private static List<ExecutionEvent> events() {
        List<ExecutionEvent> events = new ArrayList<>(Events.fullRun());
        List<String> summaries = List.of("plain", "a\u007fb\u0085c", "tab\tlf\n|\\", "ünï 😀", " x﻿");
        List<Instant> instants = List.of(Instant.parse("2026-01-01T00:00:00.123456789Z"),
                Instant.parse("0000-01-01T00:00:00Z"), Instant.parse("+10000-01-01T00:00:00.000001Z"),
                Instant.parse("-0001-12-31T23:59:59.100Z"), Instant.parse("9999-12-31T23:59:59Z"));
        for (int i = 0; i < summaries.size(); i++) {
            events.add(ExecutionEvent.executionStarted(EventId.of("s" + i), Events.SCOPE, Events.TASK,
                    ExecutionId.of("x" + i), new SourceProvenance("r", "c", "k"),
                    new EvaluationPolicy("p", "1", Set.of(QualityDimension.TESTS)), summaries.get(i),
                    instants.get(i)));
        }
        return events;
    }

    private static byte[] frame(long sequence, byte[] payload) {
        return RecordLine.frame(sequence, payload);
    }

    private static byte[] ledger(List<byte[]> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        lines.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static List<byte[]> validLines() {
        List<byte[]> lines = new ArrayList<>();
        List<ExecutionEvent> events = events();
        for (int i = 0; i < events.size(); i++) {
            lines.add(frame(i + 1, EventPayloadCodec.encode(events.get(i))));
        }
        return lines;
    }

    private static byte[] withLine(List<byte[]> lines, int index, byte[] line) {
        List<byte[]> changed = new ArrayList<>(lines);
        changed.set(index, line);
        return ledger(changed);
    }

    /** Re-frames a line with a changed payload text, with a correct length and digest. */
    private static byte[] reframed(long sequence, String payload) {
        return frame(sequence, payload.getBytes(StandardCharsets.UTF_8));
    }

    /** A line with the given raw fields (no validation of any of them). */
    private static byte[] rawLine(String magic, String sequence, String length, byte[] digest, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes((magic + "\t" + sequence + "\t" + length + "\t").getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(digest);
        out.write('\t');
        out.writeBytes(payload);
        out.write('\n');
        return out.toByteArray();
    }

    @Test
    void validLedgerAndEmptyFile() throws IOException {
        LedgerScanner.Result result = assertSameScan(ledger(validLines()));
        assertEquals(events().size(), result.records().size());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(assertSameScan(new byte[0]).records().isEmpty());
        assertSameScan(new byte[] {'\n'});
        assertSameScan(new byte[] {'\n', '\n'});
    }

    @Test
    void everyTruncationOfAValidLedger() throws IOException {
        byte[] bytes = ledger(validLines());
        for (int length = 0; length < bytes.length; length++) {
            assertSameScan(Arrays.copyOf(bytes, length), Events.SCOPE);
        }
    }

    @Test
    void digestFieldVariants() throws IOException {
        List<byte[]> lines = validLines();
        byte[] payload = EventPayloadCodec.encode(events().get(2));
        String digest = RecordLine.sha256Hex(payload);
        String length = Integer.toString(payload.length);
        List<String> digests = List.of(digest, digest.toUpperCase(), digest.substring(1), digest + "0",
                digest.substring(0, 63) + "g", digest.substring(0, 63) + " ", " " + digest.substring(1),
                "0".repeat(64), "", digest.substring(0, 62) + "é", digest + digest);
        for (String variant : digests) {
            assertSameScan(withLine(lines, 2, rawLine("MXL1", "3", length, variant.getBytes(StandardCharsets.UTF_8),
                    payload)));
        }
        byte[] latin = digest.getBytes(StandardCharsets.ISO_8859_1);
        latin[10] = (byte) 0xE1;
        assertSameScan(withLine(lines, 2, rawLine("MXL1", "3", length, latin, payload)));
    }

    @Test
    void framingFieldVariants() throws IOException {
        List<byte[]> lines = validLines();
        byte[] payload = EventPayloadCodec.encode(events().get(3));
        byte[] digest = RecordLine.sha256Hex(payload).getBytes(StandardCharsets.US_ASCII);
        String length = Integer.toString(payload.length);
        for (String magic : List.of("MXL1", "MXL2", "mxl1", "", "MXL1 ")) {
            assertSameScan(withLine(lines, 3, rawLine(magic, "4", length, digest, payload)));
        }
        for (String sequence : List.of("4", "04", "3", "5", "0", "-4", "+4", "", "x", "99999999999999999999")) {
            assertSameScan(withLine(lines, 3, rawLine("MXL1", sequence, length, digest, payload)));
        }
        for (String len : List.of(length, "0" + length, Integer.toString(payload.length + 1), "-1", "", "x")) {
            assertSameScan(withLine(lines, 3, rawLine("MXL1", "4", len, digest, payload)));
        }
    }

    @Test
    void payloadVariantsWithCorrectFraming() throws IOException {
        List<byte[]> lines = validLines();
        List<String> payloads = new ArrayList<>();
        CanonicalInstantCodecTest.instantVariants(events().get(2), 9, 10, 17, 18)
                .forEach(v -> payloads.add(v.payload()));
        CanonicalInstantCodecTest.instantVariants(events().get(0), 9, 10).forEach(v -> payloads.add(v.payload()));
        String stage = new String(EventPayloadCodec.encode(events().get(2)), StandardCharsets.UTF_8);
        payloads.addAll(List.of(stage + "|", stage.replace("plan\\pedit", "plan|edit"),
                stage.replace("plan\\pedit", "plan\\x007Cedit"), stage.replace("1|STAGE", "2|STAGE"),
                stage.replace("STAGE_RECORDED", "stage_recorded"), stage.replace("|1200|", "|01200|"),
                stage.replace("scope-1", "scope-2"), "", "1", "|", stage.substring(0, stage.length() / 2)));
        for (String payload : payloads) {
            assertSameScan(withLine(lines, 2, reframed(3, payload)));
        }
        String started = new String(EventPayloadCodec.encode(events().get(0)), StandardCharsets.UTF_8);
        assertSameScan(withLine(lines, 0, reframed(1, started.replace("ünï", "uni"))));
        assertSameScan(withLine(lines, 0, reframed(1, started.replace("ünï", "u\\x0085"))));
    }

    @Test
    void invalidUtf8PayloadsWithCorrectFraming() throws IOException {
        List<byte[]> lines = validLines();
        byte[] payload = EventPayloadCodec.encode(events().get(0));
        List<byte[]> invalid = List.of(new byte[] {(byte) 0xFF}, new byte[] {(byte) 0xC3}, new byte[] {(byte) 0xC0,
                (byte) 0x80}, new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, new byte[] {(byte) 0xF4, (byte) 0x90,
                (byte) 0x80, (byte) 0x80}, new byte[] {(byte) 0xE2, (byte) 0x82}, new byte[] {(byte) 0x80});
        for (byte[] bad : invalid) {
            for (int at : new int[] {0, 10, payload.length / 2, payload.length}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(payload, 0, at);
                out.writeBytes(bad);
                out.write(payload, at, payload.length - at);
                assertSameScan(withLine(lines, 0, frame(1, out.toByteArray())));
            }
        }
    }

    @Test
    void oversizedAndLimitLengthLines() throws IOException {
        List<byte[]> lines = validLines();
        for (int contentBytes : new int[] {MAX - 1, MAX, MAX + 1}) {
            byte[] line = new byte[contentBytes + 1];
            Arrays.fill(line, (byte) 'a');
            line[contentBytes] = '\n';
            assertSameScan(withLine(lines, 4, line));
            assertSameScan(ledger(List.of(lines.get(0), Arrays.copyOf(line, contentBytes))));
        }
    }

    @Test
    void seededByteMutations() throws IOException {
        byte[] bytes = ledger(validLines());
        byte[] interesting = {'\t', '\n', '|', '\\', '~', '0', '9', 'a', 'f', 'A', 'Z', 'T', ':', '.', '-', ' ',
                (byte) 0x80, (byte) 0xC3, (byte) 0xFF};
        Random random = new Random(1582);
        boolean sawAccepted = false;
        boolean sawRejected = false;
        for (int n = 0; n < 3000; n++) {
            byte[] mutated = mutate(bytes, random, interesting);
            if (n % 3 == 0) {
                mutated = mutate(mutated, random, interesting);
            }
            LedgerScanner.Result result = assertSameScan(mutated, Events.SCOPE);
            sawAccepted |= !result.records().isEmpty();
            sawRejected |= result.hasErrors();
        }
        assertTrue(sawAccepted);
        assertTrue(sawRejected);
    }

    @Test
    void seededPayloadMutationsWithCorrectFraming() throws IOException {
        List<byte[]> lines = validLines();
        List<ExecutionEvent> events = events();
        byte[] interesting = {'|', '\\', '~', '0', '1', '5', '9', 'p', 'x', 'Z', 'T', 'z', ':', '.', '-', '+', ' '};
        Random random = new Random(1583);
        boolean sawNonCanonical = false;
        for (int n = 0; n < 4000; n++) {
            int index = random.nextInt(events.size());
            byte[] payload = mutate(EventPayloadCodec.encode(events.get(index)), random, interesting);
            LedgerScanner.Result result = assertSameScan(withLine(lines, index, frame(index + 1, payload)),
                    Events.SCOPE);
            sawNonCanonical |= result.diagnostics().stream()
                    .anyMatch(d -> d.message().equals("payload is not in canonical form"));
        }
        assertTrue(sawNonCanonical);
    }

    private static byte[] mutate(byte[] bytes, Random random, byte[] interesting) {
        int at = random.nextInt(bytes.length);
        byte value = random.nextInt(4) == 0 ? (byte) random.nextInt(256)
                : interesting[random.nextInt(interesting.length)];
        return switch (random.nextInt(3)) {
            case 0 -> {
                byte[] changed = bytes.clone();
                changed[at] = value;
                yield changed;
            }
            case 1 -> {
                byte[] changed = new byte[bytes.length - 1];
                System.arraycopy(bytes, 0, changed, 0, at);
                System.arraycopy(bytes, at + 1, changed, at, bytes.length - at - 1);
                yield changed;
            }
            default -> {
                byte[] changed = new byte[bytes.length + 1];
                System.arraycopy(bytes, 0, changed, 0, at);
                changed[at] = value;
                System.arraycopy(bytes, at, changed, at + 1, bytes.length - at);
                yield changed;
            }
        };
    }
}
