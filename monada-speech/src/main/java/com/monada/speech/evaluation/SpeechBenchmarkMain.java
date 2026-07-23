package com.monada.speech.evaluation;

import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
import com.monada.speech.encoder.SpeechFeatureEncodingJob;
import com.monada.speech.importer.TorgoDatasetImporter;
import com.monada.speech.importer.TorgoDatasetImportReport;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

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
 *   <li>deep diagnostics — {@code monada.speech.benchmark.diagnostics} /
 *       {@code MONADA_SPEECH_BENCHMARK_DIAGNOSTICS} (optional, default false)</li>
 * </ul>
 *
 * <h2>Query manifest format</h2>
 * UTF-8 text, one query per line. Blank lines and lines starting with {@code #} are ignored.
 * The first three tab-separated fields are:
 * <pre>
 * queryId &lt;TAB&gt; queryWavPath &lt;TAB&gt; relevantId1,relevantId2,...
 * </pre>
 * It also accepts the optional {@code topK}, dataset source, condition, task type, speaker ID,
 * and language fields defined by {@link SpeechEvaluationQueryTsvParser}.
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
            System.err.println("  -Dmonada.speech.benchmark.diagnostics=<boolean> (default false)");
            System.exit(2);
            return;
        }

        int k;
        int dimensions;
        boolean diagnostics;
        try {
            k = intConfig("monada.speech.benchmark.k", "MONADA_SPEECH_BENCHMARK_K", DEFAULT_K);
            dimensions = intConfig("monada.speech.benchmark.dims", "MONADA_SPEECH_BENCHMARK_DIMS", DEFAULT_DIMENSIONS);
            diagnostics = booleanConfig(
                    "monada.speech.benchmark.diagnostics",
                    "MONADA_SPEECH_BENCHMARK_DIAGNOSTICS",
                    false);
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
        var encodingReport = new SpeechFeatureEncodingJob(encoder).run(sampleStore, featureStore);
        System.out.println(encodingReport.render());

        var retriever = new SpeechSampleRetriever(encoder);
        var runner = new SpeechBenchmarkRunner();
        var options = new SpeechEvaluationOptions(k, true, diagnostics);

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
        return SpeechEvaluationQueryTsvParser.parse(manifest, corpusDir, k);
    }

    static Path parseConfigPath(String label, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(label + " is not a valid path: " + value, e);
        }
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

    private static boolean booleanConfig(String systemProperty, String envVar, boolean defaultValue) {
        String value = config(systemProperty, envVar);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "invalid boolean for " + systemProperty + "/" + envVar + ": " + value);
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
