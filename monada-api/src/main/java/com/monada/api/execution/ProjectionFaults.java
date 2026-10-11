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

    /** Called after a rebuilt generation is complete, before its pointer is published. */
    default void beforePublish() throws IOException {
    }

    /** Called before an old generation is deleted after a successful publication. */
    default void beforeDeleteGeneration(java.nio.file.Path generation) throws IOException {
    }

    /** Called before each ranker query of a recall, with its top-K. */
    default void onRecallRound(int topK) {
    }
}
