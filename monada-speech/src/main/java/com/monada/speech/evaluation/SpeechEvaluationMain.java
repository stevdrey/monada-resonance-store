package com.monada.speech.evaluation;

import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line entrypoint for evaluating an existing local speech store.
 *
 * <p>The store and query definition file remain outside the repository. This command only
 * reads them and emits a deterministic exploratory report; it does not import data or write
 * feature vectors.
 */
public final class SpeechEvaluationMain {

    private static final int DEFAULT_K = 5;
    private static final int DEFAULT_DIMENSIONS = 64;
    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_EVALUATION_FAILURE = 1;
    private static final int EXIT_CONFIGURATION_FAILURE = 2;
    private static final String SAMPLE_LOG = "samples/speech-samples-000001.jsonl";
    private static final String FEATURE_FILE = "features/speech-features-000001.f32";
    private static final String FEATURE_INDEX = "indexes/speech-feature-map.idx";

    private SpeechEvaluationMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(systemProperties(), System.getenv(), System.out, System.err);
        if (exitCode != EXIT_SUCCESS) {
            System.exit(exitCode);
        }
    }

    static int run(
            Map<String, String> systemProperties,
            Map<String, String> environment,
            PrintStream out,
            PrintStream err
    ) {
        EvaluationConfiguration configuration;
        List<SpeechEvaluationQuery> queries;
        try {
            configuration = parseConfiguration(systemProperties, environment);
            validateStoreLayout(configuration.storeRoot());
            queries = SpeechEvaluationQueryTsvParser.parse(
                    configuration.queryManifest(), queryBaseDirectory(configuration.queryManifest()), configuration.k());
            if (queries.isEmpty()) {
                throw new IllegalArgumentException("no queries parsed from manifest: " + configuration.queryManifest());
            }
        } catch (IllegalArgumentException | IOException e) {
            err.println("invalid speech evaluation configuration: " + e.getMessage());
            return EXIT_CONFIGURATION_FAILURE;
        }

        try {
            var sampleStore = new FileSpeechSampleStore(configuration.storeRoot());
            var featureStore = new FileSpeechFeatureStore(configuration.storeRoot());
            if (!sampleStore.hasSamples()) {
                throw new IllegalArgumentException("speech store has no samples: " + configuration.storeRoot());
            }
            if (featureStore.validateStoredVectorDimensions(configuration.dimensions()) == 0) {
                throw new IllegalArgumentException("speech store has no feature vectors: " + configuration.storeRoot());
            }

            var encoder = new BasicAcousticFeatureEncoder(configuration.dimensions());
            var report = new SpeechBenchmarkRunner().run(
                    SpeechBenchmarkMode.EXPLORATORY,
                    configuration.label(),
                    queries,
                    new SpeechSampleRetriever(encoder),
                    sampleStore,
                    featureStore,
                    new SpeechEvaluationOptions(configuration.k(), true));
            out.print(report.render());
            return EXIT_SUCCESS;
        } catch (IllegalArgumentException e) {
            err.println("invalid speech evaluation configuration: " + e.getMessage());
            return EXIT_CONFIGURATION_FAILURE;
        } catch (IOException e) {
            err.println("speech evaluation failed: " + e.getMessage());
            return EXIT_EVALUATION_FAILURE;
        } catch (RuntimeException e) {
            err.println("speech evaluation failed: " + e.getMessage());
            return EXIT_EVALUATION_FAILURE;
        }
    }

    static EvaluationConfiguration parseConfiguration(
            Map<String, String> systemProperties,
            Map<String, String> environment
    ) {
        String storeValue = configurationValue(
                systemProperties, environment, "monada.speech.evaluation.store", "MONADA_SPEECH_EVALUATION_STORE");
        String queriesValue = configurationValue(
                systemProperties, environment, "monada.speech.evaluation.queries", "MONADA_SPEECH_EVALUATION_QUERIES");
        if (storeValue == null || queriesValue == null) {
            throw new IllegalArgumentException("provide both -Dmonada.speech.evaluation.store=<store root> "
                    + "and -Dmonada.speech.evaluation.queries=<queries.tsv> "
                    + "(or MONADA_SPEECH_EVALUATION_STORE and MONADA_SPEECH_EVALUATION_QUERIES)");
        }

        Path storeRoot = parseConfigPath("store root", storeValue);
        Path queryManifest = parseConfigPath("query manifest", queriesValue);
        int k = parsePositiveInt(systemProperties, environment,
                "monada.speech.evaluation.k", "MONADA_SPEECH_EVALUATION_K", DEFAULT_K, "k");
        int dimensions = parsePositiveInt(systemProperties, environment,
                "monada.speech.evaluation.dims", "MONADA_SPEECH_EVALUATION_DIMS", DEFAULT_DIMENSIONS, "dimensions");
        String label = configurationValue(
                systemProperties, environment, "monada.speech.evaluation.label", "MONADA_SPEECH_EVALUATION_LABEL");
        if (label == null) {
            Path fileName = storeRoot.getFileName();
            label = fileName == null ? storeRoot.toString() : fileName.toString();
        }
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        return new EvaluationConfiguration(storeRoot, queryManifest, k, dimensions, label);
    }

    static Path parseConfigPath(String label, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(label + " is not a valid path: " + value, e);
        }
    }

    private static void validateStoreLayout(Path storeRoot) {
        if (!Files.isDirectory(storeRoot)) {
            throw new IllegalArgumentException("store root does not exist or is not a directory: " + storeRoot);
        }
        requireRegularFile(storeRoot.resolve(SAMPLE_LOG), "speech sample log");
        requireRegularFile(storeRoot.resolve(FEATURE_FILE), "speech feature segment");
        requireRegularFile(storeRoot.resolve(FEATURE_INDEX), "speech feature index");
    }

    private static void requireRegularFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " does not exist or is not a file: " + path);
        }
    }

    private static Path queryBaseDirectory(Path manifest) {
        Path parent = manifest.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("query manifest has no parent directory: " + manifest);
        }
        return parent;
    }

    private static int parsePositiveInt(
            Map<String, String> systemProperties,
            Map<String, String> environment,
            String property,
            String environmentVariable,
            int defaultValue,
            String label
    ) {
        String value = configurationValue(systemProperties, environment, property, environmentVariable);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(label + " must be positive: " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer for " + property + "/" + environmentVariable
                    + ": " + value, e);
        }
    }

    private static String configurationValue(
            Map<String, String> systemProperties,
            Map<String, String> environment,
            String property,
            String environmentVariable
    ) {
        String fromProperty = systemProperties.get(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.strip();
        }
        String fromEnvironment = environment.get(environmentVariable);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.strip();
        }
        return null;
    }

    private static Map<String, String> systemProperties() {
        Map<String, String> properties = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            properties.put(name, System.getProperty(name));
        }
        return Map.copyOf(properties);
    }

    record EvaluationConfiguration(Path storeRoot, Path queryManifest, int k, int dimensions, String label) {
    }
}
