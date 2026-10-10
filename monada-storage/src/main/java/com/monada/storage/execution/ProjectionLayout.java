package com.monada.storage.execution;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
    /** Directories of the fixed MonadaMemory layout a projection creates (manifest 0.4 defaults). */
    public static final List<String> MEMORY_DIRS = List.of("atoms", "vectors", "indexes", "feedback");
    /** Files of the fixed MonadaMemory layout a projection creates (manifest 0.4 defaults). */
    public static final List<String> MEMORY_FILES = List.of("manifest.json", "atoms/segment-000001.log",
            "vectors/segment-000001.f32", "indexes/vector-map.idx", "feedback/feedback-000001.log");

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

    /**
     * Read-only, fail-closed check of a projection tree: walks {@code dir} without following links and
     * reports every symbolic link and every entry that is neither a regular file nor a directory. Nothing
     * below a reported link is ever read. A missing {@code dir} has no problems.
     */
    public List<String> linkProblems(Path dir) throws LedgerException {
        Path start = paths.contained(dir);
        List<String> problems = new ArrayList<>();
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) {
            return problems;
        }
        try (Stream<Path> walk = Files.walk(start)) { // Files.walk never follows links by default
            for (Path p : walk.sorted().toList()) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink()) {
                    problems.add(paths.display(p) + " is a symbolic link; projections must not contain links");
                } else if (!a.isRegularFile() && !a.isDirectory()) {
                    problems.add(paths.display(p) + " is not a regular file or directory");
                }
            }
        } catch (IOException e) {
            problems.add("cannot inspect " + paths.display(start) + ": " + e.getMessage());
        }
        return problems;
    }

    /**
     * Reports every artifact of the fixed projection memory layout ({@link #MEMORY_DIRS},
     * {@link #MEMORY_FILES}) that is missing or of the wrong kind. Checked before opening the memory,
     * because opening it would silently recreate missing directories and logs.
     */
    public List<String> memoryLayoutProblems(Path memoryDir) throws LedgerException {
        Path memory = paths.contained(memoryDir);
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(memory, LinkOption.NOFOLLOW_LINKS)) {
            problems.add(paths.display(memory) + " is missing");
            return problems;
        }
        for (String name : MEMORY_DIRS) {
            Path p = paths.contained(memory.resolve(name));
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                problems.add(paths.display(p) + " is missing or not a directory");
            }
        }
        for (String name : MEMORY_FILES) {
            Path p = paths.contained(memory.resolve(name));
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                problems.add(paths.display(p) + " is missing or not a regular file");
            }
        }
        return problems;
    }

    /**
     * Read-only generation fingerprint of the scope: the ledger segment size and the relative path and size of
     * every entry under {@code projection/}. Two equal fingerprints taken around a read mean no writer changed
     * these files in between. Links are not followed.
     */
    public String generation() {
        StringBuilder sb = new StringBuilder();
        try {
            Path segment = paths.segment(scopeDir);
            sb.append("ledger=").append(Files.exists(segment, LinkOption.NOFOLLOW_LINKS)
                    ? Files.size(segment) : -1);
            Path dir = projectionDir();
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path p : walk.sorted().toList()) {
                        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        sb.append('|').append(paths.display(p)).append('=').append(a.size());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            sb.append("|unreadable:").append(e.getMessage());
        }
        return sb.toString();
    }
}
