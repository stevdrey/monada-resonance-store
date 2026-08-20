package com.monada.storage.audit;

public record StorageIntegrityStatistics(
        long atomLogPhysicalRecords,
        long activeUniqueAtoms,
        long atomHistoryDuplicates,
        long vectorIndexEntries,
        long uniqueVectorIndexIds,
        long duplicateVectorIndexIds,
        long distinctVectorOffsets,
        long duplicateVectorOffsets,
        long activeAtomsWithVector,
        long activeAtomsWithoutVector,
        long vectorEntriesWithoutActiveAtom,
        long feedbackLogRecords
) {
    public static StorageIntegrityStatistics empty() {
        return new StorageIntegrityStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
