package com.monada.storage.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Test helpers for locating, mutating and fingerprinting ledger files. */
public final class LedgerFiles {
    private LedgerFiles() {
    }

    public static Path segment(Path root) {
        return root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE))
                .resolve("ledger").resolve("events-000001.log");
    }

    public static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    public static void writeLines(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /** Relative path to SHA-256 of every regular file under {@code root}, sorted; includes empty directories. */
    public static Map<String, String> digests(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(p -> {
                try {
                    String key = root.relativize(p).toString();
                    if (Files.isDirectory(p)) {
                        result.put(key + "/", "dir");
                    } else {
                        result.put(key, RecordLine.sha256Hex(Files.readAllBytes(p)));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return result;
    }
}
