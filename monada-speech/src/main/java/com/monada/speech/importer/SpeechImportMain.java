package com.monada.speech.importer;

import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
import com.monada.speech.encoder.SpeechFeatureEncodingJob;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import com.monada.speech.storage.StoredSpeechFeatureVector;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line entrypoint for importing a local TORGO-style corpus into a persistent speech store.
 *
 * <p>The command creates the standard sample, feature, and index files beneath the configured
 * store root when they do not exist. Re-running it reuses existing feature vectors; it never
 * overwrites them or mixes feature dimensions in one store.
 */
public final class SpeechImportMain {

    private static final int DEFAULT_DIMENSIONS = 64;
    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_IMPORT_FAILURE = 1;
    private static final int EXIT_CONFIGURATION_FAILURE = 2;

    private SpeechImportMain() {
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
        ImportConfiguration configuration;
        try {
            configuration = parseConfiguration(systemProperties, environment);
        } catch (IllegalArgumentException e) {
            err.println("invalid speech import configuration: " + e.getMessage());
            return EXIT_CONFIGURATION_FAILURE;
        }

        try {
            var sampleStore = new FileSpeechSampleStore(configuration.storeRoot());
            var featureStore = new FileSpeechFeatureStore(configuration.storeRoot());
            validateExistingFeatureDimensions(featureStore.findAll(), configuration.dimensions());

            TorgoDatasetImportReport importReport = new TorgoDatasetImporter().importFrom(
                    configuration.corpusDirectory(), sampleStore);
            var encodingReport = new SpeechFeatureEncodingJob(
                    new BasicAcousticFeatureEncoder(configuration.dimensions()))
                    .run(sampleStore, featureStore);

            out.print(renderImportReport(configuration, importReport));
            out.print(encodingReport.render());
            return EXIT_SUCCESS;
        } catch (IllegalArgumentException e) {
            err.println("invalid speech import configuration: " + e.getMessage());
            return EXIT_CONFIGURATION_FAILURE;
        } catch (IOException | RuntimeException e) {
            err.println("speech import failed: " + e.getMessage());
            return EXIT_IMPORT_FAILURE;
        }
    }

    static ImportConfiguration parseConfiguration(
            Map<String, String> systemProperties,
            Map<String, String> environment
    ) {
        String corpusValue = configurationValue(
                systemProperties, environment, "monada.speech.import.dir", "MONADA_SPEECH_IMPORT_DIR");
        String storeValue = configurationValue(
                systemProperties, environment, "monada.speech.import.store", "MONADA_SPEECH_IMPORT_STORE");
        if (corpusValue == null || storeValue == null) {
            throw new IllegalArgumentException("provide both -Dmonada.speech.import.dir=<corpus directory> "
                    + "and -Dmonada.speech.import.store=<store root> "
                    + "(or MONADA_SPEECH_IMPORT_DIR and MONADA_SPEECH_IMPORT_STORE)");
        }

        Path corpusDirectory = parseConfigPath("corpus directory", corpusValue);
        if (!Files.isDirectory(corpusDirectory)) {
            throw new IllegalArgumentException("corpus directory does not exist or is not a directory: " + corpusDirectory);
        }
        Path storeRoot = parseConfigPath("store root", storeValue);
        if (Files.exists(storeRoot) && !Files.isDirectory(storeRoot)) {
            throw new IllegalArgumentException("store root exists but is not a directory: " + storeRoot);
        }
        int dimensions = parsePositiveInt(systemProperties, environment,
                "monada.speech.import.dims", "MONADA_SPEECH_IMPORT_DIMS", DEFAULT_DIMENSIONS);
        return new ImportConfiguration(corpusDirectory, storeRoot, dimensions);
    }

    static Path parseConfigPath(String label, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(label + " is not a valid path: " + value, e);
        }
    }

    private static void validateExistingFeatureDimensions(
            List<StoredSpeechFeatureVector> vectors,
            int expectedDimensions
    ) {
        for (StoredSpeechFeatureVector vector : vectors) {
            int actualDimensions = vector.vector().dimensions();
            if (actualDimensions != expectedDimensions) {
                throw new IllegalArgumentException("feature vector dimensions for sample " + vector.sampleId()
                        + " are " + actualDimensions + ", expected " + expectedDimensions
                        + "; use a separate store or the original encoder dimensions");
            }
        }
    }

    private static String renderImportReport(
            ImportConfiguration configuration,
            TorgoDatasetImportReport report
    ) {
        StringBuilder output = new StringBuilder();
        output.append("Monada Speech Import Report\n");
        output.append("==========================\n");
        output.append("Corpus: ").append(configuration.corpusDirectory()).append('\n');
        output.append("Store: ").append(configuration.storeRoot()).append('\n');
        output.append("Encoder dimensions: ").append(configuration.dimensions()).append('\n');
        output.append("Discovered audio files: ").append(report.discoveredAudioFiles()).append('\n');
        output.append("Imported samples: ").append(report.importedSamples()).append('\n');
        output.append("Skipped samples: ").append(report.skippedSamples()).append('\n');
        if (!report.warnings().isEmpty()) {
            output.append("Warnings:\n");
            for (TorgoDatasetImportWarning warning : report.warnings()) {
                output.append("- ").append(warning.path()).append(": ").append(warning.reason()).append('\n');
            }
        }
        output.append('\n');
        return output.toString();
    }

    private static int parsePositiveInt(
            Map<String, String> systemProperties,
            Map<String, String> environment,
            String property,
            String environmentVariable,
            int defaultValue
    ) {
        String value = configurationValue(systemProperties, environment, property, environmentVariable);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("dimensions must be positive: " + parsed);
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

    record ImportConfiguration(Path corpusDirectory, Path storeRoot, int dimensions) {
    }
}
