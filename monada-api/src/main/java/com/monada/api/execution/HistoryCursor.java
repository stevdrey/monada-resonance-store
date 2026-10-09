package com.monada.api.execution;

import com.monada.core.execution.ScopeId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Opaque, stateless history position: format version, scope, the high-watermark fixed by the first page and
 * the last returned sequence. Hosts treat {@link #token()} as an opaque string.
 */
public record HistoryCursor(ScopeId scope, long highWatermark, long lastSequence) {
    private static final String VERSION = "h1";

    public HistoryCursor {
        Objects.requireNonNull(scope, "scope");
        if (highWatermark < 0 || lastSequence < 0 || lastSequence > highWatermark) {
            throw new IllegalArgumentException("invalid history cursor positions");
        }
    }

    public String token() {
        String raw = VERSION + "|" + highWatermark + "|" + lastSequence + "|" + scope.value();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException when the token is malformed or of an unsupported version */
    public static HistoryCursor parse(String token) {
        Objects.requireNonNull(token, "token");
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("unsupported history cursor");
            }
            return new HistoryCursor(ScopeId.of(parts[3]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed history cursor", e);
        }
    }
}
