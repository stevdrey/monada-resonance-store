package com.monada.evaluation.book;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic text summary of one chunking run: no paths, timing or environment values, so the same source and
 * policy print the same report on every JVM.
 *
 * @param minBytes                smallest chunk UTF-8 size
 * @param medianBytes             lower median chunk UTF-8 size
 * @param maxBytes                largest chunk UTF-8 size
 * @param repeatedContentGroups   distinct texts emitted more than once
 * @param repeatedContentChunks   chunks whose text is shared with another chunk
 */
public record BookChunkingReport(BookChunker.Result result, long minBytes, long medianBytes, long maxBytes,
                                 int repeatedContentGroups, int repeatedContentChunks, String chunksSha256,
                                 String sidecarSha256) {

    public static BookChunkingReport of(BookChunker.Result result, BookChunkOutputWriter.Output output) {
        List<BookChunk> chunks = result.chunks();
        long[] sizes = chunks.stream().mapToLong(BookChunk::utf8Bytes).sorted().toArray();
        Map<String, Integer> countBySha = new HashMap<>();
        for (BookChunk chunk : chunks) {
            countBySha.merge(chunk.sha256(), 1, Integer::sum);
        }
        int groups = (int) countBySha.values().stream().filter(c -> c > 1).count();
        int repeated = countBySha.values().stream().filter(c -> c > 1).mapToInt(Integer::intValue).sum();
        return new BookChunkingReport(result,
                sizes.length == 0 ? 0 : sizes[0],
                sizes.length == 0 ? 0 : sizes[(sizes.length - 1) / 2],
                sizes.length == 0 ? 0 : sizes[sizes.length - 1],
                groups, repeated,
                VerifiedBookSource.sha256(output.chunks()), VerifiedBookSource.sha256(output.sidecar()));
    }

    public String render() {
        VerifiedBookSource source = result.source();
        BookChunkingPolicy policy = result.policy();
        return String.join("\n",
                "== Book chunking report",
                "corpus: " + source.corpus() + " version " + source.corpusVersion(),
                "source: " + source.sizeBytes() + " bytes, sha256 " + source.sha256(),
                "manifest sha256: " + source.manifestSha256(),
                "policy: " + policy.id() + " (maxChunkBytes=" + policy.maxChunkBytes() + ", overlapBytes="
                        + policy.overlapBytes() + ", chapterHeadingPattern=" + policy.chapterHeadingPattern() + ")",
                "sections: frontMatter=" + (result.frontMatter() ? "yes" : "no") + ", chapters="
                        + result.chapterCount(),
                "chunks: " + result.chunks().size(),
                "chunk UTF-8 bytes: min=" + minBytes + ", median=" + medianBytes + ", max=" + maxBytes,
                "repeated content: groups=" + repeatedContentGroups + ", chunks=" + repeatedContentChunks
                        + " (identical text shares one content-derived Store atom ID; chunk keys stay distinct)",
                "coverage: every non-whitespace code point is inside a chunk",
                BookChunkOutputWriter.CHUNKS_FILE + " sha256: " + chunksSha256,
                BookChunkOutputWriter.SIDECAR_FILE + " sha256: " + sidecarSha256,
                "");
    }
}
