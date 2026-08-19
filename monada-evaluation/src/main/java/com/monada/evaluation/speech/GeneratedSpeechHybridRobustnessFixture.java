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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Deterministic corpus with strict direct-modality conflicts and explicit availability masks. */
final class GeneratedSpeechHybridRobustnessFixture {

    static final String LABEL = "generated-speech-hybrid-robustness-v1";
    private static final int SAMPLE_RATE = 16_000;
    private static final int DIMENSIONS = 64;
    private static final int K = 1;

    private GeneratedSpeechHybridRobustnessFixture() {
    }

    static Fixture create(Path root) throws IOException {
        Path audioRoot = Files.createDirectories(root.resolve("audio"));
        Path queryRoot = Files.createDirectories(root.resolve("queries"));
        Path storeRoot = root.resolve("speech-store");
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);
        BasicAcousticFeatureEncoder encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);
        List<SampleSpec> specs = List.of(
                new SampleSpec("d_text_wrong_440", "D01", "deploy service safely", 440.0f, SpeechCondition.DYSARTHRIC, SpeechTaskType.COMMAND),
                new SampleSpec("d_acoustic_target_1760", "D02", "deploy application safely", 1760.0f, SpeechCondition.DYSARTHRIC, SpeechTaskType.COMMAND),
                new SampleSpec("c_audio_wrong_880", "C01", "read service status", 880.0f, SpeechCondition.CONTROL, SpeechTaskType.COMMAND),
                new SampleSpec("c_text_target_1320", "C02", "stop service safely", 1320.0f, SpeechCondition.CONTROL, SpeechTaskType.COMMAND),
                new SampleSpec("c_agreement_1440", "C03", "restore database backup", 1440.0f, SpeechCondition.CONTROL, SpeechTaskType.SENTENCE),
                new SampleSpec("d_text_wrong_550", "D03", "rotate access token", 550.0f, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD),
                new SampleSpec("d_audio_wrong_660", "D04", "inspect audit log", 660.0f, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD),
                new SampleSpec("d_ambiguous_target_770", "D05", "rotate credential now", 770.0f, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        for (SampleSpec spec : specs.stream().sorted(Comparator.comparing(SampleSpec::id)).toList()) {
            Path audio = GeneratedSpeechModalityFixture.writeSineWav(audioRoot, spec.id(), spec.frequencyHz());
            SpeechSample sample = new SpeechSample(spec.id(), spec.speakerId(), SpeechDatasetSource.CUSTOM, audio,
                    spec.transcript(), List.of(), spec.condition(), spec.taskType(), "en-US",
                    new AudioMetadata(SAMPLE_RATE, 1, 500, "generated-" + spec.id()), Instant.EPOCH);
            sampleStore.save(sample);
            featureStore.save(sample.id(), encoder.encode(audio));
        }
        PairedSpeechQuery acousticConflict = query(queryRoot, "acoustic-conflict", 1760.0f, "deploy service safely",
                "d_acoustic_target_1760", SpeechCondition.DYSARTHRIC, SpeechTaskType.COMMAND);
        PairedSpeechQuery transcriptConflict = query(queryRoot, "transcript-conflict", 880.0f, "stop service safely",
                "c_text_target_1320", SpeechCondition.CONTROL, SpeechTaskType.COMMAND);
        PairedSpeechQuery agreement = query(queryRoot, "agreement", 1440.0f, "restore database backup",
                "c_agreement_1440", SpeechCondition.CONTROL, SpeechTaskType.SENTENCE);
        PairedSpeechQuery ambiguous = query(queryRoot, "ambiguous", 660.0f, "rotate access token",
                "d_ambiguous_target_770", SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD);
        return new Fixture(sampleStore, featureStore, encoder, List.of(
                copy(acousticConflict, "robust_acoustic_conflict_same", "D02", SpeechHybridConflictCategory.ACOUSTIC_CORRECT_TRANSCRIPT_CONFLICT, Set.of(), Set.of()),
                copy(transcriptConflict, "robust_transcript_conflict_cross", "C01", SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_ACOUSTIC_CONFLICT, Set.of(), Set.of()),
                copy(agreement, "robust_agreement_same", "C03", SpeechHybridConflictCategory.AGREEMENT_RELEVANT, Set.of(), Set.of()),
                copy(ambiguous, "robust_ambiguous_cross", "D03", SpeechHybridConflictCategory.BOTH_AMBIGUOUS, Set.of(), Set.of()),
                copy(acousticConflict, "robust_transcript_missing_same", "D02", SpeechHybridConflictCategory.TRANSCRIPT_MISSING, Set.of("d_acoustic_target_1760"), Set.of()),
                copy(transcriptConflict, "robust_acoustic_missing_cross", "C01", SpeechHybridConflictCategory.ACOUSTIC_MISSING, Set.of(), Set.of("c_text_target_1320")),
                copy(agreement, "robust_agreement_cross", "D01", SpeechHybridConflictCategory.AGREEMENT_RELEVANT, Set.of(), Set.of()),
                copy(ambiguous, "robust_ambiguous_same", "D05", SpeechHybridConflictCategory.BOTH_AMBIGUOUS, Set.of(), Set.of())));
    }

    private static PairedSpeechQuery query(Path root, String id, float frequency, String transcript, String relevant,
                                            SpeechCondition condition, SpeechTaskType task) throws IOException {
        return new PairedSpeechQuery(id, GeneratedSpeechModalityFixture.writeSineWav(root, id, frequency),
                transcript, Set.of(relevant), condition, task);
    }

    private static SpeechHybridRobustnessCase copy(PairedSpeechQuery source, String id, String speaker,
                                                    SpeechHybridConflictCategory category, Set<String> transcriptMissing,
                                                    Set<String> acousticMissing) {
        return new SpeechHybridRobustnessCase(new PairedSpeechQuery(id, source.queryAudio(), source.transcript(),
                source.relevantSampleIds(), source.condition(), source.taskType()), speaker, category,
                transcriptMissing, acousticMissing);
    }

    record Fixture(SpeechSampleStore sampleStore, SpeechFeatureStore featureStore, BasicAcousticFeatureEncoder encoder,
                   List<SpeechHybridRobustnessCase> cases) {
        Fixture {
            cases = List.copyOf(cases);
        }

        int k() {
            return K;
        }
    }

    private record SampleSpec(String id, String speakerId, String transcript, float frequencyHz,
                              SpeechCondition condition, SpeechTaskType taskType) {
    }
}
