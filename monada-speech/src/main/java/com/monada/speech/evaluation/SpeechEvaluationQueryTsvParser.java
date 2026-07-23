package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.retrieval.SpeechRetrievalOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses inspectable TSV query definitions for acoustic speech evaluation.
 *
 * <p>Each non-comment line has from three to nine tab-separated fields:
 * {@code queryId}, {@code queryWavPath}, {@code relevantIds}, optional {@code topK},
 * optional {@code datasetSource}, optional {@code condition}, optional {@code taskType},
 * optional {@code speakerId}, and optional {@code language}. Empty optional fields retain
 * their default or disable the corresponding retrieval filter.
 */
final class SpeechEvaluationQueryTsvParser {

    private static final int REQUIRED_FIELD_COUNT = 3;
    private static final int MAX_FIELD_COUNT = 9;

    private SpeechEvaluationQueryTsvParser() {
    }

    static List<SpeechEvaluationQuery> parse(Path manifest, Path audioBaseDirectory, int evaluationK)
            throws IOException {
        if (evaluationK <= 0) {
            throw new IllegalArgumentException("evaluationK must be positive: " + evaluationK);
        }

        List<SpeechEvaluationQuery> queries = new ArrayList<>();
        Set<String> queryIds = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
            String line = lines.get(lineNo - 1);
            String trimmedLine = line.strip();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }

            String[] fields = line.split("\\t", -1);
            if (fields.length < REQUIRED_FIELD_COUNT || fields.length > MAX_FIELD_COUNT) {
                throw invalidLine(lineNo, "must have from 3 to 9 tab-separated fields", line);
            }

            String queryId = requiredField(fields, 0, "queryId", lineNo, line);
            if (!queryIds.add(queryId)) {
                throw invalidLine(lineNo, "duplicates queryId: " + queryId, line);
            }
            String queryWavField = requiredField(fields, 1, "queryWavPath", lineNo, line);
            Path queryWav = resolveAudio(audioBaseDirectory, queryWavField, lineNo, line);

            Set<String> relevantIds = parseRelevant(fields[2]);
            if (relevantIds.isEmpty()) {
                throw invalidLine(lineNo, "has no relevant sample IDs", line);
            }
            if (!Files.isRegularFile(queryWav)) {
                throw invalidLine(lineNo, "references a query WAV that does not exist: " + queryWav, line);
            }

            int topK = parseTopK(optionalField(fields, 3), evaluationK, lineNo, line);
            if (topK < evaluationK) {
                throw invalidLine(lineNo, "topK (" + topK + ") must be >= evaluation k ("
                        + evaluationK + ")", line);
            }

            queries.add(new SpeechEvaluationQuery(
                    queryId,
                    queryWav,
                    relevantIds,
                    new SpeechRetrievalOptions(
                            topK,
                            parseEnum(optionalField(fields, 4), SpeechDatasetSource.class, "datasetSource", lineNo, line),
                            parseEnum(optionalField(fields, 5), SpeechCondition.class, "condition", lineNo, line),
                            parseEnum(optionalField(fields, 6), SpeechTaskType.class, "taskType", lineNo, line),
                            optionalField(fields, 7),
                            optionalField(fields, 8))));
        }
        return List.copyOf(queries);
    }

    private static String requiredField(String[] fields, int index, String name, int lineNo, String line)
            throws IOException {
        String value = optionalField(fields, index);
        if (value == null) {
            throw invalidLine(lineNo, "has a blank " + name, line);
        }
        return value;
    }

    private static String optionalField(String[] fields, int index) {
        if (index >= fields.length) {
            return null;
        }
        String value = fields[index].strip();
        return value.isEmpty() ? null : value;
    }

    private static Path resolveAudio(Path audioBaseDirectory, String value, int lineNo, String line)
            throws IOException {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path : audioBaseDirectory.resolve(path);
        } catch (InvalidPathException e) {
            throw invalidLine(lineNo, "has an invalid queryWavPath: " + value, line, e);
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

    private static int parseTopK(String value, int defaultValue, int lineNo, String line) throws IOException {
        if (value == null) {
            return defaultValue;
        }
        try {
            int topK = Integer.parseInt(value);
            if (topK <= 0) {
                throw invalidLine(lineNo, "topK must be positive: " + value, line);
            }
            return topK;
        } catch (NumberFormatException e) {
            throw invalidLine(lineNo, "has an invalid topK: " + value, line, e);
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            String value, Class<E> enumType, String fieldName, int lineNo, String line) throws IOException {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalidLine(lineNo, "has an invalid " + fieldName + ": " + value, line, e);
        }
    }

    private static IOException invalidLine(int lineNo, String message, String line) {
        return new IOException("manifest line " + lineNo + " " + message + ": " + line);
    }

    private static IOException invalidLine(int lineNo, String message, String line, Exception cause) {
        return new IOException("manifest line " + lineNo + " " + message + ": " + line, cause);
    }
}
