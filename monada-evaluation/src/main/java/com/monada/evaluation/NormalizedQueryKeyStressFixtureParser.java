package com.monada.evaluation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Parser for the versioned normalized query-key stress TSV fixture. */
final class NormalizedQueryKeyStressFixtureParser {

    private static final int FIELD_COUNT = 11;

    private final InputStream input;
    private final String sourceName;

    NormalizedQueryKeyStressFixtureParser(InputStream input, String sourceName) {
        this.input = Objects.requireNonNull(input, "input");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
    }

    List<NormalizedQueryKeyStressCase> parse() throws IOException {
        var cases = new ArrayList<NormalizedQueryKeyStressCase>();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                cases.add(parseLine(line, lineNumber));
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("fixture contains no cases: " + sourceName);
        }
        return List.copyOf(cases);
    }

    private NormalizedQueryKeyStressCase parseLine(String line, int lineNumber) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != FIELD_COUNT) {
            throw invalid(lineNumber, "must contain " + FIELD_COUNT
                    + " tab-separated fields, found " + fields.length, null);
        }
        try {
            return new NormalizedQueryKeyStressCase(
                    fields[0],
                    NormalizationStressCategory.valueOf(fields[1].toUpperCase(Locale.ROOT)),
                    NormalizationSemanticRelationship.valueOf(fields[2].toUpperCase(Locale.ROOT)),
                    decodeQuery(fields[3]),
                    decodeQuery(fields[4]),
                    parseTargets(fields[5]),
                    parseTargets(fields[6]),
                    fields[7],
                    ExpectedQueryKeyRelation.valueOf(fields[8].toUpperCase(Locale.ROOT)),
                    Double.parseDouble(fields[9]),
                    Instant.parse(fields[10]));
        } catch (RuntimeException e) {
            throw invalid(lineNumber, e.getMessage(), e);
        }
    }

    private Set<String> parseTargets(String field) {
        var targets = new LinkedHashSet<String>();
        for (String value : field.split(",", -1)) {
            String target = value.strip();
            if (target.isEmpty()) {
                throw new IllegalArgumentException("target sets must not contain blank labels");
            }
            if (!targets.add(target)) {
                throw new IllegalArgumentException("duplicate target label: " + target);
            }
        }
        return Set.copyOf(targets);
    }

    private String decodeQuery(String value) {
        var decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (index + 1 >= value.length()) {
                throw new IllegalArgumentException("query contains a trailing escape");
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case 't' -> decoded.append('\t');
                case 'n' -> decoded.append('\n');
                case '\\' -> decoded.append('\\');
                default -> throw new IllegalArgumentException("unsupported query escape: \\" + escaped);
            }
        }
        return decoded.toString();
    }

    private IllegalArgumentException invalid(int lineNumber, String message, RuntimeException cause) {
        String fullMessage = sourceName + ":" + lineNumber + " is invalid: " + message;
        return cause == null
                ? new IllegalArgumentException(fullMessage)
                : new IllegalArgumentException(fullMessage, cause);
    }
}
