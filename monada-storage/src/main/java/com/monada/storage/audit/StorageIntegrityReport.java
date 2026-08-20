package com.monada.storage.audit;

import com.monada.storage.Manifest;
import com.monada.storage.VectorFormatProfile;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record StorageIntegrityReport(
        Path storeRoot,
        Manifest manifest,
        List<StorageIntegrityFinding> findings,
        StorageIntegrityStatistics statistics
) {

    public StorageIntegrityReport {
        Objects.requireNonNull(storeRoot, "storeRoot");
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(statistics, "statistics");
        var sorted = findings.stream().sorted().toList();
        findings = Collections.unmodifiableList(sorted);
    }

    public boolean isHealthy() {
        return findings.stream().noneMatch(f ->
                f.severity() == StorageIntegritySeverity.ERROR || f.severity() == StorageIntegritySeverity.FATAL);
    }

    public boolean hasErrorsOrFatal() {
        return !isHealthy();
    }

    public Optional<StorageIntegritySeverity> highestSeverity() {
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(findings.getFirst().severity());
    }

    public long countBySeverity(StorageIntegritySeverity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================").append(System.lineSeparator());
        sb.append("Monada Storage Integrity Report").append(System.lineSeparator());
        sb.append("================================================================================").append(System.lineSeparator());
        sb.append("Store Root:       ").append(storeRoot.toAbsolutePath().normalize()).append(System.lineSeparator());
        
        String status;
        if (findings.stream().anyMatch(f -> f.severity() == StorageIntegritySeverity.FATAL)) {
            status = "CORRUPTED (FATAL)";
        } else if (findings.stream().anyMatch(f -> f.severity() == StorageIntegritySeverity.ERROR)) {
            status = "INCONSISTENT (ERRORS)";
        } else if (findings.stream().anyMatch(f -> f.severity() == StorageIntegritySeverity.WARNING)) {
            status = "WARNINGS";
        } else {
            status = "HEALTHY";
        }
        sb.append("Status:           ").append(status).append(System.lineSeparator());
        sb.append("Highest Severity: ").append(highestSeverity().map(Enum::name).orElse("NONE")).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        sb.append("Manifest:").append(System.lineSeparator());
        if (manifest != null) {
            sb.append("  Version:          ").append(manifest.version()).append(System.lineSeparator());
            sb.append("  Dimensions:       ").append(manifest.dimensions()).append(System.lineSeparator());
            sb.append("  Atom Segment:     ").append(manifest.atomSegment()).append(System.lineSeparator());
            sb.append("  Vector Segment:   ").append(manifest.vectorSegment()).append(System.lineSeparator());
            sb.append("  Feedback Segment: ").append(manifest.feedbackSegment()).append(System.lineSeparator());
            VectorFormatProfile profile = manifest.vectorFormatProfile();
            if (profile != null) {
                sb.append("  Vector Framing:   ").append(profile.framing()).append(System.lineSeparator());
                sb.append("  Scalar Type:      ").append(profile.scalarType()).append(System.lineSeparator());
                sb.append("  Byte Order:       ").append(profile.byteOrder()).append(System.lineSeparator());
            } else {
                sb.append("  Vector Framing:   [inferred fixed-raw Float32]").append(System.lineSeparator());
            }
        } else {
            sb.append("  [Manifest missing or unreadable]").append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());

        sb.append("Statistics:").append(System.lineSeparator());
        sb.append("  Atom Physical Records:             ").append(statistics.atomLogPhysicalRecords()).append(System.lineSeparator());
        sb.append("  Active Unique Atoms:               ").append(statistics.activeUniqueAtoms()).append(System.lineSeparator());
        sb.append("  Atom History Duplicates:           ").append(statistics.atomHistoryDuplicates()).append(System.lineSeparator());
        sb.append("  Vector Index Entries:              ").append(statistics.vectorIndexEntries()).append(System.lineSeparator());
        sb.append("  Unique Vector Index IDs:           ").append(statistics.uniqueVectorIndexIds()).append(System.lineSeparator());
        sb.append("  Duplicate Vector Index IDs:        ").append(statistics.duplicateVectorIndexIds()).append(System.lineSeparator());
        sb.append("  Distinct Vector Offsets:           ").append(statistics.distinctVectorOffsets()).append(System.lineSeparator());
        sb.append("  Duplicate Vector Offsets:          ").append(statistics.duplicateVectorOffsets()).append(System.lineSeparator());
        sb.append("  Active Atoms With Vector:          ").append(statistics.activeAtomsWithVector()).append(System.lineSeparator());
        sb.append("  Active Atoms Without Vector:       ").append(statistics.activeAtomsWithoutVector()).append(System.lineSeparator());
        sb.append("  Vector Entries Without Active Atom:").append(statistics.vectorEntriesWithoutActiveAtom()).append(System.lineSeparator());
        sb.append("  Feedback Log Records:              ").append(statistics.feedbackLogRecords()).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        long fatals = countBySeverity(StorageIntegritySeverity.FATAL);
        long errors = countBySeverity(StorageIntegritySeverity.ERROR);
        long warnings = countBySeverity(StorageIntegritySeverity.WARNING);
        long infos = countBySeverity(StorageIntegritySeverity.INFO);

        sb.append("Findings (").append(findings.size()).append(" total: ")
                .append(fatals).append(" fatal, ")
                .append(errors).append(" error, ")
                .append(warnings).append(" warning, ")
                .append(infos).append(" info):").append(System.lineSeparator());

        if (findings.isEmpty()) {
            sb.append("  (no findings)").append(System.lineSeparator());
        } else {
            for (StorageIntegrityFinding finding : findings) {
                sb.append("  [").append(finding.severity()).append("] ")
                        .append(finding.category()).append(" (")
                        .append(finding.target()).append("): ")
                        .append(finding.message());
                if (finding.details() != null && !finding.details().isBlank()) {
                    sb.append(" [details: ").append(finding.details()).append("]");
                }
                sb.append(System.lineSeparator());
            }
        }
        sb.append("================================================================================").append(System.lineSeparator());
        return sb.toString();
    }
}
