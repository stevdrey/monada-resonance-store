package com.monada.speech.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the transcript text for a TORGO WAV file from an inspectable local text file.
 *
 * <p>The resolver first uses a sibling plain-text file with the same stem. When that file is
 * absent, it also recognizes the native array-microphone layout:
 * {@code M01/Session1/wav_arrayMic/0001.wav} → {@code M01/Session1/prompts/0001.txt}.
 *
 * <p>Returns {@link Optional#empty()} when no supported transcript file exists or the selected
 * file's trimmed content is blank.
 */
final class TorgoTranscriptResolver {

    private TorgoTranscriptResolver() {
        // utility class
    }

    /**
     * Attempts to resolve the transcript for the given WAV path.
     *
     * @param wavPath path to the WAV file
     * @return trimmed transcript text, or empty if no supported companion file exists or it is blank
     * @throws IOException if the selected companion file exists but cannot be read
     */
    static Optional<String> resolve(Path wavPath) throws IOException {
        for (Path candidate : transcriptCandidates(wavPath)) {
            if (Files.notExists(candidate)) {
                continue;
            }
            return readTranscript(candidate);
        }
        return Optional.empty();
    }

    /** Returns whether any supported transcript companion path exists. */
    static boolean transcriptFileExists(Path wavPath) {
        return transcriptCandidates(wavPath).stream().anyMatch(Files::exists);
    }

    private static List<Path> transcriptCandidates(Path wavPath) {
        Path sibling = TorgoPathUtils.siblingTxt(wavPath);
        Path arrayMicPrompt = TorgoPathUtils.arrayMicPromptTxt(wavPath);
        return arrayMicPrompt == null ? List.of(sibling) : List.of(sibling, arrayMicPrompt);
    }

    private static Optional<String> readTranscript(Path transcriptPath) throws IOException {
        String content = Files.readString(transcriptPath, StandardCharsets.UTF_8).strip();
        if (content.isBlank()) {
            return Optional.empty();
        }
        // Normalize internal whitespace for consistent downstream encoding
        content = content.replaceAll("\\s+", " ");
        return Optional.of(content);
    }
}
