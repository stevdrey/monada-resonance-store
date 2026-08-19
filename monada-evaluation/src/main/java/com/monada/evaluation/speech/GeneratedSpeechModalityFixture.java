package com.monada.evaluation.speech;

import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Builds the deterministic generated fixture used by the modality comparison. */
final class GeneratedSpeechModalityFixture {

    static final String LABEL = "generated-speech-modality-comparison-v1";
    static final int SAMPLE_RATE = 16_000;
    static final int DURATION_MS = 500;
    static final int DIMENSIONS = 64;
    static final int K = 1;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;

    private GeneratedSpeechModalityFixture() {
    }

    static Fixture create(Path root) throws IOException {
        Path audioRoot = Files.createDirectories(root.resolve("audio"));
        Path queryRoot = Files.createDirectories(root.resolve("queries"));
        Path storeRoot = root.resolve("speech-store");
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);
        BasicAcousticFeatureEncoder encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);

        List<SampleSpec> specs = List.of(
                new SampleSpec("a_deploy_440", "D01", "deploy service safely", 440.0f,
                        SpeechCondition.DYSARTHRIC, SpeechTaskType.COMMAND),
                new SampleSpec("z_deploy_1760", "D02", "deploy service safely", 1760.0f,
                        SpeechCondition.DYSARTHRIC, SpeechTaskType.COMMAND),
                new SampleSpec("a_status_880", "C01", "read service status", 880.0f,
                        SpeechCondition.CONTROL, SpeechTaskType.COMMAND),
                new SampleSpec("z_stop_880", "C02", "stop service safely", 880.0f,
                        SpeechCondition.CONTROL, SpeechTaskType.COMMAND),
                new SampleSpec("m_backup_1320", "C03", "restore database backup", 1320.0f,
                        SpeechCondition.CONTROL, SpeechTaskType.SENTENCE),
                new SampleSpec("a_ambiguous_text_550", "D03", "rotate access token", 550.0f,
                        SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD),
                new SampleSpec("a_ambiguous_audio_660", "D04", "inspect audit log", 660.0f,
                        SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD),
                new SampleSpec("z_ambiguous_660", "D05", "rotate access token", 660.0f,
                        SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));

        List<SpeechSample> samples = new ArrayList<>();
        for (SampleSpec spec : specs) {
            Path audioPath = writeSineWav(audioRoot, spec.id(), spec.frequencyHz());
            samples.add(new SpeechSample(
                    spec.id(),
                    spec.speakerId(),
                    SpeechDatasetSource.CUSTOM,
                    audioPath,
                    spec.transcript(),
                    List.of(),
                    spec.condition(),
                    spec.taskType(),
                    "en-US",
                    new AudioMetadata(SAMPLE_RATE, CHANNELS, DURATION_MS, "generated-" + spec.id()),
                    Instant.EPOCH));
        }
        samples.sort(Comparator.comparing(SpeechSample::id));
        for (SpeechSample sample : samples) {
            sampleStore.save(sample);
            featureStore.save(sample.id(), encoder.encode(sample.audioPath()));
        }

        List<PairedSpeechQuery> queries = List.of(
                new PairedSpeechQuery(
                        "q_acoustic_only",
                        writeSineWav(queryRoot, "q_acoustic_only", 1760.0f),
                        "deploy service safely",
                        Set.of("z_deploy_1760"),
                        SpeechCondition.DYSARTHRIC,
                        SpeechTaskType.COMMAND),
                new PairedSpeechQuery(
                        "q_transcript_only",
                        writeSineWav(queryRoot, "q_transcript_only", 880.0f),
                        "stop service safely",
                        Set.of("z_stop_880"),
                        SpeechCondition.CONTROL,
                        SpeechTaskType.COMMAND),
                new PairedSpeechQuery(
                        "q_both_succeed",
                        writeSineWav(queryRoot, "q_both_succeed", 1320.0f),
                        "restore database backup",
                        Set.of("m_backup_1320"),
                        SpeechCondition.CONTROL,
                        SpeechTaskType.SENTENCE),
                new PairedSpeechQuery(
                        "q_both_fail",
                        writeSineWav(queryRoot, "q_both_fail", 660.0f),
                        "rotate access token",
                        Set.of("z_ambiguous_660"),
                        SpeechCondition.DYSARTHRIC,
                        SpeechTaskType.WORD));

        return new Fixture(
                List.copyOf(samples),
                queries,
                sampleStore,
                featureStore,
                encoder,
                LABEL,
                K);
    }

    static Path writeSineWav(Path directory, String name, float frequencyHz) throws IOException {
        Path wav = directory.resolve(name + ".wav");
        Files.write(wav, generateSineWav(frequencyHz));
        return wav;
    }

    private static byte[] generateSineWav(float frequencyHz) {
        int sampleCount = SAMPLE_RATE * DURATION_MS / 1_000;
        byte[] pcm = new byte[sampleCount * CHANNELS * (BITS_PER_SAMPLE / 8)];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < sampleCount; index++) {
            double time = index / (double) SAMPLE_RATE;
            short value = (short) (Math.sin(2.0 * Math.PI * frequencyHz * time) * 32_767);
            samples.putShort(value);
        }
        return withWavHeader(pcm);
    }

    private static byte[] withWavHeader(byte[] pcm) {
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        writeAscii(wav, "RIFF");
        writeIntLe(wav, 36 + pcm.length);
        writeAscii(wav, "WAVE");
        writeAscii(wav, "fmt ");
        writeIntLe(wav, 16);
        writeShortLe(wav, (short) 1);
        writeShortLe(wav, (short) CHANNELS);
        writeIntLe(wav, SAMPLE_RATE);
        writeIntLe(wav, byteRate);
        writeShortLe(wav, (short) blockAlign);
        writeShortLe(wav, (short) BITS_PER_SAMPLE);
        writeAscii(wav, "data");
        writeIntLe(wav, pcm.length);
        wav.writeBytes(pcm);
        return wav.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeIntLe(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeShortLe(ByteArrayOutputStream output, short value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    record Fixture(
            List<SpeechSample> samples,
            List<PairedSpeechQuery> queries,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            BasicAcousticFeatureEncoder encoder,
            String label,
            int k
    ) {
    }

    private record SampleSpec(
            String id,
            String speakerId,
            String transcript,
            float frequencyHz,
            SpeechCondition condition,
            SpeechTaskType taskType
    ) {
    }
}
