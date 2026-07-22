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
        return wavPath.resolveSibling(transcriptFilename(wavPath));
    }

    /**
     * Returns the session-level TORGO prompt path for a file in {@code wav_arrayMic}, or
     * {@code null} when the WAV is not in that native layout.
     *
     * <p>Example: {@code M01/Session1/wav_arrayMic/0001.wav} maps to
     * {@code M01/Session1/prompts/0001.txt}.
     */
    static Path arrayMicPromptTxt(Path wavPath) {
        Path audioDirectory = wavPath.getParent();
        if (audioDirectory == null
                || !audioDirectory.getFileName().toString().equalsIgnoreCase("wav_arrayMic")) {
            return null;
        }
        Path sessionDirectory = audioDirectory.getParent();
        if (sessionDirectory == null) {
            return null;
        }
        return sessionDirectory.resolve("prompts").resolve(transcriptFilename(wavPath));
    }

    private static String transcriptFilename(Path wavPath) {
        String filename = wavPath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return stem + ".txt";
    }
}
