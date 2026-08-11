package com.monada.evaluation;

import com.monada.storage.feedback.FeedbackSignal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Parser for the deterministic query-key strategy comparison TSV fixture. */
final class FeedbackQueryKeyComparisonFixtureParser {

    private static final int FIELD_COUNT = 9;

    private final InputStream input;
    private final String sourceName;

    FeedbackQueryKeyComparisonFixtureParser(InputStream input, String sourceName) {
        this.input = Objects.requireNonNull(input, "input");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
    }

    List<FeedbackQueryKeyComparisonCase> parse() throws IOException {
        var cases = new ArrayList<FeedbackQueryKeyComparisonCase>();
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

    private FeedbackQueryKeyComparisonCase parseLine(String line, int lineNumber) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    sourceName + ":" + lineNumber + " must contain " + FIELD_COUNT
                            + " tab-separated fields, found " + fields.length);
        }
        try {
            return new FeedbackQueryKeyComparisonCase(
                    fields[0],
                    FeedbackQueryKeyComparisonCaseCategory.valueOf(fields[1].toUpperCase(Locale.ROOT)),
                    fields[2],
                    fields[3],
                    fields[4],
                    FeedbackSignal.valueOf(fields[5].toUpperCase(Locale.ROOT)),
                    Double.parseDouble(fields[6]),
                    Instant.parse(fields[7]),
                    parseBoolean(fields[8]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    sourceName + ":" + lineNumber + " is invalid: " + e.getMessage(), e);
        }
    }

    private boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false, got: " + value);
    }
}
