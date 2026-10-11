package com.monada.storage.execution;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Paths of the derived, rebuildable projection of one scope (contract v1, sections 2 and 11):
 *
 * <pre>
 * scopes/s-&lt;sha256hex(scope id)&gt;/
 *   projection.current              # pointer: "projection-&lt;n&gt;" + LF, replaced atomically
 *   projection-&lt;n&gt;/                 # current generation
 *     projection-checkpoint.log     # see {@link ProjectionCheckpoint}
 *     memory/                       # standard MonadaMemory layout (manifest 0.4)
 *   projection-&lt;n-1&gt;/               # previous generation, kept for in-flight readers
 * </pre>
 *
 * <p>A rebuild writes a new generation under a name no reader references and publishes it by replacing the
 * pointer, so the scope is never without a published projection and no directory a reader may hold is
 * moved.
 * <p>The scope directory name is derived only from the SHA-256 of the scope ID, so raw IDs are never path
 * segments, and every path is checked to stay inside the ledger root (symbolic links included). Resolving a
 * layout creates nothing.
 */
public final class ProjectionLayout {
    public static final String POINTER_FILE = "projection.current";
    static final String POINTER_TEMP = "projection.current.tmp";
    public static final String GENERATION_PREFIX = "projection-";
    private static final Pattern GENERATION_NAME = Pattern.compile("projection-([0-9]{6,18})");
    private static final int MAX_POINTER_BYTES = 64;
    public static final String MEMORY_DIR = "memory";
    /** Atom segment a projection memory's manifest must name (manifest 0.4 default). */
    public static final String ATOM_SEGMENT = "atoms/segment-000001.log";
    /** Vector segment a projection memory's manifest must name (manifest 0.4 default). */
    public static final String VECTOR_SEGMENT = "vectors/segment-000001.f32";
    /** Feedback segment a projection memory's manifest must name (manifest 0.4 default). */
    public static final String FEEDBACK_SEGMENT = "feedback/feedback-000001.log";
    /** Directories of the fixed MonadaMemory layout a projection creates (manifest 0.4 defaults). */
    public static final List<String> MEMORY_DIRS = List.of("atoms", "vectors", "indexes", "feedback");
    /** Files of the fixed MonadaMemory layout a projection creates (manifest 0.4 defaults). */
    public static final List<String> MEMORY_FILES = List.of("manifest.json", ATOM_SEGMENT, VECTOR_SEGMENT,
            "indexes/vector-map.idx", FEEDBACK_SEGMENT);

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

    public Path pointer() throws LedgerException {
        return paths.contained(scopeDir.resolve(POINTER_FILE));
    }

    /**
     * The published generation directory named by the pointer, or empty when there is no pointer. The pointer
     * must be a regular file (never a link) holding exactly {@code projection-<n>} and a line feed; anything
     * else fails with a {@link LedgerException}. Reading creates nothing.
     */
    public Optional<Path> currentDir() throws LedgerException {
        Path pointer = pointer();
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) {
            throw new LedgerException(POINTER_FILE + " is not a regular file");
        }
        String text;
        try {
            Optional<byte[]> bytes = LedgerPaths.readBounded(pointer, MAX_POINTER_BYTES);
            if (bytes.isEmpty()) {
                throw new LedgerException(POINTER_FILE + " is larger than " + MAX_POINTER_BYTES + " bytes");
            }
            text = LedgerPaths.decodeStrict(bytes.get());
        } catch (LedgerException e) {
            throw e;
        } catch (IOException e) {
            throw new LedgerException("cannot read " + POINTER_FILE + ": " + e.getMessage());
        }
        if (!text.endsWith("\n") || !GENERATION_NAME.matcher(text.substring(0, text.length() - 1)).matches()) {
            throw new LedgerException(POINTER_FILE + " does not name a projection generation");
        }
        return Optional.of(paths.contained(scopeDir.resolve(text.substring(0, text.length() - 1))));
    }

    /** Generation directories present in the scope, in ascending generation order. */
    public List<Path> generationDirs() throws LedgerException {
        List<Path> out = new ArrayList<>();
        for (Path p : generationEntries()) {
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                out.add(paths.contained(p)); // a real directory directly below the scope never escapes
            }
        }
        return out;
    }

    /**
     * Entries named like a generation that are not real directories (symbolic links, dangling or not, and
     * plain files). They are never followed or resolved; a rebuild removes the entries themselves.
     */
    public List<Path> invalidGenerationEntries() throws LedgerException {
        List<Path> out = new ArrayList<>();
        for (Path p : generationEntries()) {
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Every entry named like a generation, in ascending generation order; nothing is resolved or followed. */
    private List<Path> generationEntries() throws LedgerException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(scopeDir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (Stream<Path> entries = Files.list(scopeDir)) {
            for (Path p : entries.toList()) {
                if (GENERATION_NAME.matcher(p.getFileName().toString()).matches()) {
                    out.add(scopeDir.resolve(p.getFileName()));
                }
            }
        } catch (IOException e) {
            throw new LedgerException("cannot list " + paths.display(scopeDir) + ": " + e.getMessage());
        }
        out.sort(Comparator.comparingLong(ProjectionLayout::generationNumber));
        return out;
    }

    /**
     * A fresh generation directory name above every entry named like a generation (including invalid ones), so
     * a name is never reused and never collides with a leftover link.
     */
    public Path nextGenerationDir() throws LedgerException {
        List<Path> existing = generationEntries();
        long next = existing.isEmpty() ? 1 : generationNumber(existing.get(existing.size() - 1)) + 1;
        return paths.contained(scopeDir.resolve(GENERATION_PREFIX + String.format("%06d", next)));
    }

    static long generationNumber(Path generationDir) {
        Matcher m = GENERATION_NAME.matcher(generationDir.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a projection generation: " + generationDir.getFileName());
        }
        return Long.parseLong(m.group(1));
    }

    /**
     * Publishes {@code generationDir} by durably writing a temporary pointer and atomically replacing
     * {@code projection.current} with it. Readers see either the old or the new generation, never none.
     */
    public void publish(Path generationDir) throws IOException {
        Path dir = paths.contained(generationDir);
        if (!dir.getParent().equals(scopeDir) || !GENERATION_NAME.matcher(dir.getFileName().toString()).matches()) {
            throw new IllegalArgumentException("not a generation directory of this scope: " + generationDir);
        }
        Path temp = paths.contained(scopeDir.resolve(POINTER_TEMP));
        Files.deleteIfExists(temp);
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap((dir.getFileName() + "\n").getBytes(StandardCharsets.US_ASCII));
            while (buffer.hasRemaining()) {
                out.write(buffer);
            }
            out.force(true);
        }
        Files.move(temp, pointer(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Leftover temporary pointer of an interrupted publication, if any. */
    public Path pointerTemp() throws LedgerException {
        return paths.contained(scopeDir.resolve(POINTER_TEMP));
    }

    /** Checkpoint file inside a generation directory. */
    public Path checkpoint(Path projectionDir) throws LedgerException {
        return paths.contained(projectionDir.resolve(ProjectionCheckpoint.FILE_NAME));
    }

    /** MonadaMemory directory inside a generation directory. */
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
     * Read-only generation fingerprint of the scope: the ledger segment size, the pointer and the relative path
     * and size of every entry of the current generation. Two equal fingerprints taken around a read mean no writer changed
     * these files in between. Links are not followed.
     */
    public String generation() {
        StringBuilder sb = new StringBuilder();
        try {
            Path segment = paths.segment(scopeDir);
            sb.append("ledger=").append(Files.exists(segment, LinkOption.NOFOLLOW_LINKS)
                    ? Files.size(segment) : -1);
            Path pointer = pointer();
            sb.append("|pointer=");
            if (Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) {
                sb.append(LedgerPaths.readBounded(pointer, MAX_POINTER_BYTES)
                        .map(b -> new String(b, StandardCharsets.UTF_8).strip()).orElse("oversized"));
            }
            Optional<Path> current = currentDir();
            if (current.isPresent() && Files.exists(current.get(), LinkOption.NOFOLLOW_LINKS)) {
                Path dir = current.get();
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
