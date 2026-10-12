package com.monada.storage.execution;

import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Versioned, append-only checkpoint of one scope's projection (contract v1, section 11). It maps every
 * projected atom ID to the exact {@link ExperienceRef}s it represents and records which ledger sequences
 * are fully projected. It is derived data: the ledger stays authoritative and the checkpoint is rebuilt
 * from it explicitly.
 *
 * <p>Line framing: {@code MXP1 TAB lineNumber TAB payloadBytes TAB sha256hex TAB payload LF}. Payloads:
 *
 * <pre>
 * HEADER|1|summary-text-v1|&lt;dims&gt;|&lt;scope&gt;               line 1: projection manifest (memory dimension)
 * PROJECT|&lt;ledgerSeq&gt;|&lt;atomId&gt;|&lt;canonical ref&gt;           atom now represents ref
 * RETIRE|&lt;ledgerSeq&gt;|&lt;atomId&gt;|&lt;canonical ref&gt;            atom no longer represents ref
 * COVER|&lt;ledgerSeq&gt;|&lt;sha256hex of the ledger payload&gt;   ledger event fully projected
 * </pre>
 *
 * <p>Every ledger sequence gets exactly one {@code COVER}, in order from 1, preceded by its
 * {@code PROJECT}/{@code RETIRE} lines (often none). Entries after the last {@code COVER} are an
 * incomplete batch: they are ignored and reported as an unclean tail (the projection is stale), never as
 * applied. Reading uses two streaming passes, so memory is bounded by the folded state, not by batch size.
 * Any other defect (digest, framing, unknown version, out-of-order sequence, duplicate or unknown
 * pair) stops reading and is reported as a problem; nothing is ever repaired in place.
 */
public final class ProjectionCheckpoint implements AutoCloseable {
    public static final String FILE_NAME = "projection-checkpoint.log";
    /** Projection format version, reported with every recall hit. */
    public static final int FORMAT_VERSION = 1;
    /**
     * Vector dimension of every format-1 projection memory: the production {@code MonadaMemory} default the
     * builder uses. It is part of the format, not trusted from files: a header or manifest naming another
     * dimension is a problem, and a builder producing another dimension must introduce a new format version.
     */
    public static final int PROJECTION_DIMENSIONS = 128;
    /** Canonical projection text scheme (see the API's projection text builder). */
    public static final String TEXT_SCHEME = "summary-text-v1";
    static final String MAGIC = "MXP1";
    static final int MAX_LINE_BYTES = RecordLine.MAX_LINE_BYTES;
    private static final Pattern ATOM_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SEQUENCE = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int STREAM_BUFFER_BYTES = 65_536;
    private static final Pattern DIMENSIONS = Pattern.compile("[1-9][0-9]{0,5}");

    /** Kind of a mapping entry. */
    public enum EntryKind { PROJECT, RETIRE }

    /** One mapping change produced while projecting a ledger event. */
    public record Entry(EntryKind kind, String atomId, ExperienceRef ref) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(atomId, "atomId");
            Objects.requireNonNull(ref, "ref");
            if (!ATOM_ID.matcher(atomId).matches()) {
                throw new IllegalArgumentException("atomId is not a canonical atom UUID");
            }
        }
    }

    /**
     * Folded content of a checkpoint file.
     *
     * @param coveredSequence last ledger sequence fully projected (0 when none)
     * @param coverDigests    ledger payload digest of every covered sequence, index {@code seq - 1}
     * @param refsByAtom      live mapping, atoms and refs in canonical order
     * @param retiredAtoms    atoms that were projected and no longer represent any ref
     * @param cleanTail       false when the file ends in a torn line or an incomplete batch
     * @param problems        defects that make the projection incompatible (empty when sound)
     * @param dimensions      vector dimension the generation's memory was built with (0 without a valid header)
     */
    public record Snapshot(int dimensions, long coveredSequence, List<String> coverDigests,
                           SortedMap<String, List<ExperienceRef>> refsByAtom, SortedSet<String> retiredAtoms,
                           boolean cleanTail, List<String> problems) {
        public Snapshot {
            coverDigests = List.copyOf(coverDigests);
            TreeMap<String, List<ExperienceRef>> copy = new TreeMap<>();
            refsByAtom.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            refsByAtom = Collections.unmodifiableSortedMap(copy);
            retiredAtoms = Collections.unmodifiableSortedSet(new TreeSet<>(retiredAtoms));
            problems = List.copyOf(problems);
        }

        public boolean isSound() {
            return problems.isEmpty();
        }
    }

    private final Path file;
    private final ScopeId scope;
    private final FileChannel channel;
    private long lines;
    private long covered;
    private boolean closed;
    private boolean failed;
    private boolean batchOpen;

    private ProjectionCheckpoint(Path file, ScopeId scope, FileChannel channel, long lines, long covered) {
        this.file = file;
        this.scope = scope;
        this.channel = channel;
        this.lines = lines;
        this.covered = covered;
    }

    /** Ledger payload digest of {@code event}: the SHA-256 the ledger line of that event carries. */
    public static String ledgerDigest(ExecutionEvent event) {
        return RecordLine.sha256Hex(EventPayloadCodec.encode(event));
    }

    /**
     * Creates a new checkpoint (the file must not exist) holding only the header, durably. {@code dimensions} is
     * the vector dimension of the generation's memory; verification pins the memory manifest to it.
     */
    public static ProjectionCheckpoint create(Path file, ScopeId scope, int dimensions) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(scope, "scope");
        if (dimensions != PROJECTION_DIMENSIONS) {
            throw new IllegalArgumentException("format " + FORMAT_VERSION + " projections have "
                    + PROJECTION_DIMENSIONS + " dimensions, not " + dimensions);
        }
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        try {
            writeFully(channel, frame(1, header(scope, dimensions)));
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return new ProjectionCheckpoint(file, scope, channel, 1, 0);
    }

    /**
     * Opens a sound, cleanly ending checkpoint for appending. {@code snapshot} must be the result of
     * {@link #read} for this file; an unsound or unclean one is refused (an explicit rebuild is required).
     */
    public static ProjectionCheckpoint openForAppend(Path file, ScopeId scope, Snapshot snapshot)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.isSound() || !snapshot.cleanTail()) {
            throw new IOException("projection checkpoint is not sound; rebuild the projection explicitly");
        }
        long lineCount = countLines(file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return new ProjectionCheckpoint(file, scope, channel, lineCount, snapshot.coveredSequence());
    }

    /**
     * Durably appends the entries of ledger sequence {@code ledgerSequence} followed by its {@code COVER}. The
     * sequence must be the next uncovered one. Convenience over {@link #begin}.
     */
    public void commit(long ledgerSequence, String ledgerDigest, List<Entry> entries) throws IOException {
        Objects.requireNonNull(ledgerDigest, "ledgerDigest");
        Objects.requireNonNull(entries, "entries");
        for (Entry e : entries) { // validate before writing anything
            if (!e.ref().scope().equals(scope)) {
                throw new IllegalArgumentException("entry ref belongs to scope " + e.ref().scope());
            }
        }
        if (!DIGEST.matcher(ledgerDigest).matches()) {
            throw new IllegalArgumentException("ledger digest must be 64 lowercase hex characters");
        }
        Batch batch = begin(ledgerSequence);
        for (Entry e : entries) {
            batch.write(e);
        }
        batch.cover(ledgerDigest);
    }

    /**
     * Starts the batch of ledger sequence {@code ledgerSequence} (the next uncovered one). Entries are streamed
     * through a bounded buffer as they are produced, never held as a whole batch, and {@link Batch#cover}
     * writes the {@code COVER} last and forces the file. A batch abandoned part-way leaves entries without their
     * {@code COVER}, which readers ignore as an unclean tail.
     */
    public Batch begin(long ledgerSequence) {
        if (closed) {
            throw new IllegalStateException("projection checkpoint is closed");
        }
        if (failed) {
            throw new IllegalStateException("projection checkpoint failed earlier; rebuild the projection");
        }
        if (batchOpen) {
            throw new IllegalStateException("a batch is already open");
        }
        if (ledgerSequence != covered + 1) {
            throw new IllegalArgumentException("expected ledger sequence " + (covered + 1) + ", got " + ledgerSequence);
        }
        batchOpen = true;
        return new Batch(ledgerSequence);
    }

    /** One ledger sequence's entries being streamed to the checkpoint; see {@link #begin}. */
    public final class Batch {
        private final long ledgerSequence;
        private final OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel),
                STREAM_BUFFER_BYTES); // never closed: closing would close the channel
        private long line = lines;
        private boolean done;

        private Batch(long ledgerSequence) {
            this.ledgerSequence = ledgerSequence;
        }

        public void write(Entry e) throws IOException {
            Objects.requireNonNull(e, "entry");
            requireActive();
            if (!e.ref().scope().equals(scope)) {
                fail();
                throw new IllegalArgumentException("entry ref belongs to scope " + e.ref().scope());
            }
            try {
                out.write(frame(line + 1, e.kind().name() + "|" + ledgerSequence + "|" + e.atomId() + "|"
                        + ExperienceRefCodec.canonical(e.ref())));
                line++;
            } catch (IOException | RuntimeException ex) {
                fail();
                throw ex;
            }
        }

        /** Writes the {@code COVER}, flushes and forces: the ledger sequence is now fully projected. */
        public void cover(String ledgerDigest) throws IOException {
            Objects.requireNonNull(ledgerDigest, "ledgerDigest");
            requireActive();
            if (!DIGEST.matcher(ledgerDigest).matches()) {
                fail();
                throw new IllegalArgumentException("ledger digest must be 64 lowercase hex characters");
            }
            try {
                out.write(frame(line + 1, "COVER|" + ledgerSequence + "|" + ledgerDigest));
                out.flush();
                channel.force(true);
            } catch (IOException | RuntimeException ex) {
                fail();
                throw ex;
            }
            line++;
            done = true;
            batchOpen = false;
            lines = line;
            covered = ledgerSequence;
        }

        private void requireActive() {
            if (done || failed || closed) {
                throw new IllegalStateException("projection checkpoint batch is no longer usable");
            }
        }

        private void fail() {
            failed = true;
            batchOpen = false;
        }
    }

    public long coveredSequence() {
        return covered;
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        channel.close();
    }

    // ------------------------------------------------------------------ reading

    /** Reads and folds a checkpoint without modifying it. A missing file is an {@link IOException}. */
    public static Snapshot read(Path file, ScopeId scope) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(scope, "scope");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(FILE_NAME + " is missing or not a regular file");
        }
        // Two streaming passes keep memory bounded by the folded state: the first finds the last COVER of the
        // well-framed prefix; the second applies every entry up to it immediately (no per-batch buffer) and only
        // syntax-checks what follows, which is an uncovered tail and never applied.
        Fold fold = new Fold(scope, lastCoverLine(file, scope));
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

    /** Line number of the last well-framed COVER before the first framing defect or torn line (0 when none). */
    private static long lastCoverLine(Path file, ScopeId scope) throws IOException {
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
        if (!parts[3].equals(RecordLine.sha256Hex(payload))) {
            fold.problem(lineNumber, "payload digest mismatch");
            return null;
        }
        return parts[4];
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
                } else if (!t[1].equals(Integer.toString(FORMAT_VERSION)) || !t[2].equals(TEXT_SCHEME)) {
                    problem(line, "unsupported projection version " + t[1] + "/" + t[2]);
                } else if (t.length != 5 || !DIMENSIONS.matcher(t[3]).matches()) {
                    problem(line, "malformed projection header");
                } else if (Integer.parseInt(t[3]) != PROJECTION_DIMENSIONS) {
                    problem(line, "unsupported projection dimension " + t[3] + " (format " + FORMAT_VERSION
                            + " uses " + PROJECTION_DIMENSIONS + ")");
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
                        apply(line, t); // covered: applied at once; a problem stops the read
                    } else {
                        uncoveredTail = true; // an interrupted batch: checked, never applied
                    }
                }
                case "COVER" -> {
                    if (t.length != 3 || !SEQUENCE.matcher(t[1]).matches() || !t[1].equals(Long.toString(next))
                            || !DIGEST.matcher(t[2]).matches()) {
                        problem(line, "malformed or out-of-order COVER, expected ledger sequence " + next);
                        return;
                    }
                    if (line > lastCover) {
                        uncoveredTail = true; // appeared after the first pass (a concurrent writer): not applied
                        return;
                    }
                    coverDigests.add(t[2]);
                }
                default -> problem(line, "unknown entry kind");
            }
        }

        private boolean apply(long line, String[] t) {
            String atom = t[2];
            ExperienceRef ref = ExperienceRefCodec.fromTokens(t, 3);
            String key = ExperienceRefCodec.canonical(ref);
            if (t[0].equals("PROJECT")) {
                TreeMap<String, ExperienceRef> refs = live.computeIfAbsent(atom, k -> new TreeMap<>());
                if (refs.putIfAbsent(key, ref) != null) {
                    problem(line, "duplicate projection of " + key);
                    return false;
                }
                projected.add(atom);
            } else {
                TreeMap<String, ExperienceRef> refs = live.get(atom);
                if (refs == null || refs.remove(key) == null) {
                    problem(line, "retirement of a ref the atom does not represent: " + key);
                    return false;
                }
                if (refs.isEmpty()) {
                    live.remove(atom);
                }
            }
            return true;
        }

        Snapshot snapshot() {
            TreeMap<String, List<ExperienceRef>> refsByAtom = new TreeMap<>();
            live.forEach((atom, refs) -> refsByAtom.put(atom, new ArrayList<>(refs.values())));
            TreeSet<String> retired = new TreeSet<>(projected);
            retired.removeAll(live.keySet());
            boolean clean = !tornTail && !uncoveredTail;
            return new Snapshot(dimensions, coverDigests.size(), coverDigests, refsByAtom, retired, clean, problems);
        }
    }

    // ------------------------------------------------------------------ framing helpers

    private static String header(ScopeId scope, int dimensions) {
        return "HEADER|" + FORMAT_VERSION + "|" + TEXT_SCHEME + "|" + dimensions + "|"
                + EventPayloadCodec.escape(scope.value());
    }

    private static byte[] frame(long line, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] head = (MAGIC + "\t" + line + "\t" + body.length + "\t" + RecordLine.sha256Hex(body) + "\t")
                .getBytes(StandardCharsets.US_ASCII);
        if (head.length + body.length + 1 > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("projection checkpoint line exceeds " + MAX_LINE_BYTES + " bytes");
        }
        byte[] out = new byte[head.length + body.length + 1];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        out[out.length - 1] = '\n';
        return out;
    }

    private record Line(byte[] bytes, boolean terminated, boolean oversized) {
    }

    /** Next line without its LF, or null at end of input; never buffers more than the line limit. */
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

    private static long countLines(Path file) throws IOException {
        long count = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 65_536)) {
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    count++;
                }
            }
        }
        return count;
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
