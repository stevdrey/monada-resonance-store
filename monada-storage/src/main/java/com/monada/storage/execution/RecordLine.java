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
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
