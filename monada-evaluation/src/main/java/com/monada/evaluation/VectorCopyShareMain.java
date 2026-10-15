package com.monada.evaluation;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for the exploratory vector copy-share measurement (Issue #135):
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runVectorCopyShare -q
 * </pre>
 *
 * <p>Measures how much of {@code FileFrequencyStore.findAll()} and {@code FileSpeechFeatureStore.findAll()} is
 * spent on {@code FrequencyVector}'s defensive copy at N=100/1,000/5,000/10,000, in time and allocated bytes,
 * and decides whether a no-copy construction path is justified.
 */
public final class VectorCopyShareMain {

    private VectorCopyShareMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-vector-copy-share-");
        try {
            var report = new VectorCopyShareRunner().run(workdir);
            System.out.println(TextEvaluationCatalog.VECTOR_COPY_SHARE.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
