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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * Offline verifier for the {@code book-corpus-v1} input contract (issue #163).
 *
 * <p>Run from the repository root with the JDK source launcher; JDK only, no network access:
 * <pre>
 * java docs/experiments/book-corpus-v1/VerifyBookCorpus.java [--manifest FILE] [--write-normalized OUT] [--print-digests] INPUT
 * </pre>
 *
 * <p>Exit codes: {@code 0} the input matches the pinned edition, {@code 1} the bytes are missing a
 * requirement or do not match, {@code 2} usage error, missing input or unreadable manifest.
 * Diagnostics go to stderr. {@code --write-normalized} writes only after every check passed.
 */
public final class VerifyBookCorpus {

    static final int OK = 0;
    static final int MISMATCH = 1;
    static final int USAGE = 2;

    static final Path DEFAULT_MANIFEST = Path.of("docs", "experiments", "book-corpus-v1", "manifest.properties");
    static final String SUPPORTED_POLICY = "v1";
    static final String README = "docs/experiments/book-corpus-v1/README.md";

    static final List<String> REQUIRED_KEYS = List.of(
            "corpus", "corpusVersion", "raw.sizeBytes", "raw.sha256",
            "normalize.policy", "normalize.startMarkerPrefix", "normalize.endMarkerPrefix");
    static final List<String> NORMALIZED_KEYS = List.of(
            "normalized.sizeBytes", "normalized.sha256", "normalized.lineCount");

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
        List<String> required = new ArrayList<>(REQUIRED_KEYS);
        if (!printDigests) {
            required.addAll(NORMALIZED_KEYS);
        }
        for (String key : required) {
            if (manifest.getProperty(key, "").isBlank()) {
                err.println("ERROR manifest " + manifestPath + " is missing required key: " + key);
                return USAGE;
            }
        }
        for (String key : List.of("raw.sizeBytes", "normalized.sizeBytes", "normalized.lineCount")) {
            String value = manifest.getProperty(key, "").trim();
            if (!value.isEmpty() && !value.matches("[0-9]{1,18}")) {
                err.println("ERROR manifest key " + key + " must be a non-negative integer, was: " + value);
                return USAGE;
            }
        }
        for (String key : List.of("raw.sha256", "normalized.sha256")) {
            String value = manifest.getProperty(key, "").trim();
            if (!value.isEmpty() && !value.matches("[0-9a-f]{64}")) {
                err.println("ERROR manifest key " + key + " must be 64 lowercase hex digits, was: " + value);
                return USAGE;
            }
        }
        String policy = manifest.getProperty("normalize.policy").trim();
        if (!SUPPORTED_POLICY.equals(policy)) {
            err.println("ERROR manifest normalize.policy=" + policy + " is not implemented; this verifier supports only "
                    + SUPPORTED_POLICY);
            return USAGE;
        }
        boolean bootstrap = NORMALIZED_KEYS.stream().anyMatch(k -> manifest.getProperty(k, "").isBlank());
        String corpus = manifest.getProperty("corpus");

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

        long expectedRawSize = Long.parseLong(manifest.getProperty("raw.sizeBytes").trim());
        String expectedRawSha = manifest.getProperty("raw.sha256").trim();
        String rawSha = sha256(raw);
        boolean rawMatches = raw.length == expectedRawSize && rawSha.equals(expectedRawSha);

        String normalized;
        try {
            normalized = normalize(raw, manifest.getProperty("normalize.startMarkerPrefix"),
                    manifest.getProperty("normalize.endMarkerPrefix"));
        } catch (NormalizationException e) {
            if (!rawMatches) {
                reportRawMismatch(err, expectedRawSize, expectedRawSha, raw.length, rawSha);
            }
            err.println("ERROR normalization policy " + manifest.getProperty("normalize.policy")
                    + " rejected the input: " + e.getMessage());
            return MISMATCH;
        }
        byte[] normalizedBytes = normalized.getBytes(StandardCharsets.UTF_8);
        String normalizedSha = sha256(normalizedBytes);
        long lineCount = normalized.chars().filter(c -> c == '\n').count();

        if (printDigests) {
            out.println("raw.sizeBytes=" + raw.length);
            out.println("raw.sha256=" + rawSha);
            out.println("normalized.sizeBytes=" + normalizedBytes.length);
            out.println("normalized.sha256=" + normalizedSha);
            out.println("normalized.lineCount=" + lineCount);
            if (bootstrap) {
                // Only reachable with --print-digests: pinning a new contract, nothing to compare yet.
                if (!rawMatches) {
                    reportRawMismatch(err, expectedRawSize, expectedRawSha, raw.length, rawSha);
                    return MISMATCH;
                }
                err.println("INFO manifest has no normalized.* expectations yet; digests printed, nothing compared");
                return OK;
            }
        }

        boolean normalizedMatches = matches(manifest, "normalized.sizeBytes", normalizedBytes.length)
                && normalizedSha.equals(manifest.getProperty("normalized.sha256", "").trim())
                && matches(manifest, "normalized.lineCount", lineCount);

        if (!rawMatches) {
            reportRawMismatch(err, expectedRawSize, expectedRawSha, raw.length, rawSha);
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
            err.println("  expected normalized size=" + manifest.getProperty("normalized.sizeBytes")
                    + " sha256=" + manifest.getProperty("normalized.sha256")
                    + " lines=" + manifest.getProperty("normalized.lineCount"));
            err.println("  actual   normalized size=" + normalizedBytes.length + " sha256=" + normalizedSha
                    + " lines=" + lineCount);
            err.println("  the manifest or the normalization policy is inconsistent; fix the contract, not the input.");
            return MISMATCH;
        }

        if (writeNormalized != null) {
            try {
                Files.write(writeNormalized, normalizedBytes);
            } catch (IOException e) {
                err.println("ERROR cannot write normalized text to " + writeNormalized + ": " + e.getMessage());
                return USAGE;
            }
        }
        out.println("OK " + corpus + " v" + manifest.getProperty("corpusVersion")
                + " raw sha256=" + rawSha + " normalized sha256=" + normalizedSha
                + " lines=" + lineCount);
        return OK;
    }

    /**
     * Normalization policy v1: strict UTF-8, no BOM, CRLF or LF line ends only (no lone CR), keep the
     * lines strictly between the single start marker line and the single end marker line, trim leading
     * and trailing blank lines, require NFC, end with exactly one LF.
     */
    static String normalize(byte[] raw, String startPrefix, String endPrefix) throws NormalizationException {
        String text = decodeStrictUtf8(raw);
        if (text.startsWith("﻿")) {
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

    private static boolean matches(Properties manifest, String key, long actual) {
        String value = manifest.getProperty(key, "").trim();
        return !value.isEmpty() && Long.parseLong(value) == actual;
    }

    private static void reportRawMismatch(PrintStream err, long expectedSize, String expectedSha,
                                          long actualSize, String actualSha) {
        err.println("ERROR raw bytes do not match the pinned edition");
        err.println("  expected size=" + expectedSize + " sha256=" + expectedSha);
        err.println("  actual   size=" + actualSize + " sha256=" + actualSha);
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
