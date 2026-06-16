package com.monada.speech.importer;

import java.util.List;
import java.util.Objects;

public record TorgoDatasetImportReport(
        int discoveredAudioFiles,
        int importedSamples,
        int skippedSamples,
        List<TorgoDatasetImportWarning> warnings
) {
    public TorgoDatasetImportReport {
        if (discoveredAudioFiles < 0) {
            throw new IllegalArgumentException("discoveredAudioFiles must be non-negative");
        }
        if (importedSamples < 0) {
            throw new IllegalArgumentException("importedSamples must be non-negative");
        }
        if (skippedSamples < 0) {
            throw new IllegalArgumentException("skippedSamples must be non-negative");
        }
        Objects.requireNonNull(warnings, "warnings");
        warnings = List.copyOf(warnings);
    }
}
