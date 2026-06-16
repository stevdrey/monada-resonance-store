package com.monada.speech.importer;

import java.nio.file.Path;
import java.util.Objects;

public record TorgoDatasetImportWarning(
        Path path,
        String reason
) {
    public TorgoDatasetImportWarning {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
