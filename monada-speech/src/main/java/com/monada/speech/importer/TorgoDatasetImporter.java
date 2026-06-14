package com.monada.speech.importer;

import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Imports TORGO-style speech samples from a local directory tree into a
 * {@link SpeechSampleStore}.
 *
 * <p>The importer:
 * <ol>
 *   <li>Recursively discovers {@code .wav} files under {@code datasetRoot}.</li>
 *   <li>Sorts them lexicographically for deterministic import order.</li>
 *   <li>Resolves transcripts from sibling {@code .txt} files; skips samples
 *       with missing or blank transcripts.</li>
 *   <li>Reads audio metadata (sample rate, channels, duration, SHA-256) from
 *       each WAV file using JDK NIO.</li>
 *   <li>Infers speaker ID, {@link SpeechCondition}, and {@link SpeechTaskType}
 *       from path segment naming conventions.</li>
 *   <li>Derives a deterministic, stable sample ID from the dataset-root-relative
 *       path so repeated imports of the same layout produce the same IDs.</li>
 *   <li>Detects duplicate sample IDs within a single import run and skips
 *       subsequent occurrences with a warning.</li>
 * </ol>
 *
 * <p>All {@code createdAt} timestamps are set to the instant the import run
 * starts, ensuring consistency across a single batch.
 *
 * <p>No real TORGO dataset download is required; any WAV + sibling {@code .txt}
 * layout matching the conventions is accepted.
 */
public final class TorgoDatasetImporter {

    /**
     * Imports WAV files found under {@code datasetRoot} into {@code sampleStore}.
     *
     * @param datasetRoot root directory to scan; must exist
     * @param sampleStore store to persist valid samples into
     * @return an import report with counts and any warnings
     * @throws IOException if scanning the directory tree fails
     */
    public TorgoDatasetImportReport importFrom(Path datasetRoot, SpeechSampleStore sampleStore)
            throws IOException {
        if (!Files.isDirectory(datasetRoot)) {
            throw new IOException("datasetRoot does not exist or is not a directory: " + datasetRoot);
        }

        Instant importTime = Instant.now();
        List<TorgoDatasetImportWarning> warnings = new ArrayList<>();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        int imported = 0;
        int skipped = 0;

        List<Path> wavFiles;
        try (var stream = Files.walk(datasetRoot)) {
            wavFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wav"))
                    .sorted()
                    .toList();
        }

        int discovered = wavFiles.size();

        for (Path wavPath : wavFiles) {
            // Resolve transcript
            Optional<String> transcript;
            try {
                transcript = TorgoTranscriptResolver.resolve(wavPath);
            } catch (IOException e) {
                warnings.add(new TorgoDatasetImportWarning(wavPath,
                        "unreadable transcript file: " + e.getMessage()));
                skipped++;
                continue;
            }

            if (transcript.isEmpty()) {
                boolean companionExists = Files.exists(siblingTxt(wavPath));
                String reason = companionExists ? "blank transcript" : "missing transcript";
                warnings.add(new TorgoDatasetImportWarning(wavPath, reason));
                skipped++;
                continue;
            }

            // Read audio metadata
            AudioMetadata audioMetadata;
            try {
                audioMetadata = WavMetadataReader.read(wavPath);
            } catch (IOException e) {
                warnings.add(new TorgoDatasetImportWarning(wavPath,
                        "unreadable or unsupported audio file: " + e.getMessage()));
                skipped++;
                continue;
            }

            // Infer path-based fields
            String speakerId = TorgoPathInference.speakerId(datasetRoot, wavPath);
            SpeechCondition condition = TorgoPathInference.condition(speakerId);
            SpeechTaskType taskType = TorgoPathInference.taskType(datasetRoot, wavPath);

            // Derive deterministic stable ID
            String sampleId = deriveId(datasetRoot, wavPath);

            // Duplicate detection
            if (seenIds.contains(sampleId)) {
                warnings.add(new TorgoDatasetImportWarning(wavPath,
                        "duplicate sample id: " + sampleId));
                skipped++;
                continue;
            }
            seenIds.add(sampleId);

            SpeechSample sample = new SpeechSample(
                    sampleId,
                    speakerId,
                    SpeechDatasetSource.TORGO,
                    wavPath,
                    transcript.get(),
                    List.of(),
                    condition,
                    taskType,
                    "en-US",
                    audioMetadata,
                    importTime
            );

            sampleStore.save(sample);
            imported++;
        }

        return new TorgoDatasetImportReport(discovered, imported, skipped, warnings);
    }

    /**
     * Derives a deterministic, stable sample ID from the dataset-root-relative
     * path of the WAV file.
     *
     * <p>The relative path components are joined with {@code _}, the
     * {@code .wav} extension is removed, and the result is lower-cased with a
     * {@code torgo_} prefix.
     *
     * <p>Example: {@code datasetRoot/M01/Session1/words/hello.wav}
     * → {@code torgo_m01_session1_words_hello}
     */
    static String deriveId(Path datasetRoot, Path wavPath) {
        Path relative = datasetRoot.relativize(wavPath);
        String joined = relative.toString()
                .replace(java.io.File.separatorChar, '_')
                .replace('/', '_');
        if (joined.toLowerCase().endsWith(".wav")) {
            joined = joined.substring(0, joined.length() - 4);
        }
        return "torgo_" + joined.toLowerCase();
    }

    private static Path siblingTxt(Path wavPath) {
        String filename = wavPath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return wavPath.resolveSibling(stem + ".txt");
    }
}
