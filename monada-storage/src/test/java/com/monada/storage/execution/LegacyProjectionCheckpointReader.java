package com.monada.storage.execution;

import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Test-only copy of the {@link ProjectionCheckpoint} reader as it was before #158 (byte-by-byte line reading,
 * {@code split}/regex parsing, a fresh {@link MessageDigest} per line). It pins the reference behavior: every
 * input must fold to an equal {@link ProjectionCheckpoint.Snapshot} through both readers.
 */
final class LegacyProjectionCheckpointReader {
    private static final String FILE_NAME = ProjectionCheckpoint.FILE_NAME;
    private static final String MAGIC = "MXP1";
    private static final int MAX_LINE_BYTES = RecordLine.MAX_LINE_BYTES;
    private static final Pattern ATOM_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SEQUENCE = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern DIMENSIONS = Pattern.compile("[1-9][0-9]{0,5}");

    private LegacyProjectionCheckpointReader() {
    }

    static ProjectionCheckpoint.Snapshot read(Path file, ScopeId scope) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(FILE_NAME + " is missing or not a regular file");
        }
        return read(file, scope, lastCoverLine(file, scope));
    }

    /** The second pass alone, with the first pass's result given (to simulate a concurrent COVER). */
    static ProjectionCheckpoint.Snapshot read(Path file, ScopeId scope, long lastCover) throws IOException {
        Fold fold = new Fold(scope, lastCover);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 65_536)) {
            long lineNumber = 0;
            while (fold.problems.isEmpty()) {
                Line line = nextLine(in);
                if (line == null) {
                    break;
                }
                lineNumber++;
                if (!line.terminated()) {
                    fold.tornTail = true;
                    break;
                }
                fold.lines = lineNumber;
                if (line.oversized()) {
                    fold.problem(lineNumber, "line exceeds " + MAX_LINE_BYTES + " bytes");
                    break;
                }
                String payload = unframe(line.bytes(), lineNumber, fold);
                if (payload != null) {
                    fold.accept(lineNumber, payload);
                }
            }
            if (lineNumber == 0 && fold.problems.isEmpty()) {
                fold.problem(1, "checkpoint has no header");
            }
        }
        return fold.snapshot();
    }

    static long lastCoverLine(Path file, ScopeId scope) throws IOException {
        Fold scratch = new Fold(scope, 0);
        long lastCover = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 65_536)) {
            long lineNumber = 0;
            while (true) {
                Line line = nextLine(in);
                if (line == null || !line.terminated() || line.oversized()) {
                    break;
                }
                lineNumber++;
                String payload = unframe(line.bytes(), lineNumber, scratch);
                if (payload == null) {
                    break;
                }
                if (payload.startsWith("COVER|")) {
                    lastCover = lineNumber;
                }
            }
        }
        return lastCover;
    }

    private static String unframe(byte[] bytes, long lineNumber, Fold fold) {
        String text;
        try {
            text = LedgerPaths.decodeStrict(bytes);
        } catch (CharacterCodingException e) {
            fold.problem(lineNumber, "line is not valid UTF-8");
            return null;
        }
        String[] parts = text.split("\t", 5);
        if (parts.length != 5 || !parts[0].equals(MAGIC)) {
            fold.problem(lineNumber, "unknown line framing");
            return null;
        }
        if (!parts[1].equals(Long.toString(lineNumber))) {
            fold.problem(lineNumber, "line number " + parts[1] + " out of order");
            return null;
        }
        byte[] payload = parts[4].getBytes(StandardCharsets.UTF_8);
        if (!parts[2].equals(Integer.toString(payload.length))) {
            fold.problem(lineNumber, "payload length mismatch");
            return null;
        }
        if (!parts[3].equals(sha256Hex(payload))) {
            fold.problem(lineNumber, "payload digest mismatch");
            return null;
        }
        return parts[4];
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Fold {
        private final ScopeId scope;
        private final List<String> problems = new ArrayList<>();
        private final List<String> coverDigests = new ArrayList<>();
        private final TreeMap<String, TreeMap<String, ExperienceRef>> live = new TreeMap<>();
        private final Set<String> projected = new LinkedHashSet<>();
        private final long lastCover;
        private boolean uncoveredTail;
        private boolean header;
        private int dimensions;
        private boolean tornTail;
        private long lines;

        Fold(ScopeId scope, long lastCover) {
            this.scope = scope;
            this.lastCover = lastCover;
        }

        void problem(long line, String message) {
            problems.add(FILE_NAME + " line " + line + ": " + message);
        }

        void accept(long line, String payload) {
            String[] t = payload.split("\\|", -1);
            if (!header) {
                if (t.length < 3 || !t[0].equals("HEADER")) {
                    problem(line, "first line is not a projection header");
                } else if (!t[1].equals(Integer.toString(ProjectionCheckpoint.FORMAT_VERSION))
                        || !t[2].equals(ProjectionCheckpoint.TEXT_SCHEME)) {
                    problem(line, "unsupported projection version " + t[1] + "/" + t[2]);
                } else if (t.length != 5 || !DIMENSIONS.matcher(t[3]).matches()) {
                    problem(line, "malformed projection header");
                } else if (Integer.parseInt(t[3]) != ProjectionCheckpoint.PROJECTION_DIMENSIONS) {
                    problem(line, "unsupported projection dimension " + t[3] + " (format "
                            + ProjectionCheckpoint.FORMAT_VERSION + " uses "
                            + ProjectionCheckpoint.PROJECTION_DIMENSIONS + ")");
                } else if (!t[4].equals(EventPayloadCodec.escape(scope.value()))) {
                    problem(line, "header belongs to another scope");
                } else {
                    header = true;
                    dimensions = Integer.parseInt(t[3]);
                }
                return;
            }
            long next = coverDigests.size() + 1L;
            switch (t[0]) {
                case "PROJECT", "RETIRE" -> {
                    if (t.length != 3 + ExperienceRefCodec.TOKENS || !t[1].equals(Long.toString(next))
                            || !ATOM_ID.matcher(t[2]).matches()) {
                        problem(line, "malformed " + t[0] + " entry for ledger sequence " + next);
                        return;
                    }
                    try {
                        ExperienceRef ref = ExperienceRefCodec.fromTokens(t, 3);
                        if (!ref.scope().equals(scope)) {
                            problem(line, "entry ref belongs to another scope");
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        problem(line, "malformed experience ref: " + e.getMessage());
                        return;
                    }
                    if (line < lastCover) {
                        apply(line, t);
                    } else {
                        uncoveredTail = true;
                    }
                }
                case "COVER" -> {
                    if (t.length != 3 || !SEQUENCE.matcher(t[1]).matches() || !t[1].equals(Long.toString(next))
                            || !DIGEST.matcher(t[2]).matches()) {
                        problem(line, "malformed or out-of-order COVER, expected ledger sequence " + next);
                        return;
                    }
                    if (line > lastCover) {
                        uncoveredTail = true;
                        return;
                    }
                    coverDigests.add(t[2]);
                }
                default -> problem(line, "unknown entry kind");
            }
        }

        private void apply(long line, String[] t) {
            String atom = t[2];
            ExperienceRef ref = ExperienceRefCodec.fromTokens(t, 3);
            String key = ExperienceRefCodec.canonical(ref);
            if (t[0].equals("PROJECT")) {
                TreeMap<String, ExperienceRef> refs = live.computeIfAbsent(atom, k -> new TreeMap<>());
                if (refs.putIfAbsent(key, ref) != null) {
                    problem(line, "duplicate projection of " + key);
                    return;
                }
                projected.add(atom);
            } else {
                TreeMap<String, ExperienceRef> refs = live.get(atom);
                if (refs == null || refs.remove(key) == null) {
                    problem(line, "retirement of a ref the atom does not represent: " + key);
                    return;
                }
                if (refs.isEmpty()) {
                    live.remove(atom);
                }
            }
        }

        ProjectionCheckpoint.Snapshot snapshot() {
            TreeMap<String, List<ExperienceRef>> refsByAtom = new TreeMap<>();
            live.forEach((atom, refs) -> refsByAtom.put(atom, new ArrayList<>(refs.values())));
            TreeSet<String> retired = new TreeSet<>(projected);
            retired.removeAll(live.keySet());
            boolean clean = !tornTail && !uncoveredTail;
            return new ProjectionCheckpoint.Snapshot(dimensions, coverDigests.size(), coverDigests, refsByAtom,
                    retired, clean, problems, lines);
        }
    }

    private record Line(byte[] bytes, boolean terminated, boolean oversized) {
    }

    private static Line nextLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean oversized = false;
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                return new Line(buf.toByteArray(), true, oversized);
            }
            if (buf.size() < MAX_LINE_BYTES) {
                buf.write(b);
            } else {
                oversized = true;
            }
        }
        return any ? new Line(buf.toByteArray(), false, oversized) : null;
    }
}
