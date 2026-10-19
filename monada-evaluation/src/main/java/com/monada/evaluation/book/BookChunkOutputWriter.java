package com.monada.evaluation.book;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Serializes a {@link BookChunker.Result} as two JSON Lines files with a fixed field order, minimal RFC 8259 escaping
 * (non-ASCII is written as raw UTF-8) and LF line ends, so the same source and policy always give the same bytes.
 *
 * <ul>
 *   <li>{@value #CHUNKS_FILE}: one {@code {"key","ordinal","text"}} object per chunk.</li>
 *   <li>{@value #SIDECAR_FILE}: a header object (format, corpus, source digest, manifest digest, policy, counts and
 *   the chunks file digest) followed by one provenance object per chunk, without text.</li>
 * </ul>
 */
public final class BookChunkOutputWriter {
    public static final String CHUNKS_FILE = "chunks.jsonl";
    public static final String SIDECAR_FILE = "sidecar.jsonl";
    public static final String SIDECAR_FORMAT = "monada-book-chunk-sidecar";
    public static final int SIDECAR_FORMAT_VERSION = 1;
    /** Empty lock file that serializes writers to one output directory; it is left in place on purpose. */
    public static final String LOCK_FILE = ".book-chunking.lock";

    private BookChunkOutputWriter() {
    }

    /** The two files' bytes. */
    public record Output(byte[] chunks, byte[] sidecar) {
    }

    public static Output render(BookChunker.Result result) {
        StringBuilder chunks = new StringBuilder();
        for (BookChunk chunk : result.chunks()) {
            chunks.append("{\"key\":").append(string(chunk.key()))
                    .append(",\"ordinal\":").append(chunk.ordinal())
                    .append(",\"text\":").append(string(chunk.text()))
                    .append("}\n");
        }
        byte[] chunkBytes = chunks.toString().getBytes(StandardCharsets.UTF_8);

        VerifiedBookSource source = result.source();
        BookChunkingPolicy policy = result.policy();
        StringBuilder sidecar = new StringBuilder();
        sidecar.append("{\"format\":").append(string(SIDECAR_FORMAT))
                .append(",\"formatVersion\":").append(SIDECAR_FORMAT_VERSION)
                .append(",\"corpus\":").append(string(source.corpus()))
                .append(",\"corpusVersion\":").append(string(source.corpusVersion()))
                .append(",\"sourceBytes\":").append(source.sizeBytes())
                .append(",\"sourceSha256\":").append(string(source.sha256()))
                .append(",\"manifestSha256\":").append(string(source.manifestSha256()))
                .append(",\"policy\":{\"id\":").append(string(policy.id()))
                .append(",\"chapterHeadingPattern\":").append(string(policy.chapterHeadingPattern()))
                .append(",\"maxChunkBytes\":").append(policy.maxChunkBytes())
                .append(",\"overlapBytes\":").append(policy.overlapBytes())
                .append("},\"frontMatter\":").append(result.frontMatter())
                .append(",\"chapterCount\":").append(result.chapterCount())
                .append(",\"chunkCount\":").append(result.chunks().size())
                .append(",\"chunksFile\":").append(string(CHUNKS_FILE))
                .append(",\"chunksFileSha256\":").append(string(VerifiedBookSource.sha256(chunkBytes)))
                .append("}\n");
        for (BookChunk chunk : result.chunks()) {
            sidecar.append("{\"key\":").append(string(chunk.key()))
                    .append(",\"ordinal\":").append(chunk.ordinal())
                    .append(",\"sectionOrdinal\":").append(chunk.sectionOrdinal())
                    .append(",\"sectionKind\":").append(string(chunk.sectionKind().name()))
                    .append(",\"sectionHeading\":").append(string(chunk.sectionHeading()))
                    .append(",\"chunkInSection\":").append(chunk.chunkInSection())
                    .append(",\"byteStart\":").append(chunk.byteStart())
                    .append(",\"byteEnd\":").append(chunk.byteEnd())
                    .append(",\"codePointStart\":").append(chunk.codePointStart())
                    .append(",\"codePointEnd\":").append(chunk.codePointEnd())
                    .append(",\"lineStart\":").append(chunk.lineStart())
                    .append(",\"lineEnd\":").append(chunk.lineEnd())
                    .append(",\"utf8Bytes\":").append(chunk.utf8Bytes())
                    .append(",\"sha256\":").append(string(chunk.sha256()))
                    .append(",\"duplicateOf\":").append(string(chunk.duplicateOf()))
                    .append("}\n");
        }
        return new Output(chunkBytes, sidecar.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes both files into {@code directory} (created if needed) as one coherent generation:
     *
     * <ol>
     *   <li>writers to the same directory are serialized by an exclusive lock on {@value #LOCK_FILE};</li>
     *   <li>the old sidecar is removed before the chunks file is replaced, so an interrupted run leaves chunks
     *   without a sidecar (visibly incomplete), never new chunks beside an old sidecar;</li>
     *   <li>each file goes through a uniquely named temporary file and an atomic move, the sidecar last, so a
     *   present sidecar always describes the chunks file next to it by digest.</li>
     * </ol>
     */
    public static Output write(BookChunker.Result result, Path directory) throws IOException {
        Output output = render(result);
        // Absolute, so that an empty path (the working directory) still gives every target a parent directory.
        directory = directory.toAbsolutePath();
        Files.createDirectories(directory);
        // CREATE without TRUNCATE_EXISTING: opening the lock never changes an existing file's bytes.
        try (FileChannel lock = FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             FileLock ignored = lock.lock()) {
            Files.deleteIfExists(directory.resolve(SIDECAR_FILE));
            writeAtomically(directory.resolve(CHUNKS_FILE), output.chunks());
            writeAtomically(directory.resolve(SIDECAR_FILE), output.sidecar());
        }
        return output;
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        // A fresh, uniquely named temporary file (created exclusively, never an existing path or symlink), so no
        // input file, unrelated file or concurrent writer can be overwritten through a fixed temporary name.
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F || c == 0x2028 || c == 0x2029) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
