package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechSample;
import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
import com.monada.speech.importer.TorgoDatasetImporter;
import com.monada.speech.importer.TorgoDatasetImportReport;
import com.monada.speech.retrieval.SpeechRetrievalOptions;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Command-line entrypoint for the <strong>exploratory</strong> local real-data speech benchmark.
 *
 * <p>This entrypoint is intentionally <em>not</em> exercised in CI: the protected, CI-enforced
 * baseline lives in the generated-fixture integration test. This main is for running the same
 * evaluation pipeline against a local TORGO-style corpus that stays outside git. It performs no
 * downloads; the corpus must already exist on disk.
 *
 * <h2>Configuration</h2>
 * Each setting is read from a JVM system property first, then an environment variable:
 * <ul>
 *   <li>corpus dir — {@code monada.speech.benchmark.dir} / {@code MONADA_SPEECH_BENCHMARK_DIR} (required)</li>
 *   <li>query manifest — {@code monada.speech.benchmark.queries} / {@code MONADA_SPEECH_BENCHMARK_QUERIES} (required)</li>
 *   <li>k — {@code monada.speech.benchmark.k} / {@code MONADA_SPEECH_BENCHMARK_K} (optional, default 5)</li>
 *   <li>encoder dims — {@code monada.speech.benchmark.dims} / {@code MONADA_SPEECH_BENCHMARK_DIMS} (optional, default 64)</li>
 * </ul>
 *
 * <h2>Query manifest format</h2>
 * UTF-8 text, one query per line. Blank lines and lines starting with {@code #} are ignored.
 * Each line has three tab-separated fields:
 * <pre>
 * queryId &lt;TAB&gt; queryWavPath &lt;TAB&gt; relevantId1,relevantId2,...
 * </pre>
 * {@code queryWavPath} may be absolute or relative to the corpus dir. Relevant IDs are the
 * stable sample IDs produced by {@link TorgoDatasetImporter} (e.g. {@code torgo_m01_session1_words_hello}).
 */
public final class SpeechBenchmarkMain {

    private static final int DEFAULT_K = 5;
    private static final int DEFAULT_DIMENSIONS = 64;

    private SpeechBenchmarkMain() {
    }

    public static void main(String[] args) throws IOException {
        String corpusDirValue = config("monada.speech.benchmark.dir", "MONADA_SPEECH_BENCHMARK_DIR");
        String queriesValue = config("monada.speech.benchmark.queries", "MONADA_SPEECH_BENCHMARK_QUERIES");

        if (corpusDirValue == null || queriesValue == null) {
            System.err.println("Exploratory speech benchmark (local real-data, NOT enforced in CI)");
            System.err.println();
            System.err.println("Required configuration is missing. Provide both:");
            System.err.println("  -Dmonada.speech.benchmark.dir=<corpus dir>      (or MONADA_SPEECH_BENCHMARK_DIR)");
            System.err.println("  -Dmonada.speech.benchmark.queries=<manifest>    (or MONADA_SPEECH_BENCHMARK_QUERIES)");
            System.err.println("Optional:");
            System.err.println("  -Dmonada.speech.benchmark.k=<k>                 (default " + DEFAULT_K + ")");
            System.err.println("  -Dmonada.speech.benchmark.dims=<dimensions>     (default " + DEFAULT_DIMENSIONS + ")");
            System.exit(2);
            return;
        }

        int k;
        int dimensions;
        try {
            k = intConfig("monada.speech.benchmark.k", "MONADA_SPEECH_BENCHMARK_K", DEFAULT_K);
            dimensions = intConfig("monada.speech.benchmark.dims", "MONADA_SPEECH_BENCHMARK_DIMS", DEFAULT_DIMENSIONS);
            if (k <= 0) {
                throw new IllegalArgumentException("k must be positive: " + k);
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("dimensions must be positive: " + dimensions);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("invalid benchmark configuration: " + e.getMessage());
            System.exit(2);
            return;
        }

        Path corpusDir;
        Path manifest;
        try {
            corpusDir = parseConfigPath("corpus dir", corpusDirValue);
            manifest = parseConfigPath("query manifest", queriesValue);
        } catch (IllegalArgumentException e) {
            System.err.println("invalid benchmark configuration: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (!Files.isDirectory(corpusDir)) {
            System.err.println("corpus dir does not exist or is not a directory: " + corpusDir);
            System.exit(2);
            return;
        }
        if (!Files.isRegularFile(manifest)) {
            System.err.println("query manifest does not exist or is not a file: " + manifest);
            System.exit(2);
            return;
        }

        List<SpeechEvaluationQuery> queries = parseQueries(manifest, corpusDir, k);
        if (queries.isEmpty()) {
            System.err.println("no queries parsed from manifest: " + manifest);
            System.exit(2);
            return;
        }

        Path storeRoot = Files.createTempDirectory("monada-speech-benchmark-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                deleteRecursively(storeRoot);
            } catch (IOException e) {
                System.err.println("warning: failed to clean up benchmark temp directory "
                        + storeRoot + ": " + e.getMessage());
            }
        }));
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);

        BasicAcousticFeatureEncoder encoder;
        try {
            encoder = new BasicAcousticFeatureEncoder(dimensions);
        } catch (IllegalArgumentException e) {
            System.err.println("invalid encoder dimensions: " + e.getMessage());
            System.exit(2);
            return;
        }

        TorgoDatasetImportReport importReport = new TorgoDatasetImporter().importFrom(corpusDir, sampleStore);
        for (SpeechSample sample : sampleStore.findAll()) {
            featureStore.save(sample.id(), encoder.encode(sample.audioPath()));
        }

        var retriever = new SpeechSampleRetriever(encoder);
        var runner = new SpeechBenchmarkRunner();
        var options = new SpeechEvaluationOptions(k, true);

        SpeechBenchmarkReport report = runner.run(
                SpeechBenchmarkMode.EXPLORATORY,
                corpusDir.getFileName() == null ? corpusDir.toString() : corpusDir.getFileName().toString(),
                queries,
                retriever,
                sampleStore,
                featureStore,
                options);

        System.out.println(report.render());
        System.out.println("Imported samples: " + importReport.importedSamples()
                + " (discovered " + importReport.discoveredAudioFiles() + ", skipped " + importReport.skippedSamples() + ")");
    }

    static List<SpeechEvaluationQuery> parseQueries(Path manifest, Path corpusDir, int k)
            throws IOException {
        List<SpeechEvaluationQuery> queries = new ArrayList<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
            String line = lines.get(lineNo - 1);
            String trimmedLine = line.strip();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                throw new IOException("manifest line " + lineNo
                        + " must have exactly 3 tab-separated fields (queryId, queryWavPath, relevantIds): " + line);
            }
            String queryId = fields[0].strip();
            if (queryId.isEmpty()) {
                throw new IOException("manifest line " + lineNo + " has a blank queryId: " + line);
            }
            String queryWavField = fields[1].strip();
            if (queryWavField.isEmpty()) {
                throw new IOException("manifest line " + lineNo + " has a blank queryWavPath: " + line);
            }
            Path queryWav;
            try {
                queryWav = resolveAudio(corpusDir, queryWavField);
            } catch (InvalidPathException e) {
                throw new IOException("manifest line " + lineNo
                        + " has an invalid queryWavPath: " + queryWavField, e);
            }
            Set<String> relevant = parseRelevant(fields[2]);
            if (relevant.isEmpty()) {
                throw new IOException("manifest line " + lineNo + " has no relevant sample IDs: " + line);
            }
            if (!Files.isRegularFile(queryWav)) {
                throw new IOException("manifest line " + lineNo
                        + " references a query WAV that does not exist: " + queryWav);
            }
            queries.add(new SpeechEvaluationQuery(
                    queryId,
                    queryWav,
                    relevant,
                    new SpeechRetrievalOptions(k, null, null, null, null, null)));
        }
        return queries;
    }

    static Path parseConfigPath(String label, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(label + " is not a valid path: " + value, e);
        }
    }

    private static Set<String> parseRelevant(String field) {
        Set<String> relevant = new LinkedHashSet<>();
        for (String id : field.split(",")) {
            String trimmed = id.strip();
            if (!trimmed.isEmpty()) {
                relevant.add(trimmed);
            }
        }
        return relevant;
    }

    private static Path resolveAudio(Path corpusDir, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path : corpusDir.resolve(path);
    }

    private static String config(String systemProperty, String envVar) {
        String fromProperty = System.getProperty(systemProperty);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.strip();
        }
        String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        return null;
    }

    private static int intConfig(String systemProperty, String envVar, int defaultValue) {
        String value = config(systemProperty, envVar);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid integer for " + systemProperty + "/" + envVar + ": " + value, e);
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
