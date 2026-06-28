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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p><strong>Layout assumption:</strong> this implementation supports a
 * conservative normalized layout
 * ({@code <speakerId>/<session>/<taskType>/<file>.wav}). Not every possible
 * real TORGO directory variant is handled — future phases may extend coverage.
 */
public final class TorgoDatasetImporter {

    /**
     * Imports WAV files found under {@code datasetRoot} into {@code sampleStore}.
     *
     * <p>This is a convenience wrapper around
     * {@link #auditFrom(Path, SpeechSampleStore)} that returns the lightweight
     * {@link TorgoDatasetImportReport} for backward compatibility.
     *
     * @param datasetRoot root directory to scan; must exist
     * @param sampleStore store to persist valid samples into
     * @return an import report with counts and any warnings
     * @throws IOException if scanning the directory tree fails
     */
    public TorgoDatasetImportReport importFrom(Path datasetRoot, SpeechSampleStore sampleStore)
            throws IOException {
        var audit = auditFrom(datasetRoot, sampleStore);
        var warnings = audit.warningGroups().values().stream()
                .flatMap(group ->
                    group.pathExamples().stream()
                        .map(p -> new TorgoDatasetImportWarning(p, group.category().displayName()))
                )
                .toList();
        return new TorgoDatasetImportReport(
                audit.discoveredAudioFiles(),
                audit.importedSamples(),
                audit.skippedSamples(),
                warnings);
    }

    /**
     * Scans or imports WAV files found under {@code datasetRoot} and returns a
     * rich {@link TorgoImportAuditReport} with grouped counts and categorised
     * warnings.
     *
     * <p>When {@code sampleStore} is {@code null} the method performs a
     * <em>dry-run</em>: all validation and inference steps run as normal but no
     * samples are written. The resulting report has {@code importedSamples == 0}
     * and {@code dryRun == true}; the grouped maps ({@code bySpeaker}, etc.)
     * reflect what <em>would</em> have been imported.
     *
     * <p>When {@code sampleStore} is non-{@code null} the method behaves like
     * {@link #importFrom} but additionally populates the grouped maps.
     *
     * @param datasetRoot root directory to scan; must exist
     * @param sampleStore store to persist valid samples into, or {@code null}
     *                    for a dry-run scan
     * @return a rich audit report
     * @throws IOException if scanning the directory tree fails
     */
    public TorgoImportAuditReport auditFrom(Path datasetRoot, SpeechSampleStore sampleStore)
            throws IOException {
        if (!Files.isDirectory(datasetRoot)) {
            throw new IOException("datasetRoot does not exist or is not a directory: " + datasetRoot);
        }

        boolean dryRun = (sampleStore == null);
        Instant importTime = Instant.now();

        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        int imported = 0;
        int skipped = 0;

        Map<String, Integer> bySpeaker = new LinkedHashMap<>();
        Map<SpeechCondition, Integer> byCondition = new EnumMap<>(SpeechCondition.class);
        Map<SpeechTaskType, Integer> byTaskType = new EnumMap<>(SpeechTaskType.class);
        Map<String, Integer> byLanguage = new LinkedHashMap<>();
        Map<WarningCategory, List<Path>> warningPaths = new EnumMap<>(WarningCategory.class);

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
                recordWarning(warningPaths, WarningCategory.UNREADABLE_TRANSCRIPT, wavPath);
                skipped++;
                continue;
            }

            if (transcript.isEmpty()) {
                boolean companionExists = Files.exists(siblingTxt(wavPath));
                WarningCategory cat = companionExists
                        ? WarningCategory.BLANK_TRANSCRIPT
                        : WarningCategory.MISSING_TRANSCRIPT;
                recordWarning(warningPaths, cat, wavPath);
                skipped++;
                continue;
            }

            // Read audio metadata
            AudioMetadata audioMetadata;
            try {
                audioMetadata = WavMetadataReader.read(wavPath);
            } catch (IOException e) {
                recordWarning(warningPaths, WarningCategory.UNREADABLE_AUDIO, wavPath);
                skipped++;
                continue;
            }

            // Infer path-based fields
            Optional<String> speakerIdOpt = TorgoPathInference.speakerId(datasetRoot, wavPath);
            if (speakerIdOpt.isEmpty()) {
                recordWarning(warningPaths, WarningCategory.UNSUPPORTED_LAYOUT, wavPath);
                skipped++;
                continue;
            }
            String speakerId = speakerIdOpt.get();
            SpeechCondition condition = TorgoPathInference.condition(speakerId);
            SpeechTaskType taskType = TorgoPathInference.taskType(datasetRoot, wavPath);
            String language = "en-US";

            // Derive deterministic stable ID
            String sampleId = deriveId(datasetRoot, wavPath);

            // Duplicate detection
            if (seenIds.contains(sampleId)) {
                recordWarning(warningPaths, WarningCategory.DUPLICATE_ID, wavPath);
                skipped++;
                continue;
            }
            seenIds.add(sampleId);

            // Accumulate grouped counts
            bySpeaker.merge(speakerId, 1, Integer::sum);
            byCondition.merge(condition, 1, Integer::sum);
            byTaskType.merge(taskType, 1, Integer::sum);
            byLanguage.merge(language, 1, Integer::sum);

            if (!dryRun) {
                SpeechSample sample = new SpeechSample(
                        sampleId,
                        speakerId,
                        SpeechDatasetSource.TORGO,
                        wavPath,
                        transcript.get(),
                        List.of(),
                        condition,
                        taskType,
                        language,
                        audioMetadata,
                        importTime
                );
                sampleStore.save(sample);
                imported++;
            }
        }

        Map<WarningCategory, TorgoAuditWarningGroup> warningGroups = new EnumMap<>(WarningCategory.class);
        for (Map.Entry<WarningCategory, List<Path>> entry : warningPaths.entrySet()) {
            List<Path> paths = entry.getValue();
            int count = paths.size();
            List<Path> examples = paths.subList(0, Math.min(count, TorgoAuditWarningGroup.MAX_EXAMPLES));
            warningGroups.put(entry.getKey(),
                    new TorgoAuditWarningGroup(entry.getKey(), count, examples));
        }

        return new TorgoImportAuditReport(
                discovered,
                imported,
                skipped,
                dryRun,
                bySpeaker,
                byCondition,
                byTaskType,
                byLanguage,
                warningGroups);
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

    private static void recordWarning(Map<WarningCategory, List<Path>> warningPaths,
                                      WarningCategory category, Path path) {
        warningPaths.computeIfAbsent(category, k -> new ArrayList<>()).add(path);
    }

    private static Path siblingTxt(Path wavPath) {
        String filename = wavPath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return wavPath.resolveSibling(stem + ".txt");
    }
}
