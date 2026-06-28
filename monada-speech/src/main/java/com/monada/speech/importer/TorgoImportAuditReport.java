package com.monada.speech.importer;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;

import java.util.Map;
import java.util.Objects;

/**
 * Rich audit report produced by
 * {@link TorgoDatasetImporter#auditFrom(java.nio.file.Path, com.monada.speech.storage.SpeechSampleStore)}.
 *
 * <p>In addition to the basic counts carried by {@link TorgoDatasetImportReport},
 * this report includes:
 * <ul>
 *   <li>Grouped import counts by speaker, condition, task type, and language.</li>
 *   <li>Warning categories (see {@link WarningCategory}) with per-category
 *       occurrence counts and up to {@value TorgoAuditWarningGroup#MAX_EXAMPLES}
 *       example paths.</li>
 * </ul>
 *
 * <p>When produced by a dry-run scan ({@code store == null}),
 * {@link #importedSamples()} is always {@code 0} and the grouped maps reflect
 * what <em>would</em> have been imported.
 *
 * <p>All maps are unmodifiable and use deterministic insertion order (import /
 * scan order within each group).
 */
public record TorgoImportAuditReport(
        int discoveredAudioFiles,
        int importedSamples,
        int skippedSamples,
        boolean dryRun,
        Map<String, Integer> bySpeaker,
        Map<SpeechCondition, Integer> byCondition,
        Map<SpeechTaskType, Integer> byTaskType,
        Map<String, Integer> byLanguage,
        Map<WarningCategory, TorgoAuditWarningGroup> warningGroups
) {
    public TorgoImportAuditReport {
        if (discoveredAudioFiles < 0) {
            throw new IllegalArgumentException("discoveredAudioFiles must be non-negative");
        }
        if (importedSamples < 0) {
            throw new IllegalArgumentException("importedSamples must be non-negative");
        }
        if (skippedSamples < 0) {
            throw new IllegalArgumentException("skippedSamples must be non-negative");
        }
        Objects.requireNonNull(bySpeaker, "bySpeaker");
        Objects.requireNonNull(byCondition, "byCondition");
        Objects.requireNonNull(byTaskType, "byTaskType");
        Objects.requireNonNull(byLanguage, "byLanguage");
        Objects.requireNonNull(warningGroups, "warningGroups");
        bySpeaker = Map.copyOf(bySpeaker);
        byCondition = Map.copyOf(byCondition);
        byTaskType = Map.copyOf(byTaskType);
        byLanguage = Map.copyOf(byLanguage);
        warningGroups = Map.copyOf(warningGroups);
    }

    /** Total number of distinct warning categories observed. */
    public int warningCategoryCount() {
        return warningGroups.size();
    }

    /** Total number of skipped files across all warning categories. */
    public int totalWarningCount() {
        return warningGroups.values().stream()
                .mapToInt(TorgoAuditWarningGroup::count)
                .sum();
    }
}
