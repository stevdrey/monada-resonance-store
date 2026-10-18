import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Offline verifier for the {@code book-corpus-v1} input contract (issue #163).
 *
 * <p>Run from the repository root with the JDK source launcher; JDK only, no network access:
 * <pre>
 * java docs/experiments/book-corpus-v1/VerifyBookCorpus.java [--manifest FILE] [--write-normalized OUT] [--print-digests] INPUT
 * </pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0}: the input matches the pinned edition (or, with {@code --print-digests} on a manifest
 *       that pins nothing yet, the digests were printed);</li>
 *   <li>{@code 1}: the input bytes are invalid under the normalization policy or do not match the pinned
 *       values;</li>
 *   <li>{@code 2}: usage error, missing or unreadable input, missing or invalid manifest (missing keys,
 *       malformed values or escapes, unsupported policy), {@code --write-normalized} naming the input, or
 *       the normalized output could not be written.</li>
 * </ul>
 * Diagnostics go to stderr. {@code --write-normalized} writes only after every check passed, atomically.
 */
public final class VerifyBookCorpus {

    static final int OK = 0;
    static final int MISMATCH = 1;
    static final int USAGE = 2;

    static final Path DEFAULT_MANIFEST = Path.of("docs", "experiments", "book-corpus-v1", "manifest.properties");
    static final String SUPPORTED_POLICY = "v1";
    static final String README = "docs/experiments/book-corpus-v1/README.md";
    static final char BOM = 0xFEFF;

    static final List<String> REQUIRED_KEYS = List.of(
            "corpus", "corpusVersion", "normalize.policy", "normalize.startMarkerPrefix", "normalize.endMarkerPrefix");
    /** Pinned values; optional only with {@code --print-digests}, to bootstrap a new contract. */
    static final List<String> PINNED_RAW_KEYS = List.of("raw.sizeBytes", "raw.sha256");
    static final List<String> PINNED_NORMALIZED_KEYS = List.of(
            "normalized.sizeBytes", "normalized.sha256", "normalized.lineCount");
    static final List<String> INTEGER_KEYS = List.of(
            "corpusVersion", "raw.sizeBytes", "raw.lineCount", "normalized.sizeBytes", "normalized.lineCount");
    static final List<String> SHA256_KEYS = List.of("raw.sha256", "normalized.sha256");
    /** Declarative keys and the only values normalization policy v1 can guarantee for them. */
    static final Map<String, Set<String>> DECLARED_VALUES = Map.of(
            "raw.encoding", Set.of("UTF-8"),
            "raw.bom", Set.of("ABSENT"),
            "raw.eol", Set.of("CRLF", "LF"),
            "normalized.encoding", Set.of("UTF-8"),
            "normalized.bom", Set.of("ABSENT"),
            "normalized.eol", Set.of("LF"),
            "normalized.unicodeForm", Set.of("NFC"));

    private VerifyBookCorpus() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path manifestPath = null;
        Path writeNormalized = null;
        boolean printDigests = false;
        Path input = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> {
                    if (++i == args.length) {
                        return usage(err, "--manifest needs a file argument");
                    }
                    manifestPath = Path.of(args[i]);
                }
                case "--write-normalized" -> {
                    if (++i == args.length) {
                        return usage(err, "--write-normalized needs a file argument");
                    }
                    writeNormalized = Path.of(args[i]);
                }
                case "--print-digests" -> printDigests = true;
                default -> {
                    if (args[i].startsWith("--") || input != null) {
                        return usage(err, "unexpected argument: " + args[i]);
                    }
                    input = Path.of(args[i]);
                }
            }
        }
        if (input == null) {
            return usage(err, "missing INPUT (the locally supplied book file)");
        }
        if (manifestPath == null) {
            manifestPath = DEFAULT_MANIFEST;
        }

        Properties manifest = new Properties();
        if (!Files.isRegularFile(manifestPath)) {
            err.println("ERROR manifest not found: " + manifestPath.toAbsolutePath());
            err.println("  run from the repository root or pass --manifest <file>");
            return USAGE;
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            manifest.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: malformed Unicode escape in the properties file.
            err.println("ERROR cannot read manifest " + manifestPath + ": " + e.getMessage());
            return USAGE;
        }
        String problem = validateManifest(manifest, printDigests);
        if (problem != null) {
            err.println("ERROR manifest " + manifestPath + ": " + problem);
            return USAGE;
        }
        boolean rawPinned = PINNED_RAW_KEYS.stream().noneMatch(k -> value(manifest, k).isEmpty());
        boolean normalizedPinned = PINNED_NORMALIZED_KEYS.stream().noneMatch(k -> value(manifest, k).isEmpty());
        String corpus = value(manifest, "corpus");

        if (!Files.exists(input)) {
            err.println("ERROR input not found: " + input.toAbsolutePath());
            err.println("  " + corpus + " bytes are never stored in Git; download the pinned edition once,");
            err.println("  by hand, as described in " + README + " section \"Offline acquisition\".");
            return USAGE;
        }
        if (!Files.isRegularFile(input) || !Files.isReadable(input)) {
            err.println("ERROR input is not a readable regular file: " + input.toAbsolutePath());
            return USAGE;
        }
        if (writeNormalized != null && Files.exists(writeNormalized)) {
            try {
                if (Files.isSameFile(input, writeNormalized)) {
                    err.println("ERROR --write-normalized must not name the input file: " + writeNormalized.toAbsolutePath());
                    err.println("  writing there would replace the pinned raw bytes; choose another output path");
                    return USAGE;
                }
            } catch (IOException e) {
                err.println("ERROR cannot compare --write-normalized with the input: " + e.getMessage());
                return USAGE;
            }
        }

        byte[] raw;
        try {
            raw = Files.readAllBytes(input);
        } catch (IOException e) {
            err.println("ERROR cannot read input " + input + ": " + e.getMessage());
            return USAGE;
        }

        RawFacts actualRaw = RawFacts.of(raw);
        boolean rawMatches = rawPinned && actualRaw.matches(manifest);

        String normalized;
        try {
            normalized = normalize(raw, value(manifest, "normalize.startMarkerPrefix"),
                    value(manifest, "normalize.endMarkerPrefix"));
        } catch (NormalizationException e) {
            if (rawPinned && !rawMatches) {
                reportRawMismatch(err, manifest, actualRaw);
            }
            err.println("ERROR normalization policy " + SUPPORTED_POLICY + " rejected the input: " + e.getMessage());
            return MISMATCH;
        }
        byte[] normalizedBytes = normalized.getBytes(StandardCharsets.UTF_8);
        String normalizedSha = sha256(normalizedBytes);
        long lineCount = normalized.chars().filter(c -> c == '\n').count();

        if (printDigests) {
            out.println("raw.sizeBytes=" + actualRaw.size());
            out.println("raw.sha256=" + actualRaw.sha256());
            out.println("raw.lineCount=" + actualRaw.lineCount());
            out.println("raw.eol=" + actualRaw.eol());
            out.println("normalized.sizeBytes=" + normalizedBytes.length);
            out.println("normalized.sha256=" + normalizedSha);
            out.println("normalized.lineCount=" + lineCount);
            if (!rawPinned || !normalizedPinned) {
                // Bootstrapping a new contract: check what is already pinned, compare nothing else.
                if (rawPinned && !rawMatches) {
                    reportRawMismatch(err, manifest, actualRaw);
                    return MISMATCH;
                }
                err.println("INFO manifest does not pin every raw.* and normalized.* value yet;"
                        + " digests printed, unpinned values not compared");
                return OK;
            }
        }

        boolean normalizedMatches = Long.parseLong(value(manifest, "normalized.sizeBytes")) == normalizedBytes.length
                && normalizedSha.equals(value(manifest, "normalized.sha256"))
                && Long.parseLong(value(manifest, "normalized.lineCount")) == lineCount;

        if (!rawMatches) {
            reportRawMismatch(err, manifest, actualRaw);
            if (normalizedMatches) {
                err.println("  the normalized body still matches the pinned body: upstream boilerplate drift");
                err.println("  (header/footer changed). Still rejected: raw bytes are pinned on purpose.");
            } else {
                err.println("  the normalized body differs too: different edition, transcription change or a");
                err.println("  corrupted download.");
            }
            err.println("  Locate the pinned bytes or re-pin deliberately; see " + README
                    + " section \"Re-pinning\".");
            return MISMATCH;
        }
        if (!normalizedMatches) {
            err.println("ERROR raw bytes match but the normalized body does not match the manifest");
            err.println("  expected normalized size=" + value(manifest, "normalized.sizeBytes")
                    + " sha256=" + value(manifest, "normalized.sha256")
                    + " lines=" + value(manifest, "normalized.lineCount"));
            err.println("  actual   normalized size=" + normalizedBytes.length + " sha256=" + normalizedSha
                    + " lines=" + lineCount);
            err.println("  the manifest or the normalization policy is inconsistent; fix the contract, not the input.");
            return MISMATCH;
        }

        if (writeNormalized != null) {
            try {
                writeAtomically(writeNormalized, normalizedBytes);
            } catch (IOException e) {
                err.println("ERROR cannot write normalized text to " + writeNormalized + ": " + e.getMessage());
                return USAGE;
            }
        }
        out.println("OK " + corpus + " v" + value(manifest, "corpusVersion")
                + " raw sha256=" + actualRaw.sha256() + " normalized sha256=" + normalizedSha
                + " lines=" + lineCount);
        return OK;
    }

    /** Returns a description of the first manifest problem, or {@code null} when the manifest is usable. */
    static String validateManifest(Properties manifest, boolean printDigests) {
        List<String> required = new ArrayList<>(REQUIRED_KEYS);
        if (!printDigests) {
            required.addAll(PINNED_RAW_KEYS);
            required.addAll(PINNED_NORMALIZED_KEYS);
        }
        for (String key : required) {
            if (value(manifest, key).isEmpty()) {
                return "missing required key: " + key;
            }
        }
        for (String key : INTEGER_KEYS) {
            String value = value(manifest, key);
            if (!value.isEmpty() && !value.matches("[0-9]{1,18}")) {
                return "key " + key + " must be a non-negative integer, was: " + value;
            }
        }
        for (String key : SHA256_KEYS) {
            String value = value(manifest, key);
            if (!value.isEmpty() && !value.matches("[0-9a-f]{64}")) {
                return "key " + key + " must be 64 lowercase hex digits, was: " + value;
            }
        }
        String policy = value(manifest, "normalize.policy");
        if (!SUPPORTED_POLICY.equals(policy)) {
            return "normalize.policy=" + policy + " is not implemented; this verifier supports only " + SUPPORTED_POLICY;
        }
        for (var entry : DECLARED_VALUES.entrySet()) {
            String value = value(manifest, entry.getKey());
            if (!value.isEmpty() && !entry.getValue().contains(value)) {
                return "key " + entry.getKey() + "=" + value + " is not guaranteed by normalization policy "
                        + SUPPORTED_POLICY + "; allowed: " + entry.getValue();
            }
        }
        return null;
    }

    /**
     * Normalization policy v1: strict UTF-8, no BOM, CRLF or LF line ends only (no lone CR), keep the
     * lines strictly between the single start marker line and the single end marker line, trim leading
     * and trailing blank lines, require NFC, end with exactly one LF.
     */
    static String normalize(byte[] raw, String startPrefix, String endPrefix) throws NormalizationException {
        String text = decodeStrictUtf8(raw);
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            throw new NormalizationException("byte order mark (U+FEFF) at offset 0; the pinned edition has none");
        }
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].indexOf('\r') >= 0) {
                throw new NormalizationException("lone carriage return on line " + (i + 1)
                        + "; only CRLF or LF line ends are accepted");
            }
            if (lines[i].startsWith(startPrefix)) {
                if (start >= 0) {
                    throw new NormalizationException("duplicate start marker on lines " + (start + 1)
                            + " and " + (i + 1));
                }
                start = i;
            } else if (lines[i].startsWith(endPrefix)) {
                if (end >= 0) {
                    throw new NormalizationException("duplicate end marker on lines " + (end + 1)
                            + " and " + (i + 1));
                }
                end = i;
            }
        }
        if (start < 0) {
            throw new NormalizationException("start marker not found (line starting with \"" + startPrefix + "\")");
        }
        if (end < 0) {
            throw new NormalizationException("end marker not found (line starting with \"" + endPrefix + "\")");
        }
        if (end < start) {
            throw new NormalizationException("end marker (line " + (end + 1) + ") precedes start marker (line "
                    + (start + 1) + ")");
        }
        int first = start + 1;
        int last = end - 1;
        while (first <= last && lines[first].isBlank()) {
            first++;
        }
        while (last >= first && lines[last].isBlank()) {
            last--;
        }
        if (first > last) {
            throw new NormalizationException("no text between the start and end markers");
        }
        StringBuilder body = new StringBuilder();
        for (int i = first; i <= last; i++) {
            body.append(lines[i]).append('\n');
        }
        String result = body.toString();
        if (!Normalizer.isNormalized(result, Normalizer.Form.NFC)) {
            throw new NormalizationException("body is not in Unicode NFC; policy v1 verifies but never rewrites");
        }
        return result;
    }

    static String decodeStrictUtf8(byte[] raw) throws NormalizationException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(raw);
        CharBuffer out = CharBuffer.allocate(raw.length);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            throw new NormalizationException("invalid UTF-8 at byte offset " + in.position()
                    + " (" + result.length() + " byte(s)); the pinned edition is strict UTF-8");
        }
        result = decoder.flush(out);
        if (result.isError()) {
            throw new NormalizationException("invalid UTF-8 at end of input");
        }
        return out.flip().toString();
    }

    /** Size, digest and line-end facts of the raw bytes, compared with the manifest's pinned raw.* values. */
    record RawFacts(long size, String sha256, long lineCount, String eol) {

        static RawFacts of(byte[] raw) {
            long lf = 0;
            long cr = 0;
            long crlf = 0;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == '\n') {
                    lf++;
                    if (i > 0 && raw[i - 1] == '\r') {
                        crlf++;
                    }
                } else if (raw[i] == '\r') {
                    cr++;
                }
            }
            String eol;
            if (lf == 0 && cr == 0) {
                eol = "NONE";
            } else if (cr == 0) {
                eol = "LF";
            } else if (crlf == lf && crlf == cr) {
                eol = "CRLF";
            } else {
                eol = "MIXED";
            }
            return new RawFacts(raw.length, VerifyBookCorpus.sha256(raw), lf, eol);
        }

        /** Size and SHA-256 must match; line count and line ends must match when the manifest declares them. */
        boolean matches(Properties manifest) {
            String lines = value(manifest, "raw.lineCount");
            String declaredEol = value(manifest, "raw.eol");
            return Long.parseLong(value(manifest, "raw.sizeBytes")) == size
                    && sha256.equals(value(manifest, "raw.sha256"))
                    && (lines.isEmpty() || Long.parseLong(lines) == lineCount)
                    && (declaredEol.isEmpty() || declaredEol.equals(eol));
        }
    }

    private static void reportRawMismatch(PrintStream err, Properties manifest, RawFacts actual) {
        err.println("ERROR raw bytes do not match the pinned edition");
        err.println("  expected size=" + value(manifest, "raw.sizeBytes") + " sha256=" + value(manifest, "raw.sha256")
                + " lines=" + orUndeclared(value(manifest, "raw.lineCount"))
                + " eol=" + orUndeclared(value(manifest, "raw.eol")));
        err.println("  actual   size=" + actual.size() + " sha256=" + actual.sha256()
                + " lines=" + actual.lineCount() + " eol=" + actual.eol());
    }

    private static String orUndeclared(String value) {
        return value.isEmpty() ? "(undeclared)" : value;
    }

    /** Writes through a sibling temporary file so a crash never leaves a truncated output behind. */
    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, ".normalized-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String value(Properties manifest, String key) {
        return manifest.getProperty(key, "").trim();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static int usage(PrintStream err, String problem) {
        err.println("ERROR " + problem);
        err.println("usage: java docs/experiments/book-corpus-v1/VerifyBookCorpus.java"
                + " [--manifest FILE] [--write-normalized OUT] [--print-digests] INPUT");
        return USAGE;
    }

    static final class NormalizationException extends Exception {
        NormalizationException(String message) {
            super(message);
        }
    }
}
