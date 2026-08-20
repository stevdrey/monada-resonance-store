package com.monada.storage.audit;

import java.util.Comparator;
import java.util.Objects;

public record StorageIntegrityFinding(
        StorageIntegritySeverity severity,
        StorageIntegrityCategory category,
        String target,
        String message,
        String details
) implements Comparable<StorageIntegrityFinding> {

    private static final Comparator<StorageIntegrityFinding> COMPARATOR = Comparator
            .comparing((StorageIntegrityFinding f) -> severityRank(f.severity()))
            .thenComparing(f -> f.category().name())
            .thenComparing(StorageIntegrityFinding::target)
            .thenComparing(StorageIntegrityFinding::message)
            .thenComparing(f -> f.details() == null ? "" : f.details());

    public StorageIntegrityFinding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
    }

    public StorageIntegrityFinding(
            StorageIntegritySeverity severity,
            StorageIntegrityCategory category,
            String target,
            String message
    ) {
        this(severity, category, target, message, null);
    }

    private static int severityRank(StorageIntegritySeverity severity) {
        return switch (severity) {
            case FATAL -> 0;
            case ERROR -> 1;
            case WARNING -> 2;
            case INFO -> 3;
        };
    }

    @Override
    public int compareTo(StorageIntegrityFinding other) {
        Objects.requireNonNull(other, "other");
        return COMPARATOR.compare(this, other);
    }
}
