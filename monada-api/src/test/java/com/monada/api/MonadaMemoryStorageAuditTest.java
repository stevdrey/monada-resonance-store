package com.monada.api;

import com.monada.core.KnowledgeAtom;
import com.monada.storage.audit.StorageIntegrityAuditor;
import com.monada.storage.audit.StorageIntegrityFinding;
import com.monada.storage.audit.StorageIntegrityReport;
import com.monada.storage.audit.StorageIntegritySeverity;
import com.monada.storage.feedback.FeedbackSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonadaMemoryStorageAuditTest {

    @TempDir
    Path root;

    @Test
    void auditsLiveMonadaMemoryStoreWithHistoryAndFeedback() {
        MonadaMemory memory = MonadaMemory.open(root);

        KnowledgeAtom atom1 = memory.remember("deterministic storage memory");
        KnowledgeAtom atom2 = memory.remember("approximate resonance retrieval", List.of("resonance recall"));

        // Update atom1 with a new alias -> produces valid append history in atom log and vector segment/index
        KnowledgeAtom atom1Updated = memory.remember("deterministic storage memory", List.of("local memory"));

        memory.feedback("deterministic storage", atom1.id(), FeedbackSignal.POSITIVE);

        StorageIntegrityAuditor auditor = new StorageIntegrityAuditor();
        StorageIntegrityReport report = auditor.audit(root);

        assertTrue(report.isHealthy());
        assertFalse(report.hasErrorsOrFatal());
        assertEquals(0, report.countBySeverity(StorageIntegritySeverity.FATAL));
        assertEquals(0, report.countBySeverity(StorageIntegritySeverity.ERROR));

        assertEquals(3, report.statistics().atomLogPhysicalRecords());
        assertEquals(2, report.statistics().activeUniqueAtoms());
        assertEquals(1, report.statistics().atomHistoryDuplicates());
        assertEquals(3, report.statistics().vectorIndexEntries());
        assertEquals(2, report.statistics().uniqueVectorIndexIds());
        assertEquals(1, report.statistics().duplicateVectorIndexIds());
        assertEquals(3, report.statistics().distinctVectorOffsets());
        assertEquals(0, report.statistics().duplicateVectorOffsets());
        assertEquals(2, report.statistics().activeAtomsWithVector());
        assertEquals(0, report.statistics().activeAtomsWithoutVector());
        assertEquals(0, report.statistics().vectorEntriesWithoutActiveAtom());
        assertEquals(1, report.statistics().feedbackLogRecords());

        // Warning finding for duplicate index ID (due to atom update) and INFO for atom history
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.WARNING && f.message().contains("Duplicate vector index entry")));
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == StorageIntegritySeverity.INFO && f.message().contains("historical update records")));

        String rendered = report.render();
        assertTrue(rendered.contains("Status:           WARNINGS"));
        assertTrue(rendered.contains("Active Unique Atoms:               2"));
    }
}
