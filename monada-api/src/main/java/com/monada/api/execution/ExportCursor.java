package com.monada.api.execution;

import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Opaque, stateless export position: format version, scope, the resolved cutoff sequence fixed by the first page,
 * the ledger sequence at which the last returned execution started, the view, the caller gates and the digest of
 * the ledger prefix the export reads ({@link ExperienceSamplePage.LedgerCheckpoint#prefixDigest}). The digest binds
 * the cursor to that exact history: resumed against another ledger (or a replaced one) it is rejected instead of
 * returning a page of a different snapshot. Because the options travel inside the token, a continuation cannot silently drift to other options. Nothing is persisted:
 * the same cursor always yields the same page, whatever was appended meanwhile. Hosts treat {@link #token()} as
 * an opaque string.
 */
public record ExportCursor(ScopeId scope, long cutoffSequence, long afterStartSequence, ExportView view,
                           Set<QualityDimension> additionalGates, String ledgerDigest) {
    private static final String VERSION = "x2";
    private static final java.util.regex.Pattern HEX64 = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    public ExportCursor {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(additionalGates, "additionalGates");
        Objects.requireNonNull(ledgerDigest, "ledgerDigest");
        if (!HEX64.matcher(ledgerDigest).matches()) {
            throw new IllegalArgumentException("ledgerDigest must be a lower-case SHA-256 hex string");
        }
        if (cutoffSequence < 0 || afterStartSequence < 0 || afterStartSequence > cutoffSequence) {
            throw new IllegalArgumentException("invalid export cursor positions");
        }
        additionalGates = additionalGates.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(additionalGates));
    }

    public String token() {
        String gates = additionalGates.isEmpty() ? "-" : EnumSet.copyOf(additionalGates).stream()
                .map(Enum::name).collect(Collectors.joining(","));
        String raw = String.join("|", VERSION, Long.toString(cutoffSequence), Long.toString(afterStartSequence),
                view.name(), gates, ledgerDigest, scope.value());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException when the token is malformed, non-canonical or of an unsupported version */
    public static ExportCursor parse(String token) {
        Objects.requireNonNull(token, "token");
        try {
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(token))).toString();
            String[] parts = raw.split("\\|", 7);
            if (parts.length != 7 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("unsupported export cursor");
            }
            EnumSet<QualityDimension> gates = EnumSet.noneOf(QualityDimension.class);
            if (!parts[4].equals("-")) {
                for (String name : parts[4].split(",", -1)) {
                    gates.add(QualityDimension.valueOf(name));
                }
            }
            ExportCursor cursor = new ExportCursor(ScopeId.of(parts[6]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), ExportView.valueOf(parts[3]), gates, parts[5]);
            if (!cursor.token().equals(token)) {
                throw new IllegalArgumentException("non-canonical export cursor");
            }
            return cursor;
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw new IllegalArgumentException("malformed export cursor", e);
        }
    }
}
