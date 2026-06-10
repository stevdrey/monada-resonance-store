package com.monada.speech.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;

class FileSpeechSampleStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndFindsById() throws IOException {
        var store = new FileSpeechSampleStore(tempDir);
        var sample = createSample("sample1", "speaker1");

        store.save(sample);

        var found = store.findById("sample1");
        assertTrue(found.isPresent());
        assertEquals("sample1", found.get().id());
        assertEquals("speaker1", found.get().speakerId());
        assertEquals("hello world", found.get().transcript());
    }

    @Test
    void returnsEmptyWhenNotFound() throws IOException {
        var store = new FileSpeechSampleStore(tempDir);

        var found = store.findById("nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    void savesAndReloadsAllSamples() throws IOException {
        var store = new FileSpeechSampleStore(tempDir);
        var sample1 = createSample("sample1", "speaker1");
        var sample2 = createSample("sample2", "speaker2");

        store.save(sample1);
        store.save(sample2);

        var all = store.findAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(s -> s.id().equals("sample1")));
        assertTrue(all.stream().anyMatch(s -> s.id().equals("sample2")));
    }

    @Test
    void findsMultipleSamplesBySpeakerId() throws IOException {
        var store = new FileSpeechSampleStore(tempDir);
        var sample1 = createSample("sample1", "speakerA");
        var sample2 = createSample("sample2", "speakerA");
        var sample3 = createSample("sample3", "speakerB");

        store.save(sample1);
        store.save(sample2);
        store.save(sample3);

        var speakerASamples = store.findBySpeakerId("speakerA");
        assertEquals(2, speakerASamples.size());
        assertTrue(speakerASamples.stream().allMatch(s -> s.speakerId().equals("speakerA")));
    }

    @Test
    void handlesDuplicateSampleIdsDeterministically() throws IOException {
        var store = new FileSpeechSampleStore(tempDir);
        var original = createSample("sample1", "speaker1", "original transcript");
        var updated = createSample("sample1", "speaker1", "updated transcript");

        store.save(original);
        store.save(updated);

        var found = store.findById("sample1");
        assertTrue(found.isPresent());
        assertEquals("updated transcript", found.get().transcript());

        var all = store.findAll();
        assertEquals(1, all.size());
        assertEquals("updated transcript", all.get(0).transcript());
    }

    @Test
    void preservesFirstSeenInsertionOrderAfterDeduplication() throws IOException {
        var store = new FileSpeechSampleStore(tempDir);
        var sample1 = createSample("sample1", "speaker1");
        var sample2 = createSample("sample2", "speaker2");
        var sample1Updated = createSample("sample1", "speaker1", "updated");
        var sample3 = createSample("sample3", "speaker3");

        store.save(sample1);
        store.save(sample2);
        store.save(sample1Updated);
        store.save(sample3);

        var all = store.findAll();
        assertEquals(3, all.size());
        // sample1 was first seen at position 0, so it stays first
        assertEquals("sample1", all.get(0).id());
        assertEquals("sample2", all.get(1).id());
        assertEquals("sample3", all.get(2).id());
    }

    @Test
    void worksAfterReopeningStore() throws IOException {
        var sample = createSample("sample1", "speaker1");

        // First session: save
        var store = new FileSpeechSampleStore(tempDir);
        store.save(sample);

        // Second session: reopen and read
        var reopenedStore = new FileSpeechSampleStore(tempDir);
        var found = reopenedStore.findById("sample1");

        assertTrue(found.isPresent());
        assertEquals("sample1", found.get().id());
        assertEquals("speaker1", found.get().speakerId());
    }

    @Test
    void failsClearlyOnMalformedJsonL() throws IOException {
        var sampleFile = tempDir.resolve("samples/speech-samples-000001.jsonl");
        Files.createDirectories(sampleFile.getParent());
        Files.writeString(sampleFile, "{not valid json", StandardCharsets.UTF_8);

        var store = new FileSpeechSampleStore(tempDir);

        var ex = assertThrows(IllegalStateException.class, store::findAll);
        assertTrue(ex.getMessage().contains("Malformed"));
    }

    @Test
    void toleratesBlankLines() throws IOException {
        var store = new FileSpeechSampleStore(tempDir);
        var sample = createSample("sample1", "speaker1");

        store.save(sample);

        // Inject blank lines into the file
        var sampleFile = tempDir.resolve("samples/speech-samples-000001.jsonl");
        var content = Files.readString(sampleFile, StandardCharsets.UTF_8);
        Files.writeString(sampleFile, "\n\n" + content + "\n\n", StandardCharsets.UTF_8);

        var all = store.findAll();
        assertEquals(1, all.size());
        assertEquals("sample1", all.get(0).id());
    }

    @Test
    void preservesAllFields() throws IOException {
        var now = Instant.now();
        var store = new FileSpeechSampleStore(tempDir);
        var sample = new SpeechSample(
                "sample1",
                "speaker1",
                SpeechDatasetSource.UA_SPEECH,
                Path.of("/data/audio.wav"),
                "test transcript",
                List.of("alias1", "alias2"),
                SpeechCondition.DYSARTHRIC,
                SpeechTaskType.SENTENCE,
                "en-US",
                new AudioMetadata(22050, 2, 2500, "sha256hash"),
                now
        );

        store.save(sample);

        var found = store.findById("sample1").orElseThrow();
        assertEquals("sample1", found.id());
        assertEquals("speaker1", found.speakerId());
        assertEquals(SpeechDatasetSource.UA_SPEECH, found.datasetSource());
        assertEquals(Path.of("/data/audio.wav"), found.audioPath());
        assertEquals("test transcript", found.transcript());
        assertEquals(List.of("alias1", "alias2"), found.aliases());
        assertEquals(SpeechCondition.DYSARTHRIC, found.condition());
        assertEquals(SpeechTaskType.SENTENCE, found.taskType());
        assertEquals("en-US", found.language());
        assertEquals(22050, found.audioMetadata().sampleRate());
        assertEquals(2, found.audioMetadata().channels());
        assertEquals(2500, found.audioMetadata().durationMs());
        assertEquals("sha256hash", found.audioMetadata().sha256());
        assertEquals(now, found.createdAt());
    }

    private SpeechSample createSample(String id, String speakerId) {
        return createSample(id, speakerId, "hello world");
    }

    private SpeechSample createSample(String id, String speakerId, String transcript) {
        return new SpeechSample(
                id,
                speakerId,
                SpeechDatasetSource.TORGO,
                Path.of("/audio/" + id + ".wav"),
                transcript,
                List.of("alias"),
                SpeechCondition.CONTROL,
                SpeechTaskType.WORD,
                "en",
                new AudioMetadata(16000, 1, 1000, "hash" + id),
                Instant.now()
        );
    }
}
