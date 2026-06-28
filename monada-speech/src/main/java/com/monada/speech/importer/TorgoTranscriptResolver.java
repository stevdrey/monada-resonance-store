package com.monada.speech.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the transcript text for a TORGO WAV file by looking for a sibling
 * plain-text file with the same stem and a {@code .txt} extension.
 *
 * <p>Example: {@code M01/Session1/words/hello.wav} → {@code M01/Session1/words/hello.txt}
 *
 * <p>Returns {@link Optional#empty()} when the companion file does not exist or its
 * trimmed content is blank.
 */
final class TorgoTranscriptResolver {

    private TorgoTranscriptResolver() {
        // utility class
    }

    /**
     * Attempts to resolve the transcript for the given WAV path.
     *
     * @param wavPath path to the WAV file
     * @return trimmed transcript text, or empty if the companion file does not exist or is blank
     * @throws IOException if the companion file exists but cannot be read
     */
    static Optional<String> resolve(Path wavPath) throws IOException {
        Path companion = TorgoPathUtils.siblingTxt(wavPath);
        if (Files.notExists(companion)) {
            return Optional.empty();
        }
        String content = Files.readString(companion, StandardCharsets.UTF_8).strip();
        if (content.isBlank()) {
            return Optional.empty();
        }
        // Normalize internal whitespace for consistent downstream encoding
        content = content.replaceAll("\\s+", " ");
        return Optional.of(content);
    }
}
