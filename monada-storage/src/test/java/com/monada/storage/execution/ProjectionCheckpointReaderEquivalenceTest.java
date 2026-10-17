package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
import com.monada.storage.execution.ProjectionCheckpoint.Entry;
import com.monada.storage.execution.ProjectionCheckpoint.EntryKind;
import com.monada.storage.execution.ProjectionCheckpoint.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The #158 checkpoint reader (buffered lines, byte-level framing, reused digest, no regex) must fold every input
 * to a {@link Snapshot} equal, field by field, to the one the pre-#158 reader produced
 * ({@link LegacyProjectionCheckpointReader}).
 */
class ProjectionCheckpointReaderEquivalenceTest {
    private static final ScopeId SCOPE = ScopeId.of("scope|1\\x");
    private static final String ATOM_A = "00000000-0000-3000-8000-00000000000a";
    private static final String ATOM_B = "00000000-0000-3000-8000-00000000000b";
    private static final String ATOM_C = "00000000-0000-3000-8000-00000000000c";
    private static final String DIGEST = "a".repeat(64);
    private static final int MAX = ProjectionCheckpoint.MAX_LINE_BYTES;

    @TempDir
    Path root;

    private Path file() {
        return root.resolve(ProjectionCheckpoint.FILE_NAME);
    }

    private static ExperienceRef ref(String event, int revision) {
        return new ExperienceRef(SCOPE, TaskId.of("task"), ExecutionId.of("exec"), Optional.of(AttemptId.of("a1")),
                EventId.of(event), revision);
    }

    /** Reads {@link #file()} with both readers and requires equal snapshots; returns the new one. */
    private Snapshot assertEquivalent() throws IOException {
        Snapshot legacy = LegacyProjectionCheckpointReader.read(file(), SCOPE);
        Snapshot current = ProjectionCheckpoint.read(file(), SCOPE);
        assertEquals(legacy, current);
        return current;
    }

    private Snapshot assertEquivalent(byte[] content) throws IOException {
        Files.write(file(), content);
        return assertEquivalent();
    }

    private void writeSample() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of());
            c.commit(2, "b".repeat(64), List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("z", 1)),
                    new Entry(EntryKind.PROJECT, ATOM_A, ref("b|é", 1))));
            c.commit(3, "c".repeat(64), List.of(new Entry(EntryKind.RETIRE, ATOM_A, ref("z", 1)),
                    new Entry(EntryKind.PROJECT, ATOM_B, ref("z2€", 2))));
            c.commit(4, "d".repeat(64), List.of(new Entry(EntryKind.RETIRE, ATOM_A, ref("b|é", 1)),
                    new Entry(EntryKind.PROJECT, ATOM_C, ref("😀", 3))));
        }
    }

    /** A framed checkpoint line with a correct length and digest. */
    private static byte[] frame(long line, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        return frameRaw(Long.toString(line), Integer.toString(body.length), RecordLine.sha256Hex(body), body);
    }

    private static byte[] frameRaw(String line, String length, String digest, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("MXP1\t" + line + "\t" + length + "\t" + digest + "\t").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body);
        out.write('\n');
        return out.toByteArray();
    }

    private static String header() {
        return "HEADER|1|summary-text-v1|128|" + EventPayloadCodec.escape(SCOPE.value());
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void missingFileFailsInBoth() {
        assertThrows(IOException.class, () -> LegacyProjectionCheckpointReader.read(file(), SCOPE));
        assertThrows(IOException.class, () -> ProjectionCheckpoint.read(file(), SCOPE));
    }

    @Test
    void emptyFileHeaderOnlyAndSoundFile() throws IOException {
        Snapshot empty = assertEquivalent(new byte[0]);
        assertEquals(List.of(ProjectionCheckpoint.FILE_NAME + " line 1: checkpoint has no header"), empty.problems());

        Files.delete(file());
        ProjectionCheckpoint.create(file(), SCOPE, 128).close();
        Snapshot headerOnly = assertEquivalent();
        assertTrue(headerOnly.isSound());
        assertEquals(1, headerOnly.lines());

        Files.delete(file());
        writeSample();
        Snapshot sound = assertEquivalent();
        assertTrue(sound.isSound() && sound.cleanTail(), sound.problems().toString());
        assertEquals(4, sound.coveredSequence());
        assertEquals(List.of(ATOM_B, ATOM_C), List.copyOf(sound.refsByAtom().keySet()));
        assertEquals(List.of(ATOM_A), List.copyOf(sound.retiredAtoms()));
        assertEquals(List.of(DIGEST, "b".repeat(64), "c".repeat(64), "d".repeat(64)), sound.coverDigests());
    }

    @Test
    void lineOfExactlyTheLimitAndOneByteMore() throws IOException {
        byte[] head = frame(1, header());
        for (int extra = -1; extra <= 1; extra++) {
            // A well-framed line of MAX + extra bytes before its LF; its payload is an unknown entry kind. The
            // framing before the payload is "MXP1 TAB 2 TAB <5-digit length> TAB <64 hex> TAB": 78 bytes.
            int bodyLength = MAX + extra - 78;
            byte[] body = new byte[bodyLength];
            Arrays.fill(body, (byte) 'X');
            byte[] line = frameRaw("2", Integer.toString(bodyLength), RecordLine.sha256Hex(body), body);
            assertEquals(MAX + extra + 1, line.length, "line + LF");
            Snapshot s = assertEquivalent(concat(head, line));
            String expected = extra > 0 ? "line exceeds " + MAX + " bytes" : "unknown entry kind";
            assertEquals(List.of(ProjectionCheckpoint.FILE_NAME + " line 2: " + expected), s.problems());
            assertEquals(2, s.lines());
        }
        // An oversized line is also refused when it is the torn last line, and when it has no framing at all.
        byte[] huge = new byte[3 * MAX + 17];
        Arrays.fill(huge, (byte) 'y');
        assertEquivalent(concat(head, huge));
        assertEquivalent(concat(head, huge, new byte[] {'\n'}, frame(3, "COVER|1|" + DIGEST)));
    }

    @Test
    void tornLastLineAndMissingFinalLf() throws IOException {
        writeSample();
        byte[] sound = Files.readAllBytes(file());
        Snapshot noFinalLf = assertEquivalent(Arrays.copyOf(sound, sound.length - 1));
        assertFalse(noFinalLf.cleanTail());
        assertEquals(3, noFinalLf.coveredSequence());
        Snapshot torn = assertEquivalent(concat(sound, "MXP1\t11\t9".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(torn.isSound());
        assertFalse(torn.cleanTail());
        assertEquals(4, torn.coveredSequence());
    }

    @Test
    void multiByteCharactersSplitAcrossTheReadWindow() throws IOException {
        // Pad an entry so that a multi-byte character straddles each read-window boundary (64 KiB multiples).
        List<Integer> straddled = new ArrayList<>();
        for (int pad = 0; pad < 8; pad++) {
            Files.deleteIfExists(file());
            try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
                List<Entry> entries = new ArrayList<>();
                entries.add(new Entry(EntryKind.PROJECT, ATOM_A, ref("p" + "x".repeat(pad), 1)));
                for (int i = 0; i < 2_000; i++) {
                    entries.add(new Entry(EntryKind.PROJECT, ATOM_B, ref("é€😀".repeat(8) + i, 1)));
                }
                c.commit(1, DIGEST, entries);
            }
            byte[] bytes = Files.readAllBytes(file());
            assertTrue(bytes.length > 3 * 65_536);
            if ((bytes[65_536] & 0xC0) == 0x80) {
                straddled.add(pad);
            }
            Snapshot s = assertEquivalent();
            assertTrue(s.isSound(), s.problems().toString());
            assertEquals(2_000, s.refsByAtom().get(ATOM_B).size());
        }
        assertFalse(straddled.isEmpty(), "some padding splits a character at the window boundary");
    }

    @Test
    void invalidUtf8() throws IOException {
        byte[] head = frame(1, header());
        byte[][] invalid = {
            {(byte) 0xFF},
            {(byte) 0xC3}, // truncated two-byte sequence at the end of the line
            {(byte) 0xC0, (byte) 0xAF}, // overlong
            {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, // encoded surrogate
            {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, // beyond U+10FFFF
        };
        for (byte[] bad : invalid) {
            byte[] body = concat("COVER|1|".getBytes(StandardCharsets.US_ASCII), bad);
            Snapshot s = assertEquivalent(concat(head, frameRaw("2", Integer.toString(body.length),
                    RecordLine.sha256Hex(body), body)));
            assertEquals(List.of(ProjectionCheckpoint.FILE_NAME + " line 2: line is not valid UTF-8"), s.problems());
            // invalid bytes in the framing fields, too
            assertEquivalent(concat(head, concat("MXP1\t2".getBytes(StandardCharsets.US_ASCII), bad,
                    "\t1\t".getBytes(StandardCharsets.US_ASCII), DIGEST.getBytes(StandardCharsets.US_ASCII),
                    "\tX\n".getBytes(StandardCharsets.US_ASCII))));
        }
    }

    @Test
    void framingDefects() throws IOException {
        byte[] head = frame(1, header());
        byte[] body = ("COVER|1|" + DIGEST).getBytes(StandardCharsets.UTF_8);
        String len = Integer.toString(body.length);
        String sha = RecordLine.sha256Hex(body);
        List<byte[]> lines = List.of(
                frameRaw("2", len, sha, body),
                frameRaw("3", len, sha, body), // line number out of order
                frameRaw("02", len, sha, body),
                frameRaw("é", len, sha, body),
                frameRaw("2", Integer.toString(body.length + 1), sha, body), // length mismatch
                frameRaw("2", "0" + len, sha, body),
                frameRaw("2", len, sha.toUpperCase(), body), // digest mismatch
                frameRaw("2", len, sha.substring(1), body),
                frameRaw("2", len, sha + "0", body),
                frameRaw("2", len, "z".repeat(64), body),
                "MXP2\t2\t1\tx\ty\n".getBytes(StandardCharsets.US_ASCII), // unknown framing
                "MXP1\t2\t1\tx\n".getBytes(StandardCharsets.US_ASCII),
                "MXP1 2 1 x y\n".getBytes(StandardCharsets.US_ASCII),
                "\n".getBytes(StandardCharsets.US_ASCII),
                "MXP1\t2\t3\t\t\t\n".getBytes(StandardCharsets.US_ASCII));
        for (byte[] line : lines) {
            assertEquivalent(concat(head, line, frame(3, "COVER|2|" + DIGEST)));
        }
        Snapshot s = assertEquivalent(concat(head, frameRaw("3", len, sha, body)));
        assertEquals(List.of(ProjectionCheckpoint.FILE_NAME + " line 2: line number 3 out of order"), s.problems());
        s = assertEquivalent(concat(head, frameRaw("2", len, "0".repeat(64), body)));
        assertEquals(List.of(ProjectionCheckpoint.FILE_NAME + " line 2: payload digest mismatch"), s.problems());
        // a tab inside the payload belongs to the payload
        byte[] tabbed = "COVER|1|\tx".getBytes(StandardCharsets.UTF_8);
        assertEquivalent(concat(head, frameRaw("2", Integer.toString(tabbed.length), RecordLine.sha256Hex(tabbed),
                tabbed)));
    }

    @Test
    void entrySyntaxDefectsAndUncoveredTail() throws IOException {
        String ref = ExperienceRefCodec.canonical(ref("e1", 1));
        List<String> payloads = List.of(
                "PROJECT|1|" + ATOM_A + "|" + ref,
                "PROJECT|2|" + ATOM_A + "|" + ref,
                "PROJECT|1|" + ATOM_A.toUpperCase() + "|" + ref,
                "PROJECT|1|" + ATOM_A.replace('-', '_') + "|" + ref,
                "PROJECT|1|" + ATOM_A + "x|" + ref,
                "PROJECT|1|" + ATOM_A + "|" + ref + "|",
                "PROJECT|1|" + ATOM_A + "|" + ref.replace("scope", "other"),
                "RETIRE|1|" + ATOM_A + "|" + ref,
                "COVER|1|" + DIGEST.toUpperCase(),
                "COVER|01|" + DIGEST,
                "COVER|1|" + DIGEST + "|",
                "COVER|1|" + DIGEST.substring(1),
                "COVER|1",
                "COVER|١|" + DIGEST,
                "COVER|12345678901234567890|" + DIGEST,
                "UNKNOWN|1",
                "",
                "|||");
        for (String payload : payloads) {
            for (boolean covered : new boolean[] {true, false}) {
                byte[] content = concat(frame(1, header()), frame(2, payload));
                if (covered) {
                    content = concat(content, frame(3, "COVER|1|" + DIGEST));
                }
                assertEquivalent(content);
            }
        }
        // a duplicate PROJECT is a problem only when covered (applied)
        String project = "PROJECT|1|" + ATOM_A + "|" + ref;
        Snapshot covered = assertEquivalent(concat(frame(1, header()), frame(2, project), frame(3, project),
                frame(4, "COVER|1|" + DIGEST)));
        assertFalse(covered.isSound());
        Snapshot uncovered = assertEquivalent(concat(frame(1, header()), frame(2, project), frame(3, project)));
        assertTrue(uncovered.isSound());
        assertFalse(uncovered.cleanTail());
    }

    @Test
    void headerDefects() throws IOException {
        String scope = EventPayloadCodec.escape(SCOPE.value());
        List<String> headers = List.of("HEADER|2|summary-text-v1|128|" + scope, "HEADER|1|other|128|" + scope,
                "HEADER|1|summary-text-v1|064|" + scope, "HEADER|1|summary-text-v1|64|" + scope,
                "HEADER|1|summary-text-v1|128|other", "HEADER|1|summary-text-v1|128", "HEADER|1",
                "COVER|1|" + DIGEST);
        for (String h : headers) {
            assertEquivalent(concat(frame(1, h), frame(2, "COVER|1|" + DIGEST)));
        }
    }

    @Test
    void coverAppendedBetweenThePassesIsNeverApplied() throws IOException {
        writeSample();
        long lastCover = ProjectionCheckpoint.lastCoverLine(file(), SCOPE);
        assertEquals(LegacyProjectionCheckpointReader.lastCoverLine(file(), SCOPE), lastCover);
        Snapshot before = ProjectionCheckpoint.read(file(), SCOPE);
        try (ProjectionCheckpoint c = ProjectionCheckpoint.openForAppend(file(), SCOPE, before)) {
            c.commit(5, "e".repeat(64), List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("late", 1))));
        }
        Snapshot legacy = LegacyProjectionCheckpointReader.read(file(), SCOPE, lastCover);
        Snapshot current = ProjectionCheckpoint.read(file(), SCOPE, lastCover);
        assertEquals(legacy, current);
        assertEquals(4, current.coveredSequence(), "the concurrent COVER is not applied");
        assertFalse(current.cleanTail());
        assertFalse(current.refsByAtom().containsKey(ATOM_A));
        assertEquals(5, assertEquivalent().coveredSequence(), "a fresh read applies it");
    }

    @Test
    void everyTruncationAndSeededMutationReadsTheSame() throws IOException {
        writeSample();
        byte[] sound = Files.readAllBytes(file());
        for (int cut = 0; cut <= sound.length; cut++) {
            assertEquivalent(Arrays.copyOf(sound, cut));
        }
        Random random = new Random(158);
        byte[] interesting = {0, '\t', '\n', '|', '0', 'a', 'f', '-', (byte) 0xC3, (byte) 0xA9, (byte) 0xFF};
        for (int round = 0; round < 1_500; round++) {
            byte[] mutated = sound.clone();
            int at = random.nextInt(mutated.length);
            mutated[at] = random.nextBoolean() ? interesting[random.nextInt(interesting.length)]
                    : (byte) random.nextInt(256);
            assertEquivalent(mutated);
        }
    }

    @Test
    void seededPayloadMutationsWithValidFramingReadTheSame() throws IOException {
        writeSample();
        List<String> payloads = new ArrayList<>();
        for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
            payloads.add(line.split("\t", 5)[4]);
        }
        Random random = new Random(1580);
        String alphabet = "|0123456789abcdefABCDEF-\\xé€😀PROJECTRIEVH";
        for (int round = 0; round < 3_000; round++) {
            List<String> copy = new ArrayList<>(payloads);
            int which = random.nextInt(copy.size());
            switch (random.nextInt(4)) {
                case 0 -> copy.set(which, replaceOneCodePoint(copy.get(which), alphabet, random));
                case 1 -> copy.remove(which);
                case 2 -> copy.add(which, copy.get(random.nextInt(copy.size())));
                default -> Collections.swap(copy, which, random.nextInt(copy.size()));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < copy.size(); i++) {
                out.writeBytes(frame(i + 1, copy.get(i)));
            }
            assertEquivalent(out.toByteArray());
        }
    }

    private static String replaceOneCodePoint(String s, String alphabet, Random random) {
        if (s.isEmpty()) {
            return s;
        }
        int at = s.offsetByCodePoints(0, random.nextInt(s.codePointCount(0, s.length())));
        int pick = alphabet.offsetByCodePoints(0, random.nextInt(alphabet.codePointCount(0, alphabet.length())));
        return s.substring(0, at) + Character.toString(alphabet.codePointAt(pick))
                + s.substring(s.offsetByCodePoints(at, 1));
    }

    @Test
    void lineReaderMatchesTheLegacyLineSplittingForAnyChunking() throws IOException {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            int lines = random.nextInt(6);
            for (int i = 0; i < lines; i++) {
                int length = switch (random.nextInt(5)) {
                    case 0 -> 0;
                    case 1 -> MAX;
                    case 2 -> MAX + 1 + random.nextInt(3);
                    default -> random.nextInt(300);
                };
                for (int j = 0; j < length; j++) {
                    content.write('a' + random.nextInt(3));
                }
                if (i < lines - 1 || random.nextBoolean()) {
                    content.write('\n');
                }
            }
            byte[] bytes = content.toByteArray();
            List<String> expected = legacyLines(bytes);
            int chunk = 1 + random.nextInt(9_000);
            ProjectionCheckpoint.LineReader reader = new ProjectionCheckpoint.LineReader(trickle(bytes, chunk));
            List<String> actual = new ArrayList<>();
            while (reader.next()) {
                actual.add(describe(Arrays.copyOf(reader.bytes, reader.length), reader.terminated,
                        reader.oversized));
            }
            assertEquals(expected, actual, "chunk " + chunk);
            assertFalse(reader.next(), "stays at end of input");
        }
    }

    /** Line splitting of the pre-#158 {@code nextLine}: at most MAX bytes kept per line. */
    private static List<String> legacyLines(byte[] bytes) {
        List<String> out = new ArrayList<>();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean oversized = false;
        boolean any = false;
        for (byte b : bytes) {
            any = true;
            if (b == '\n') {
                out.add(describe(buf.toByteArray(), true, oversized));
                buf.reset();
                oversized = false;
                any = false;
            } else if (buf.size() < MAX) {
                buf.write(b);
            } else {
                oversized = true;
            }
        }
        if (any) {
            out.add(describe(buf.toByteArray(), false, oversized));
        }
        return out;
    }

    private static String describe(byte[] line, boolean terminated, boolean oversized) {
        return line.length + ":" + Arrays.hashCode(line) + ":" + terminated + ":" + oversized;
    }

    /** An input stream that returns at most {@code chunk} bytes per read. */
    private static InputStream trickle(byte[] bytes, int chunk) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, chunk));
            }
        };
    }

    @Test
    void fieldHelpersMatchTheRegexesAndSplit() {
        Pattern atom = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        Pattern digest = Pattern.compile("[0-9a-f]{64}");
        Pattern sequence = Pattern.compile("[1-9][0-9]{0,18}");
        Random random = new Random(7);
        String alphabet = "0123456789abcdefgABF-|١ \t";
        List<String> samples = new ArrayList<>(List.of("", "|", "||", "a|", "|a", ATOM_A, ATOM_A.toUpperCase(),
                DIGEST, DIGEST + "a", "1", "0", "01", "9".repeat(19), "9".repeat(20), "1".repeat(19) + "x"));
        for (int i = 0; i < 20_000; i++) {
            int length = switch (random.nextInt(4)) {
                case 0 -> 36;
                case 1 -> 64;
                case 2 -> 1 + random.nextInt(20);
                default -> random.nextInt(80);
            };
            StringBuilder s = new StringBuilder();
            for (int j = 0; j < length; j++) {
                s.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            if (length == 36 && random.nextBoolean()) {
                for (int dash : new int[] {8, 13, 18, 23}) {
                    s.setCharAt(dash, '-');
                }
            }
            samples.add(s.toString());
        }
        for (String s : samples) {
            assertEquals(atom.matcher(s).matches(), ProjectionCheckpoint.isAtomId(s), s);
            assertEquals(digest.matcher(s).matches(), ProjectionCheckpoint.isDigest(s), s);
            assertEquals(sequence.matcher(s).matches(), ProjectionCheckpoint.isSequence(s), s);
            assertArrayEquals(s.split("\\|", -1), ProjectionCheckpoint.splitFields(s), s);
        }
    }

    @Test
    void digestHelpersMatchTheStringDigest() {
        Random random = new Random(3);
        var sha = RecordLine.newSha256();
        for (int i = 0; i < 500; i++) {
            byte[] bytes = new byte[random.nextInt(200)];
            random.nextBytes(bytes);
            String hex = RecordLine.sha256Hex(bytes);
            byte[] framed = ("x" + hex + "y").getBytes(StandardCharsets.US_ASCII);
            byte[] padded = concat(new byte[] {1, 2}, bytes, new byte[] {3});
            assertTrue(RecordLine.sha256HexEquals(sha, padded, 2, bytes.length, framed, 1, 65));
            assertFalse(RecordLine.sha256HexEquals(sha, padded, 2, bytes.length, framed, 0, 64));
            assertFalse(RecordLine.sha256HexEquals(sha, padded, 2, bytes.length, framed, 1, 66));
            byte[] upper = ("x" + hex.toUpperCase() + "y").getBytes(StandardCharsets.US_ASCII);
            assertEquals(hex.equals(hex.toUpperCase()),
                    RecordLine.sha256HexEquals(sha, padded, 2, bytes.length, upper, 1, 65));
        }
        // the per-thread digest is reset between calls
        byte[] a = "a".getBytes(StandardCharsets.US_ASCII);
        assertEquals(RecordLine.sha256Hex(a), RecordLine.sha256Hex(a));
    }
}
