package com.monada.speech.importer;

import java.nio.file.Path;

/**
 * Shared path helpers for TORGO importer components.
 */
final class TorgoPathUtils {

    private TorgoPathUtils() {
        // utility class
    }

    /**
     * Returns the sibling {@code .txt} path for a WAV file, using the same stem.
     *
     * <p>The stem is the part before the last dot. If the filename starts with a dot
     * (e.g. {@code .wav}), the whole filename is used as the stem.
     *
     * @param wavPath path to the WAV file
     * @return path to the sibling transcript file
     */
    static Path siblingTxt(Path wavPath) {
        String filename = wavPath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return wavPath.resolveSibling(stem + ".txt");
    }
}
