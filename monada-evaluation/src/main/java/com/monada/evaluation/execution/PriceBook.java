package com.monada.evaluation.execution;

import com.monada.core.execution.PriceSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Caller-supplied, versioned price snapshots. Nothing is looked up live. A stage is priced by the snapshot of
 * its provider and model with the latest effective date on or before the (UTC) date the stage ended; two
 * different snapshots sharing that date make the match ambiguous instead of picking one silently.
 */
public final class PriceBook {

    public enum MatchStatus { FOUND, NONE, AMBIGUOUS }

    public record Match(MatchStatus status, Optional<PriceSnapshot> snapshot) {
        public Match {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(snapshot, "snapshot");
            if ((status == MatchStatus.FOUND) != snapshot.isPresent()) {
                throw new IllegalArgumentException("a snapshot is present exactly when the match is FOUND");
            }
        }
    }

    private static final PriceBook EMPTY = new PriceBook(List.of());

    private final List<PriceSnapshot> snapshots;

    private PriceBook(List<PriceSnapshot> snapshots) {
        this.snapshots = snapshots;
    }

    public static PriceBook empty() {
        return EMPTY;
    }

    /** Rejects a null list and duplicate {@code (id, version)} pairs. */
    public static PriceBook of(List<PriceSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        List<PriceSnapshot> copy = List.copyOf(snapshots);
        Set<String> seen = new HashSet<>();
        for (PriceSnapshot s : copy) {
            if (!seen.add(s.id() + '\u0000' + s.version())) {
                throw new IllegalArgumentException("duplicate price snapshot " + s.id() + " version " + s.version());
            }
        }
        return new PriceBook(copy);
    }

    public List<PriceSnapshot> snapshots() {
        return snapshots;
    }

    public Match lookup(Optional<String> provider, Optional<String> model, Instant at) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(at, "at");
        if (provider.isEmpty() || model.isEmpty()) {
            return new Match(MatchStatus.NONE, Optional.empty());
        }
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        List<PriceSnapshot> best = new ArrayList<>();
        for (PriceSnapshot s : snapshots) {
            if (!s.provider().equals(provider.get()) || !s.model().equals(model.get())
                    || s.effectiveDate().isAfter(day)) {
                continue;
            }
            if (best.isEmpty() || s.effectiveDate().isAfter(best.get(0).effectiveDate())) {
                best.clear();
                best.add(s);
            } else if (s.effectiveDate().equals(best.get(0).effectiveDate())) {
                best.add(s);
            }
        }
        if (best.isEmpty()) {
            return new Match(MatchStatus.NONE, Optional.empty());
        }
        return best.size() == 1 ? new Match(MatchStatus.FOUND, Optional.of(best.get(0)))
                : new Match(MatchStatus.AMBIGUOUS, Optional.empty());
    }
}
