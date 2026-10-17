package com.monada.storage.execution;

import com.monada.core.execution.ExecutionLimits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Framing of one ledger line: {@code MXL1 TAB sequence TAB payloadBytes TAB sha256hex TAB payload LF}. */
final class RecordLine {
    static final String MAGIC = "MXL1";
    static final int MAX_LINE_BYTES = ExecutionLimits.MAX_RECORD_BYTES;

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    // MessageDigest.getInstance is a provider lookup; one digest per thread is reused (digest() resets it).
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(RecordLine::newSha256);

    private RecordLine() {
    }

    static byte[] frame(long sequence, byte[] payload) {
        byte[] head = (MAGIC + "\t" + sequence + "\t" + payload.length + "\t" + sha256Hex(payload) + "\t")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] line = new byte[head.length + payload.length + 1];
        System.arraycopy(head, 0, line, 0, head.length);
        System.arraycopy(payload, 0, line, head.length, payload.length);
        line[line.length - 1] = '\n';
        return line;
    }

    static String sha256Hex(byte[] bytes) {
        return HEX.formatHex(SHA256.get().digest(bytes));
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /**
     * Whether {@code hex[hexFrom, hexTo)} equals {@code sha256Hex} of {@code bytes[from, from + length)}, compared
     * without building strings: exactly 64 lowercase hex characters.
     */
    static boolean sha256HexEquals(MessageDigest sha256, byte[] bytes, int from, int length, byte[] hex,
                                   int hexFrom, int hexTo) {
        if (hexTo - hexFrom != 64) {
            return false;
        }
        sha256.update(bytes, from, length);
        byte[] digest = sha256.digest();
        for (int i = 0; i < digest.length; i++) {
            if (hex[hexFrom + 2 * i] != HEX_DIGITS[(digest[i] >> 4) & 0xF]
                    || hex[hexFrom + 2 * i + 1] != HEX_DIGITS[digest[i] & 0xF]) {
                return false;
            }
        }
        return true;
    }
}
