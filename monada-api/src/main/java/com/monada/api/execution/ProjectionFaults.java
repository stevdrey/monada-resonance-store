package com.monada.api.execution;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Test seam: lets tests fail a projection write after the ledger append, a rebuild before publication, or the
 * deletion of an old generation, and act between verification steps. Production uses {@link #NONE}.
 */
interface ProjectionFaults {
    ProjectionFaults NONE = ledgerSequence -> { };

    void beforeCommit(long ledgerSequence) throws IOException;

    /** Called after a rebuilt generation is complete, before its pointer is published. */
    default void beforePublish() throws IOException {
    }

    /**
     * Called during verification after the layout and manifest checks, before the memory's atoms and vectors are
     * read (a deterministic point to reclaim the generation concurrently).
     */
    default void afterLayoutVerified() throws IOException {
    }

    /** Called before an old generation is deleted after a successful publication or by a prune. */
    default void beforeDeleteGeneration(Path generation) throws IOException {
    }
}
