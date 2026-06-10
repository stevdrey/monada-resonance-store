package com.monada.speech.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeechSampleTest {

    @Test
    void rejectsBlankSampleId() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                new SpeechSample(
                        "",
                        "speaker1",
                        SpeechDatasetSource.TORGO,
                        Path.of("/audio.wav"),
                        "hello world",
                        List.of("greeting"),
                        SpeechCondition.CONTROL,
                        SpeechTaskType.WORD,
                        "en",
                        new AudioMetadata(16000, 1, 1000, "abc123"),
                        Instant.now()
                )
        );
        assertTrue(ex.getMessage().contains("blank"));
    }

    @Test
    void rejectsBlankSpeakerId() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                new SpeechSample(
                        "sample1",
                        "  ",
                        SpeechDatasetSource.TORGO,
                        Path.of("/audio.wav"),
                        "hello world",
                        List.of("greeting"),
                        SpeechCondition.CONTROL,
                        SpeechTaskType.WORD,
                        "en",
                        new AudioMetadata(16000, 1, 1000, "abc123"),
                        Instant.now()
                )
        );
        assertTrue(ex.getMessage().contains("blank"));
    }

    @Test
    void rejectsBlankTranscript() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                new SpeechSample(
                        "sample1",
                        "speaker1",
                        SpeechDatasetSource.TORGO,
                        Path.of("/audio.wav"),
                        "",
                        List.of("greeting"),
                        SpeechCondition.CONTROL,
                        SpeechTaskType.WORD,
                        "en",
                        new AudioMetadata(16000, 1, 1000, "abc123"),
                        Instant.now()
                )
        );
        assertTrue(ex.getMessage().contains("blank"));
    }

    @Test
    void allowsEmptyAliases() {
        var sample = new SpeechSample(
                "sample1",
                "speaker1",
                SpeechDatasetSource.TORGO,
                Path.of("/audio.wav"),
                "hello",
                List.of(),
                SpeechCondition.CONTROL,
                SpeechTaskType.WORD,
                "en",
                new AudioMetadata(16000, 1, 1000, "abc123"),
                Instant.now()
        );
        assertTrue(sample.aliases().isEmpty());
    }

    @Test
    void rejectsBlankAliases() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                new SpeechSample(
                        "sample1",
                        "speaker1",
                        SpeechDatasetSource.TORGO,
                        Path.of("/audio.wav"),
                        "hello",
                        List.of("valid", "  "),
                        SpeechCondition.CONTROL,
                        SpeechTaskType.WORD,
                        "en",
                        new AudioMetadata(16000, 1, 1000, "abc123"),
                        Instant.now()
                )
        );
        assertTrue(ex.getMessage().contains("blank"));
    }

    @Test
    void rejectsNullAliasInList() {
        var mutableList = new ArrayList<String>();
        mutableList.add("valid");
        mutableList.add(null);

        // List.copyOf throws NPE when collection contains null elements
        assertThrows(NullPointerException.class, () ->
                new SpeechSample(
                        "sample1",
                        "speaker1",
                        SpeechDatasetSource.TORGO,
                        Path.of("/audio.wav"),
                        "hello",
                        mutableList,
                        SpeechCondition.CONTROL,
                        SpeechTaskType.WORD,
                        "en",
                        new AudioMetadata(16000, 1, 1000, "abc123"),
                        Instant.now()
                )
        );
    }

    @Test
    void keepsAliasesImmutable() {
        var mutableList = new ArrayList<String>();
        mutableList.add("alias1");
        mutableList.add("alias2");

        var sample = new SpeechSample(
                "sample1",
                "speaker1",
                SpeechDatasetSource.TORGO,
                Path.of("/audio.wav"),
                "hello",
                mutableList,
                SpeechCondition.CONTROL,
                SpeechTaskType.WORD,
                "en",
                new AudioMetadata(16000, 1, 1000, "abc123"),
                Instant.now()
        );

        mutableList.add("alias3");

        assertEquals(2, sample.aliases().size());
        assertThrows(UnsupportedOperationException.class, () ->
                ((List<String>) sample.aliases()).add("new")
        );
    }

    @Test
    void validatesAudioMetadata() {
        assertThrows(IllegalArgumentException.class, () ->
                new AudioMetadata(0, 1, 1000, "abc123")
        );
        assertThrows(IllegalArgumentException.class, () ->
                new AudioMetadata(16000, 0, 1000, "abc123")
        );
        assertThrows(IllegalArgumentException.class, () ->
                new AudioMetadata(16000, 1, -1, "abc123")
        );
        assertThrows(IllegalArgumentException.class, () ->
                new AudioMetadata(16000, 1, 1000, "  ")
        );
    }

    @Test
    void createsValidSample() {
        var now = Instant.now();
        var sample = new SpeechSample(
                "sample1",
                "speaker1",
                SpeechDatasetSource.TORGO,
                Path.of("/data/audio.wav"),
                "hello world",
                List.of("greeting", "hello"),
                SpeechCondition.CONTROL,
                SpeechTaskType.SENTENCE,
                "en-US",
                new AudioMetadata(16000, 1, 1250, "def456"),
                now
        );

        assertEquals("sample1", sample.id());
        assertEquals("speaker1", sample.speakerId());
        assertEquals(SpeechDatasetSource.TORGO, sample.datasetSource());
        assertEquals(Path.of("/data/audio.wav"), sample.audioPath());
        assertEquals("hello world", sample.transcript());
        assertEquals(List.of("greeting", "hello"), sample.aliases());
        assertEquals(SpeechCondition.CONTROL, sample.condition());
        assertEquals(SpeechTaskType.SENTENCE, sample.taskType());
        assertEquals("en-US", sample.language());
        assertEquals(16000, sample.audioMetadata().sampleRate());
        assertEquals(now, sample.createdAt());
    }

    @Test
    void acceptsUnknownConditionAndTaskType() {
        var sample = new SpeechSample(
                "sample1",
                "speaker1",
                SpeechDatasetSource.CUSTOM,
                Path.of("/audio.wav"),
                "test",
                List.of("test"),
                SpeechCondition.UNKNOWN,
                SpeechTaskType.UNKNOWN,
                "en",
                new AudioMetadata(16000, 1, 1000, "hash"),
                Instant.now()
        );

        assertEquals(SpeechCondition.UNKNOWN, sample.condition());
        assertEquals(SpeechTaskType.UNKNOWN, sample.taskType());
    }
}
