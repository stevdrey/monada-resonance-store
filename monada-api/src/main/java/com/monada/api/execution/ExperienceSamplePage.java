package com.monada.api.execution;

import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One bounded page of {@link ExperienceSample}s in ascending order of the ledger sequence at which each
 * execution started. Everything in a page is a function of the scope, the resolved cutoff sequence, the view and
 * the gates, so repeating the request (also after reopening the ledger, or after later appends) yields a
 * byte-identical {@link ExperienceSampleJson#toJson canonical rendering}.
 *
 * <p>{@code ledger} identifies the exact ledger prefix the samples were derived from. {@code projection} names
 * the projection format that serves recall for the scope (its live state is informational and outside the canonical rendering); samples are derived from the ledger alone, never from
 * the projection, and the export never writes it. {@code next} is present exactly when {@code hasMore}.
 */
public record ExperienceSamplePage(String schema, ScopeId scope, ExportView view,
                                   List<QualityDimension> additionalGates, LedgerCheckpoint ledger,
                                   ProjectionStamp projection, List<ExperienceSample> samples, boolean hasMore,
                                   Optional<ExportCursor> next) {
    public static final String SCHEMA = "execution-sample/1";

    /**
     * {@code prefixDigest}: SHA-256 (hex) over the lines {@code <sequence>:<ledger payload digest> LF} for
     * sequences {@code 1..cutoffSequence}, where the payload digest is the one the ledger line carries.
     */
    public record LedgerCheckpoint(long cutoffSequence, String prefixDigest) {
        public LedgerCheckpoint {
            Objects.requireNonNull(prefixDigest, "prefixDigest");
        }
    }

    /**
     * {@code formatVersion} and {@code textScheme} are fixed versions and part of the canonical rendering.
     * {@code state} and {@code coversCutoff} (the projection has projected every ledger event up to the cutoff)
     * are the projection's <b>live</b> status when the page was read: they change when the projection is rebuilt,
     * so they are deliberately <b>not</b> part of the canonical JSON or its digest.
     */
    public record ProjectionStamp(int formatVersion, String textScheme, ProjectionStatus.State state,
                                  boolean coversCutoff) {
        public ProjectionStamp {
            Objects.requireNonNull(textScheme, "textScheme");
            Objects.requireNonNull(state, "state");
        }
    }

    public ExperienceSamplePage {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(next, "next");
        additionalGates = List.copyOf(additionalGates);
        samples = List.copyOf(samples);
        if (hasMore != next.isPresent()) {
            throw new IllegalArgumentException("next cursor is present exactly when hasMore");
        }
    }

    /** Canonical rendering digest, see {@link ExperienceSampleJson#digest}. */
    public String digest() {
        return ExperienceSampleJson.digest(this);
    }
}
