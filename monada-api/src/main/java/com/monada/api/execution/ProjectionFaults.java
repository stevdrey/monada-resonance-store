package com.monada.api.execution;

import java.io.IOException;

/**
 * Test seam: lets tests fail a projection write after the ledger append, or change files right before a
 * read-only recall query. Production uses {@link #NONE}.
 */
interface ProjectionFaults {
    ProjectionFaults NONE = ledgerSequence -> { };

    void beforeCommit(long ledgerSequence) throws IOException;

    default void beforeReadOnlyQuery(int attempt) {
    }
}
