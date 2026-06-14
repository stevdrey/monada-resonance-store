package com.monada.speech.importer;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;

import java.nio.file.Path;

/**
 * Infers TORGO-specific metadata from path segments, using directory naming
 * conventions found in the TORGO dataset layout.
 *
 * <p>TORGO directory layout convention:
 * <pre>
 *   &lt;datasetRoot&gt;/
 *     &lt;speakerId&gt;/          e.g. M01, FC1 — first segment is speaker
 *       &lt;session&gt;/          e.g. Session1
 *         &lt;taskType&gt;/       e.g. words, sentences, commands
 *           &lt;file&gt;.wav
 * </pre>
 *
 * <p>Condition heuristics (applied to speaker-id segment, case-insensitive):
 * <ul>
 *   <li>Starts with {@code FC} or {@code MC} → {@link SpeechCondition#CONTROL}</li>
 *   <li>Starts with {@code F} or {@code M} followed by digits, or contains
 *       {@code dysarthric} → {@link SpeechCondition#DYSARTHRIC}</li>
 *   <li>Otherwise → {@link SpeechCondition#UNKNOWN}</li>
 * </ul>
 *
 * <p>Task type heuristics (applied to each path segment, case-insensitive):
 * <ul>
 *   <li>{@code word} or {@code words} → {@link SpeechTaskType#WORD}</li>
 *   <li>{@code sentence} or {@code sentences} → {@link SpeechTaskType#SENTENCE}</li>
 *   <li>{@code command} or {@code commands} → {@link SpeechTaskType#COMMAND}</li>
 *   <li>Otherwise → {@link SpeechTaskType#UNKNOWN}</li>
 * </ul>
 */
final class TorgoPathInference {

    private TorgoPathInference() {
        // utility class
    }

    /**
     * Extracts the speaker ID from the first path component under
     * {@code datasetRoot}. Returns {@code "unknown"} if the relative path has
     * no components.
     *
     * @param datasetRoot root directory of the dataset
     * @param wavPath     absolute path to the WAV file
     * @return speaker id string
     */
    static String speakerId(Path datasetRoot, Path wavPath) {
        Path relative = datasetRoot.relativize(wavPath);
        if (relative.getNameCount() < 1) {
            return "unknown";
        }
        return relative.getName(0).toString();
    }

    /**
     * Infers the {@link SpeechCondition} from the speaker-id segment.
     *
     * @param speakerId the speaker id (first path component under dataset root)
     * @return inferred condition
     */
    static SpeechCondition condition(String speakerId) {
        String lower = speakerId.toLowerCase();
        if (lower.contains("dysarthric")) {
            return SpeechCondition.DYSARTHRIC;
        }
        // Control speakers in TORGO: FC1, FC2, MC1, MC2, MC3, MC4
        if (lower.matches("^(fc|mc)\\d*.*")) {
            return SpeechCondition.CONTROL;
        }
        // Dysarthric speakers in TORGO: F01-F05, M01-M05 (single letter + digits)
        if (lower.matches("^[fm]\\d+.*")) {
            return SpeechCondition.DYSARTHRIC;
        }
        return SpeechCondition.UNKNOWN;
    }

    /**
     * Infers the {@link SpeechTaskType} by scanning all path segments of the
     * WAV path relative to the dataset root for known directory name patterns.
     * The deepest (closest to the file) matching segment wins.
     *
     * @param datasetRoot root directory of the dataset
     * @param wavPath     absolute path to the WAV file
     * @return inferred task type
     */
    static SpeechTaskType taskType(Path datasetRoot, Path wavPath) {
        Path relative = datasetRoot.relativize(wavPath);
        SpeechTaskType result = SpeechTaskType.UNKNOWN;
        // Iterate all segments except the filename itself
        int count = relative.getNameCount();
        for (int i = 0; i < count - 1; i++) {
            String segment = relative.getName(i).toString().toLowerCase();
            SpeechTaskType inferred = inferTaskFromSegment(segment);
            if (inferred != SpeechTaskType.UNKNOWN) {
                result = inferred;
            }
        }
        return result;
    }

    private static SpeechTaskType inferTaskFromSegment(String segment) {
        return switch (segment) {
            case "word", "words" -> SpeechTaskType.WORD;
            case "sentence", "sentences" -> SpeechTaskType.SENTENCE;
            case "command", "commands" -> SpeechTaskType.COMMAND;
            case "spontaneous", "spontaneous_speech" -> SpeechTaskType.SPONTANEOUS;
            default -> SpeechTaskType.UNKNOWN;
        };
    }
}
