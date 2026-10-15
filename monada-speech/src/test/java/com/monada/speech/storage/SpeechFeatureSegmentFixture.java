package com.monada.speech.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes {@link FileSpeechFeatureStore} segment and index bytes directly (int header, then raw float bits, big-endian;
 * index lines {@code sampleId<TAB>offset}), so tests can persist arbitrary bit patterns and reorder index entries.
 */
final class SpeechFeatureSegmentFixture {

    static final String SEGMENT = "features/speech-features-000001.f32";
    static final String INDEX = "indexes/speech-feature-map.idx";

    record Frame(String sampleId, long offset, int bytes) {
    }

    private SpeechFeatureSegmentFixture() {
    }

    /** Writes the frames back to back as {@code sample-0, sample-1, ...} with an append-ordered index. */
    static List<Frame> write(Path root, List<int[]> rawBits) throws IOException {
        new FileSpeechFeatureStore(root);
        int total = rawBits.stream().mapToInt(bits -> Integer.BYTES + bits.length * Float.BYTES).sum();
        var segment = ByteBuffer.allocate(total);
        var frames = new ArrayList<Frame>(rawBits.size());
        for (int v = 0; v < rawBits.size(); v++) {
            int[] bits = rawBits.get(v);
            frames.add(new Frame("sample-" + v, segment.position(), Integer.BYTES + bits.length * Float.BYTES));
            segment.putInt(bits.length);
            for (int b : bits) {
                segment.putInt(b);
            }
        }
        Files.write(root.resolve(SEGMENT), segment.array());
        writeIndex(root, frames);
        return frames;
    }

    /** Replaces the index with one line per frame, in the given order. */
    static void writeIndex(Path root, List<Frame> frames) throws IOException {
        var index = new StringBuilder();
        for (var frame : frames) {
            index.append(frame.sampleId()).append('\t').append(frame.offset()).append('\n');
        }
        Files.writeString(root.resolve(INDEX), index, StandardCharsets.UTF_8);
    }
}
