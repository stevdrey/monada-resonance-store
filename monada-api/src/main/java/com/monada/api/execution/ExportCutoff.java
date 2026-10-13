package com.monada.api.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Upper bound of the ledger prefix a sample export reads (contract v1, section 12). Events after the cutoff are
 * invisible: a correction recorded later never changes a sample exported as of an earlier cutoff.
 *
 * <p>{@link AtSequence} is the reproducible choice: the same sequence always names the same prefix.
 * {@link RecordedUpTo} resolves to the longest ledger prefix whose events were all recorded at or before the
 * instant (it stops at the first event recorded later). It names a stable prefix only while the ledger holds an
 * event beyond it, so hosts that need repeatable exports pin the {@code cutoffSequence} the first page reports.
 */
public sealed interface ExportCutoff {

    /** Everything recorded when the first page is read. */
    static ExportCutoff latest() {
        return Latest.INSTANCE;
    }

    static ExportCutoff atSequence(long sequence) {
        return new AtSequence(sequence);
    }

    static ExportCutoff recordedUpTo(Instant recordedAt) {
        return new RecordedUpTo(recordedAt);
    }

    enum Latest implements ExportCutoff { INSTANCE }

    record AtSequence(long sequence) implements ExportCutoff {
        public AtSequence {
            if (sequence < 0) {
                throw new IllegalArgumentException("cutoff sequence must be >= 0");
            }
        }
    }

    record RecordedUpTo(Instant recordedAt) implements ExportCutoff {
        public RecordedUpTo {
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }
}
