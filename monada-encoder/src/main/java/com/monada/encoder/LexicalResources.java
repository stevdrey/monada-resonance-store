package com.monada.encoder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LexicalResources {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final String BASE_PATH = "com/monada/encoder/lexical/";

    private LexicalResources() {
    }

    static LexicalConfig loadDefault() {
        return load(DEFAULT_LANGUAGE);
    }

    static LexicalConfig load(String language) {
        var base = BASE_PATH + language + "/";
        return new LexicalConfig(
                loadStopWords(base + "stopwords.txt"),
                loadPlurals(base + "plurals.properties"),
                loadSynonyms(base + "synonyms.properties")
        );
    }

    private static Set<String> loadStopWords(String resource) {
        try (var input = open(resource);
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            var words = new LinkedHashSet<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                var value = line.strip();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    words.add(value);
                }
            }
            return Collections.unmodifiableSet(words);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load lexical stop words resource: " + resource, e);
        }
    }

    private static Map<String, String> loadPlurals(String resource) {
        return loadKeyValues(resource);
    }

    private static Map<String, List<String>> loadSynonyms(String resource) {
        Map<String, String> values = loadKeyValues(resource);
        var synonyms = new LinkedHashMap<String, List<String>>();
        for (var entry : values.entrySet()) {
            List<String> expansions = List.of(entry.getValue().split(",")).stream()
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .toList();
            synonyms.put(entry.getKey(), expansions);
        }
        return Collections.unmodifiableMap(synonyms);
    }

    private static Map<String, String> loadKeyValues(String resource) {
        try (var input = open(resource);
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            var values = new LinkedHashMap<String, String>();
            String line;
            while ((line = reader.readLine()) != null) {
                var stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    continue;
                }
                var separator = stripped.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalStateException("Malformed lexical resource line in " + resource + ": " + line);
                }
                var key = stripped.substring(0, separator).strip();
                var value = stripped.substring(separator + 1).strip();
                if (key.isEmpty() || value.isEmpty()) {
                    throw new IllegalStateException("Malformed lexical resource line in " + resource + ": " + line);
                }
                values.put(key, value);
            }
            return Collections.unmodifiableMap(values);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load lexical properties resource: " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        var input = LexicalResources.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("Missing lexical resource: " + resource);
        }
        return input;
    }

    record LexicalConfig(Set<String> stopWords, Map<String, String> plurals,
                         Map<String, List<String>> synonyms) {
    }
}
