package com.monada.storage.execution;

import java.util.Comparator;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One deterministic ledger diagnostic: where it happened (file, 1-based line, sequence when parseable),
 * what kind it is and a human-readable message. Producing a diagnostic never modifies the ledger.
 *
 * @param line 1-based line number inside {@code file}, or {@code 0} when the finding is not line-specific
 */
public record LedgerDiagnostic(
        Severity severity,
        LedgerDiagnosticCategory category,
        String file,
        long line,
        OptionalLong sequence,
        String message
) implements Comparable<LedgerDiagnostic> {

    public enum Severity { ERROR, WARNING, INFO }

    private static final Comparator<LedgerDiagnostic> ORDER = Comparator
            .comparingInt((LedgerDiagnostic d) -> d.severity().ordinal())
            .thenComparing(LedgerDiagnostic::file)
            .thenComparingLong(LedgerDiagnostic::line)
            .thenComparing(d -> d.category().name())
            .thenComparing(LedgerDiagnostic::message);

    public LedgerDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(message, "message");
    }

    public static LedgerDiagnostic error(LedgerDiagnosticCategory category, String file, long line,
                                         OptionalLong sequence, String message) {
        return new LedgerDiagnostic(Severity.ERROR, category, file, line, sequence, message);
    }

    public static LedgerDiagnostic warning(LedgerDiagnosticCategory category, String file, long line,
                                           OptionalLong sequence, String message) {
        return new LedgerDiagnostic(Severity.WARNING, category, file, line, sequence, message);
    }

    @Override
    public int compareTo(LedgerDiagnostic other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + category + " (" + file + (line > 0 ? ":" + line : "")
                + (sequence.isPresent() ? ", seq " + sequence.getAsLong() : "") + "): " + message;
    }
}
