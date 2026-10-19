package com.monada.evaluation.book;

import java.util.Locale;

/**
 * Deterministic text report of a book Store readback run: no paths, timings or timestamps, so two runs over the same
 * chunk files and Store state print the same bytes.
 */
public final class BookStoreReadbackReport {

    private BookStoreReadbackReport() {
    }

    public static String render(BookStoreReadback.Seed seed) {
        StringBuilder out = new StringBuilder();
        out.append("Book Store readback (seed)\n");
        out.append("  import kind:                   ").append(seed.importKind()).append('\n');
        out.append("  atoms before / after:          ").append(seed.before().uniqueAtoms()).append(" / ")
                .append(seed.after().uniqueAtoms()).append('\n');
        out.append("  accepted new unique atoms:     ").append(seed.acceptedNewAtoms()).append('\n');
        out.append("  atom log records before/after: ").append(seed.before().physicalRecords()).append(" / ")
                .append(seed.after().physicalRecords()).append('\n');
        out.append("  store bytes unchanged:         ").append(yesNo(seed.storeBytesUnchanged()));
        if (seed.importKind() == BookStoreReadback.ImportKind.REPEAT) {
            out.append(seed.storeBytesUnchanged() ? " (a repeat import appends nothing and reclaims nothing)"
                    : " (FAIL: a repeat import must not change the Store)");
        } else if (seed.importKind() == BookStoreReadback.ImportKind.INCOMPATIBLE) {
            out.append(" (the existing Store has another manifest version or encoding profile; nothing opened or"
                    + " appended, seed a new work directory)");
        } else if (seed.importKind() == BookStoreReadback.ImportKind.DAMAGED) {
            out.append(" (the existing Store is not HEALTHY; nothing appended, seed a new work directory)");
        }
        out.append('\n');
        out.append("  fingerprint before:            ").append(seed.before().fingerprint()).append('\n');
        out.append('\n');
        appendVerification(out, "after reopen (fresh instances)", seed.verification());
        out.append("Result: ").append(seed.passed() ? "PASS" : "FAIL").append('\n');
        return out.toString();
    }

    public static String render(BookStoreReadback.Verification verification) {
        StringBuilder out = new StringBuilder();
        appendVerification(out, "verify (read-only)", verification);
        out.append("Result: ").append(verification.passed() ? "PASS" : "FAIL").append('\n');
        return out.toString();
    }

    private static void appendVerification(StringBuilder out, String title, BookStoreReadback.Verification v) {
        BookChunkFiles.Loaded chunks = v.chunks();
        BookStoreReadback.Audit audit = v.audit();
        out.append("Book Store readback, ").append(title).append('\n');
        out.append("  corpus:                        ").append(chunks.corpus()).append(" v")
                .append(chunks.corpusVersion()).append('\n');
        out.append("  source sha256:                 ").append(chunks.sourceSha256()).append('\n');
        out.append("  chunking policy:               ").append(chunks.policyId()).append('\n');
        out.append("  chunks file sha256:            ").append(chunks.chunksFileSha256()).append('\n');
        out.append("  attempted chunks:              ").append(v.attemptedChunks()).append('\n');
        out.append("  duplicate-content chunks:      ").append(v.duplicateContentChunks()).append('\n');
        out.append("  expected unique atoms:         ").append(v.expectedUniqueAtoms()).append('\n');
        if (v.inventoryError() != null) {
            out.append("  inventory:                     UNREADABLE (").append(v.inventoryError()).append(")\n");
        }
        out.append("  persisted unique atoms:        ").append(v.inventory().uniqueAtoms()).append('\n');
        out.append("  atom log records:              ").append(v.inventory().physicalRecords()).append('\n');
        out.append("  exact readback (unique):       ").append(v.exactReadback()).append(" / ")
                .append(v.expectedUniqueAtoms()).append('\n');
        out.append("  chunks resolved exactly:       ").append(v.resolvedChunks()).append(" / ")
                .append(v.attemptedChunks()).append('\n');
        out.append("  unexpected atoms:              ").append(v.unexpectedAtoms()).append('\n');
        out.append("  failed hashes:                 ").append(v.failedHashes().isEmpty() ? "none"
                : String.join(", ", v.failedHashes())).append('\n');
        out.append("  inventory fingerprint:         ").append(v.inventory().fingerprint()).append('\n');
        out.append("  integrity audit:               ").append(audit.status())
                .append(String.format(Locale.ROOT, " (info=%d, warning=%d, error=%d, fatal=%d)", audit.info(),
                        audit.warnings(), audit.errors(), audit.fatal())).append('\n');
        out.append("  logs line-terminated:          ").append(yesNo(v.logsTerminated())).append('\n');
        out.append("  verification wrote nothing:    ").append(yesNo(v.storeUnchanged())).append('\n');
        out.append("  self-recall@1 (exploratory):   ");
        if (v.selfRecallHits().isPresent()) {
            out.append(v.selfRecallHits().getAsInt()).append(" / ").append(v.expectedUniqueAtoms())
                    .append(" (ranking only; not readback evidence)\n");
        } else {
            out.append("SKIPPED (inventory incomplete, audit not HEALTHY, or the Store could not be opened)\n");
        }
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
