package com.monada.speech.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.monada.core.AtomType;
import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;

class SpeechSampleAtomMapperTest {

    @Test
    void mapsTranscriptToAtomTypeText() {
        var sample = createSample("sample1", "hello world", List.of("greeting"));
        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        assertEquals(AtomType.TEXT, atom.type());
    }

    @Test
    void preservesSampleId() {
        var sample = createSample("sample123", "hello", List.of("alias"));

        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        assertEquals("sample123", atom.id());
    }

    @Test
    void mapsTranscriptToContent() {
        var sample = createSample("sample1", "this is the transcript", List.of("text"));

        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        assertEquals("this is the transcript", atom.content());
    }

    @Test
    void preservesAliases() {
        var sample = createSample("sample1", "hello world", List.of("greeting", "salutation", "hello"));

        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        assertEquals(List.of("greeting", "salutation", "hello"), atom.aliases());
    }

    @Test
    void preservesCreatedAt() {
        var now = Instant.now();
        var sample = new SpeechSample(
                "sample1",
                "speaker1",
                SpeechDatasetSource.TORGO,
                Path.of("/audio.wav"),
                "transcript",
                List.of("alias"),
                SpeechCondition.CONTROL,
                SpeechTaskType.WORD,
                "en",
                new AudioMetadata(16000, 1, 1000, "hash"),
                now
        );

        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        assertEquals(now, atom.createdAt());
    }

    @Test
    void emitsEmptyMetadata() {
        var sample = createSample("sample1", "hello", List.of("alias"));

        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        assertTrue(atom.metadata().isEmpty());
        assertEquals(Map.of(), atom.metadata());
    }

    @Test
    void usesDefaultWeight() {
        var sample = createSample("sample1", "hello", List.of("alias"));

        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        assertEquals(1.0, atom.weight());
    }

    @Test
    void doesNotIncludeAudioMetadataInAtom() {
        var sample = new SpeechSample(
                "sample1",
                "speaker1",
                SpeechDatasetSource.TORGO,
                Path.of("/path/to/audio.wav"),
                "transcript text",
                List.of("alias"),
                SpeechCondition.DYSARTHRIC,
                SpeechTaskType.SENTENCE,
                "en-US",
                new AudioMetadata(22050, 2, 5000, "sha256hash"),
                Instant.now()
        );

        var atom = SpeechSampleAtomMapper.toTranscriptAtom(sample);

        // Audio-specific metadata should NOT be in the atom
        assertTrue(atom.metadata().isEmpty());
        // But transcript should be the content
        assertEquals("transcript text", atom.content());
    }

    @Test
    void multipleSamplesMapIndependently() {
        var sample1 = createSample("sample1", "first transcript", List.of("first"));
        var sample2 = createSample("sample2", "second transcript", List.of("second"));

        var atom1 = SpeechSampleAtomMapper.toTranscriptAtom(sample1);
        var atom2 = SpeechSampleAtomMapper.toTranscriptAtom(sample2);

        assertEquals("sample1", atom1.id());
        assertEquals("first transcript", atom1.content());
        assertEquals("sample2", atom2.id());
        assertEquals("second transcript", atom2.content());
    }

    private SpeechSample createSample(String id, String transcript, List<String> aliases) {
        return new SpeechSample(
                id,
                "speaker1",
                SpeechDatasetSource.TORGO,
                Path.of("/audio/" + id + ".wav"),
                transcript,
                aliases,
                SpeechCondition.CONTROL,
                SpeechTaskType.SENTENCE,
                "en",
                new AudioMetadata(16000, 1, 1000, "hash" + id),
                Instant.now()
        );
    }
}
