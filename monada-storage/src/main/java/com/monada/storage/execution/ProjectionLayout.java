package com.monada.storage.execution;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Paths of the derived, rebuildable projection of one scope (contract v1, sections 2 and 11):
 *
 * <pre>
 * scopes/s-&lt;sha256hex(scope id)&gt;/
 *   projection/                     # published projection
 *     projection-checkpoint.log     # see {@link ProjectionCheckpoint}
 *     memory/                       # standard MonadaMemory layout (manifest 0.4)
 *   projection.staging/             # private build directory of an explicit rebuild
 *   projection.retired/             # previous projection while a rebuild swaps it out
 * </pre>
 *
 * <p>The scope directory name is derived only from the SHA-256 of the scope ID, so raw IDs are never path
 * segments, and every path is checked to stay inside the ledger root (symbolic links included). Resolving a
 * layout creates nothing.
 */
public final class ProjectionLayout {
    public static final String PROJECTION_DIR = "projection";
    public static final String STAGING_DIR = "projection.staging";
    public static final String RETIRED_DIR = "projection.retired";
    public static final String MEMORY_DIR = "memory";

    private final LedgerPaths paths;
    private final Path scopeDir;

    private ProjectionLayout(LedgerPaths paths, Path scopeDir) {
        this.paths = paths;
        this.scopeDir = scopeDir;
    }

    /** Resolves the layout of {@code scope} under an existing ledger root; never creates anything. */
    public static ProjectionLayout of(Path root, ScopeId scope) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scope, "scope");
        LedgerPaths paths = LedgerPaths.existing(root);
        return new ProjectionLayout(paths, paths.scopeDir(scope));
    }

    public Path scopeDir() {
        return scopeDir;
    }

    public Path projectionDir() throws LedgerException {
        return paths.contained(scopeDir.resolve(PROJECTION_DIR));
    }

    public Path stagingDir() throws LedgerException {
        return paths.contained(scopeDir.resolve(STAGING_DIR));
    }

    public Path retiredDir() throws LedgerException {
        return paths.contained(scopeDir.resolve(RETIRED_DIR));
    }

    /** Checkpoint file inside a projection directory ({@link #projectionDir()} or {@link #stagingDir()}). */
    public Path checkpoint(Path projectionDir) throws LedgerException {
        return paths.contained(projectionDir.resolve(ProjectionCheckpoint.FILE_NAME));
    }

    /** MonadaMemory directory inside a projection directory. */
    public Path memory(Path projectionDir) throws LedgerException {
        return paths.contained(projectionDir.resolve(MEMORY_DIR));
    }
}
