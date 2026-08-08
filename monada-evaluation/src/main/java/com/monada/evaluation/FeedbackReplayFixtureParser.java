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

/** Parses deterministic, inspectable TSV feedback replay fixtures. */
final class FeedbackReplayFixtureParser {

    private static final int FIELD_COUNT = 7;

    private final BufferedReader reader;
    private final String sourceName;

    FeedbackReplayFixtureParser(InputStream input, String sourceName) {
        Objects.requireNonNull(input, "input");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    List<FeedbackReplayEvent> parse() throws IOException {
        var events = new ArrayList<FeedbackReplayEvent>();
        try (reader) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    continue;
                }
                events.add(parseLine(line, lineNumber));
            }
        }
        if (events.isEmpty()) {
            throw new IOException(sourceName + " contains no feedback replay events");
        }
        return List.copyOf(events);
    }

    private FeedbackReplayEvent parseLine(String line, int lineNumber)
            throws IOException {
        String[] fields = line.split("\\t", -1);
        if (fields.length != FIELD_COUNT) {
            throw invalidLine(lineNumber,
                    "must contain exactly 7 tab-separated fields", line, null);
        }
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].strip();
            if (fields[i].isEmpty()) {
                throw invalidLine(lineNumber,
                        "contains a blank field at column " + (i + 1), line, null);
            }
        }

        try {
            return new FeedbackReplayEvent(
                    fields[0],
                    fields[1],
                    fields[2],
                    FeedbackSignal.valueOf(fields[3].toUpperCase(Locale.ROOT)),
                    Double.parseDouble(fields[4]),
                    Instant.parse(fields[5]),
                    FeedbackReplayExpectedScope.valueOf(fields[6].toUpperCase(Locale.ROOT)));
        } catch (RuntimeException e) {
            throw invalidLine(lineNumber, "is invalid", line, e);
        }
    }

    private IOException invalidLine(
            int lineNumber,
            String message,
            String line,
            Exception cause) {
        String fullMessage = sourceName + " line " + lineNumber + " " + message + ": " + line;
        return cause == null ? new IOException(fullMessage) : new IOException(fullMessage, cause);
    }
}
