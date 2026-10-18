package com.monada.evaluation.book;

import com.monada.evaluation.book.BookInputException.Reason;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * A normalized book body that passed every input check: its size and SHA-256 equal the manifest's
 * {@code normalized.*} pins, it is strict UTF-8 without BOM, uses LF line ends only (and ends with one), contains no
 * other control characters than LF and TAB, is NFC, and is not blank. Only the normalized body produced by
 * {@code VerifyBookCorpus.java --write-normalized} is accepted; the source wrapper is never stripped here.
 */
public final class VerifiedBookSource {
    private static final char BOM = 0xFEFF;

    private final String corpus;
    private final String corpusVersion;
    private final String manifestSha256;
    private final long sizeBytes;
    private final String sha256;
    private final String text;

    private VerifiedBookSource(String corpus, String corpusVersion, String manifestSha256, long sizeBytes,
                               String sha256, String text) {
        this.corpus = corpus;
        this.corpusVersion = corpusVersion;
        this.manifestSha256 = manifestSha256;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.text = text;
    }

    /**
     * Reads {@code manifest} and {@code body} and verifies the body against the manifest's normalized pins.
     *
     * @throws NoSuchFileException if either file does not exist
     */
    public static VerifiedBookSource load(Path manifest, Path body) throws IOException, BookInputException {
        byte[] manifestBytes = Files.readAllBytes(manifest);
        Properties props = new Properties();
        try {
            props.load(new StringReader(decode(manifestBytes, "manifest")));
        } catch (IllegalArgumentException e) {
            throw new BookInputException(Reason.BAD_MANIFEST, "malformed manifest: " + e.getMessage());
        } catch (BookInputException e) {
            throw new BookInputException(Reason.BAD_MANIFEST, e.getMessage());
        }
        String corpus = required(props, "corpus");
        String corpusVersion = required(props, "corpusVersion");
        String expectedSha = required(props, "normalized.sha256");
        String expectedSize = required(props, "normalized.sizeBytes");
        if (!expectedSha.matches("[0-9a-f]{64}")) {
            throw new BookInputException(Reason.BAD_MANIFEST, "normalized.sha256 is not 64 lowercase hex digits");
        }
        long size;
        try {
            size = Long.parseLong(expectedSize);
        } catch (NumberFormatException e) {
            size = -1;
        }
        if (size < 1) {
            throw new BookInputException(Reason.BAD_MANIFEST, "normalized.sizeBytes is not a positive integer");
        }

        byte[] bodyBytes = Files.readAllBytes(body);
        String actualSha = sha256(bodyBytes);
        if (bodyBytes.length != size || !actualSha.equals(expectedSha)) {
            throw new BookInputException(Reason.INVALID_INPUT, "body does not match the manifest's normalized pins:"
                    + " expected " + size + " bytes sha256 " + expectedSha + ", actual " + bodyBytes.length
                    + " bytes sha256 " + actualSha
                    + " (pass the output of VerifyBookCorpus.java --write-normalized, not the raw download)");
        }
        return ofBytes(corpus, corpusVersion, sha256(manifestBytes), bodyBytes);
    }

    /**
     * Applies every content check to {@code body} without a manifest digest comparison (used by {@link #load} and by
     * tests that build bodies in memory).
     */
    public static VerifiedBookSource ofBytes(String corpus, String corpusVersion, String manifestSha256, byte[] body)
            throws BookInputException {
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        if (corpus == null || !corpus.matches("[a-z0-9][a-z0-9.-]*")) {
            throw new BookInputException(Reason.BAD_MANIFEST, "corpus must match [a-z0-9][a-z0-9.-]*: " + corpus);
        }
        if (corpusVersion == null || !corpusVersion.matches("[0-9A-Za-z.-]+")) {
            throw new BookInputException(Reason.BAD_MANIFEST, "corpusVersion is malformed: " + corpusVersion);
        }
        if (body.length == 0) {
            throw new BookInputException(Reason.INVALID_INPUT, "body is empty");
        }
        String text = decode(body, "body");
        if (text.charAt(0) == BOM) {
            throw new BookInputException(Reason.INVALID_INPUT, "body starts with a BOM (U+FEFF)");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                throw new BookInputException(Reason.INVALID_INPUT, "body contains CR at char index " + i
                        + "; only LF line ends are accepted");
            }
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                throw new BookInputException(Reason.INVALID_INPUT, String.format(Locale.ROOT,
                        "body contains control character U+%04X at char index %d", (int) c, i));
            }
        }
        if (text.charAt(text.length() - 1) != '\n') {
            throw new BookInputException(Reason.INVALID_INPUT, "body does not end with LF");
        }
        if (text.codePoints().allMatch(BookChunker::isSpace)) {
            throw new BookInputException(Reason.INVALID_INPUT, "body contains only whitespace");
        }
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw new BookInputException(Reason.INVALID_INPUT, "body is not Unicode NFC");
        }
        return new VerifiedBookSource(corpus, corpusVersion, manifestSha256, body.length, sha256(body), text);
    }

    private static String required(Properties props, String key) throws BookInputException {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new BookInputException(Reason.BAD_MANIFEST, "manifest is missing " + key);
        }
        return value.strip();
    }

    private static String decode(byte[] bytes, String what) throws BookInputException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            throw new BookInputException(Reason.INVALID_INPUT, what + " is not valid UTF-8 at byte offset "
                    + in.position());
        }
        result = decoder.flush(out);
        if (result.isError()) {
            throw new BookInputException(Reason.INVALID_INPUT, what + " is not valid UTF-8 at byte offset "
                    + in.position());
        }
        return out.flip().toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    public String corpus() {
        return corpus;
    }

    public String corpusVersion() {
        return corpusVersion;
    }

    /** SHA-256 of the manifest file bytes the body was verified against. */
    public String manifestSha256() {
        return manifestSha256;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String sha256() {
        return sha256;
    }

    public String text() {
        return text;
    }
}
