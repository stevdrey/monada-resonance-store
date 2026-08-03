package com.monada.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/** Loads and indexes readable, versioned text baseline snapshots from classpath resources. */
final class TextBaselineRegistry {

    private static final String RESOURCE_ROOT = "/com/monada/evaluation/baselines/";
    private static final String INDEX_RESOURCE = RESOURCE_ROOT + "index.txt";

    private final Map<BaselineKey, TextEvaluationBaseline> baselines;

    private TextBaselineRegistry(List<TextEvaluationBaseline> entries) {
        Objects.requireNonNull(entries, "entries");
        var indexed = new LinkedHashMap<BaselineKey, TextEvaluationBaseline>();
        for (var baseline : entries) {
            var key = new BaselineKey(
                    baseline.datasetName(), baseline.datasetVersion(), baseline.profileName());
            var prior = indexed.putIfAbsent(key, baseline);
            if (prior != null) {
                throw new IllegalArgumentException("duplicate baseline identity: " + key.render());
            }
        }
        baselines = Map.copyOf(indexed);
    }

    static TextBaselineRegistry loadDefault() {
        var indexResource = TextBaselineRegistry.class.getResourceAsStream(INDEX_RESOURCE);
        if (indexResource == null) {
            throw new IllegalStateException("required baseline resource not found: " + INDEX_RESOURCE);
        }
        try (var index = indexResource;
             var reader = new InputStreamReader(index, StandardCharsets.UTF_8)) {
            return load(reader, resourceName -> TextBaselineRegistry.class.getResourceAsStream(
                    RESOURCE_ROOT + resourceName));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load text baseline registry", e);
        }
    }

    static TextBaselineRegistry load(
            Reader indexReader,
            Function<String, InputStream> resourceLoader) {
        Objects.requireNonNull(indexReader, "indexReader");
        Objects.requireNonNull(resourceLoader, "resourceLoader");
        var entries = new ArrayList<TextEvaluationBaseline>();
        var resourceNames = new HashSet<String>();
        try (var lines = new java.io.BufferedReader(indexReader)) {
            String line;
            while ((line = lines.readLine()) != null) {
                var resourceName = line.strip();
                if (resourceName.isEmpty() || resourceName.startsWith("#")) {
                    continue;
                }
                if (resourceName.contains("/") || resourceName.contains("\\")) {
                    throw new IllegalArgumentException(
                            "baseline index entries must be filenames: " + resourceName);
                }
                if (!resourceNames.add(resourceName)) {
                    throw new IllegalArgumentException(
                            "duplicate baseline resource in index: " + resourceName);
                }
                var resource = resourceLoader.apply(resourceName);
                if (resource == null) {
                    throw new IllegalArgumentException(
                            "baseline resource listed in index was not found: " + resourceName);
                }
                try (resource;
                     var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                    entries.add(parse(resourceName, reader));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load text baseline registry", e);
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("baseline index must contain at least one resource");
        }
        return new TextBaselineRegistry(entries);
    }

    static TextEvaluationBaseline parse(String resourceName, Reader reader) {
        Objects.requireNonNull(resourceName, "resourceName");
        Objects.requireNonNull(reader, "reader");
        return new BaselineResourceParser(resourceName, reader).parse();
    }

    TextEvaluationBaseline require(TextEvaluationMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.mode() != TextEvaluationMode.PROTECTED) {
            throw new IllegalArgumentException(
                    "baseline registry only serves PROTECTED metadata but was " + metadata.mode());
        }
        var key = new BaselineKey(
                metadata.datasetName(), metadata.datasetVersion(), metadata.profileName());
        var baseline = baselines.get(key);
        if (baseline == null) {
            throw new IllegalArgumentException("no baseline registered for " + key.render());
        }
        return baseline;
    }

    private record BaselineKey(String datasetName, String datasetVersion, String profileName) {
        private String render() {
            return "dataset=" + datasetName + ", version=" + datasetVersion + ", profile=" + profileName;
        }
    }

    private static final class BaselineResourceParser {

        private final String resourceName;
        private final Reader reader;

        private BaselineResourceParser(String resourceName, Reader reader) {
            this.resourceName = resourceName;
            this.reader = reader;
        }

        private TextEvaluationBaseline parse() {
            var properties = new Properties();
            try {
                properties.load(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read baseline resource: " + resourceName, e);
            }

            var datasetName = required(properties, "dataset");
            var datasetVersion = required(properties, "datasetVersion");
            var profileName = required(properties, "profile");
            var tolerance = parseDouble(required(properties, "tolerance"), "tolerance");
            var metrics = new LinkedHashMap<String, TextBaselineExpectation>();

            for (var key : properties.stringPropertyNames().stream().sorted().toList()) {
                if (key.equals("dataset") || key.equals("datasetVersion")
                        || key.equals("profile") || key.equals("tolerance")) {
                    continue;
                }
                if (!key.startsWith("metric.")) {
                    throw new IllegalArgumentException(
                            "unknown baseline property '" + key + "' in " + resourceName);
                }
                var metricKey = key.substring("metric.".length());
                metrics.put(metricKey, parseExpectation(properties.getProperty(key), key));
            }

            return new TextEvaluationBaseline(
                    datasetName, datasetVersion, profileName, tolerance, metrics);
        }

        private TextBaselineExpectation parseExpectation(String rawValue, String propertyName) {
            if (rawValue == null || rawValue.isBlank()) {
                throw new IllegalArgumentException(
                        "blank baseline property '" + propertyName + "' in " + resourceName);
            }
            var separator = rawValue.indexOf(':');
            if (separator <= 0 || separator == rawValue.length() - 1) {
                throw new IllegalArgumentException(
                        "baseline metric must use POLICY:value in '" + propertyName + "' in " + resourceName);
            }
            var rawPolicy = rawValue.substring(0, separator).strip();
            var rawExpected = rawValue.substring(separator + 1).strip();
            final TextBaselinePolicy policy;
            try {
                policy = TextBaselinePolicy.valueOf(rawPolicy);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown baseline policy '" + rawPolicy + "' in " + resourceName, e);
            }
            return new TextBaselineExpectation(
                    policy, parseDouble(rawExpected, propertyName));
        }

        private String required(Properties properties, String key) {
            var value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "missing or blank baseline property '" + key + "' in " + resourceName);
            }
            return value.strip();
        }

        private double parseDouble(String value, String propertyName) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "invalid number for baseline property '" + propertyName + "' in " + resourceName
                                + ": " + value,
                        e);
            }
        }
    }
}
