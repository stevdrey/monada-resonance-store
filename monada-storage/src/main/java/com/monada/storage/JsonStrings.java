package com.monada.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class JsonStrings {

    private JsonStrings() {
    }

    public static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    public static String unescape(String value, String sourceName) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= value.length()) {
                throw new IllegalStateException("Invalid trailing escape in " + sourceName);
            }
            char next = value.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        throw new IllegalStateException("Invalid \\u escape in " + sourceName);
                    }
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException(
                                "Invalid \\u hex sequence \\u" + hex + " in " + sourceName, e);
                    }
                    i += 4;
                }
                default -> throw new IllegalStateException("Invalid escape \\" + next + " in " + sourceName);
            }
        }
        return out.toString();
    }

    public static Map<String, String> parseFlat(String json, String sourceName) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(sourceName, "sourceName");
        var fields = new LinkedHashMap<String, String>();
        int i = skipWhitespace(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') {
            throw new IllegalStateException("Expected JSON object in " + sourceName);
        }
        i++;
        while (true) {
            i = skipWhitespace(json, i);
            if (i >= json.length()) {
                throw new IllegalStateException("Unclosed JSON object in " + sourceName);
            }
            if (json.charAt(i) == '}') {
                i = skipWhitespace(json, i + 1);
                if (i != json.length()) {
                    throw new IllegalStateException("Unexpected trailing content in " + sourceName);
                }
                return Map.copyOf(fields);
            }
            ParsedString key = readString(json, i, sourceName);
            i = skipWhitespace(json, key.nextIndex());
            if (i >= json.length() || json.charAt(i) != ':') {
                throw new IllegalStateException("Expected ':' after key in " + sourceName);
            }
            i = skipWhitespace(json, i + 1);
            if (i >= json.length()) {
                throw new IllegalStateException("Missing value in " + sourceName);
            }
            String value;
            if (json.charAt(i) == '"') {
                ParsedString parsedValue = readString(json, i, sourceName);
                value = parsedValue.value();
                i = parsedValue.nextIndex();
            } else {
                int valueStart = i;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') {
                    i++;
                }
                value = json.substring(valueStart, i).trim();
                if (value.isEmpty()) {
                    throw new IllegalStateException("Missing value in " + sourceName);
                }
            }
            fields.put(key.value(), value);
            i = skipWhitespace(json, i);
            if (i >= json.length()) {
                throw new IllegalStateException("Unclosed JSON object in " + sourceName);
            }
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (json.charAt(i) == '}') {
                continue;
            }
            throw new IllegalStateException("Expected ',' or '}' in " + sourceName);
        }
    }

    private static ParsedString readString(String json, int start, String sourceName) {
        if (start >= json.length() || json.charAt(start) != '"') {
            throw new IllegalStateException("Expected string in " + sourceName);
        }
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return new ParsedString(unescape(json.substring(start + 1, i), sourceName), i + 1);
            }
        }
        throw new IllegalStateException("Unclosed string in " + sourceName);
    }

    private static int skipWhitespace(String value, int start) {
        int i = start;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
            i++;
        }
        return i;
    }

    private record ParsedString(String value, int nextIndex) {
    }
}
