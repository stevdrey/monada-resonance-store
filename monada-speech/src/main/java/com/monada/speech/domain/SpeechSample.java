package com.monada.speech.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SpeechSample(
        String id,
        String speakerId,
        SpeechDatasetSource datasetSource,
        Path audioPath,
        String transcript,
        List<String> aliases,
        SpeechCondition condition,
        SpeechTaskType taskType,
        String language,
        AudioMetadata audioMetadata,
        Instant createdAt
) {
    public SpeechSample {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(speakerId, "speakerId");
        if (speakerId.isBlank()) {
            throw new IllegalArgumentException("speakerId must not be blank");
        }
        Objects.requireNonNull(datasetSource, "datasetSource");
        Objects.requireNonNull(audioPath, "audioPath");
        Objects.requireNonNull(transcript, "transcript");
        if (transcript.isBlank()) {
            throw new IllegalArgumentException("transcript must not be blank");
        }
        Objects.requireNonNull(aliases, "aliases");
        if (aliases.isEmpty()) {
            throw new IllegalArgumentException("aliases must not be empty");
        }
        aliases = List.copyOf(aliases);
        for (String alias : aliases) {
            Objects.requireNonNull(alias, "alias");
            if (alias.isBlank()) {
                throw new IllegalArgumentException("aliases must not contain blank values");
            }
        }
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(language, "language");
        if (language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        Objects.requireNonNull(audioMetadata, "audioMetadata");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
