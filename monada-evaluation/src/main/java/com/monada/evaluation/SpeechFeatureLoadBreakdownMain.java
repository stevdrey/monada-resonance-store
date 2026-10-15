package com.monada.evaluation;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Entry point for the exploratory speech feature load breakdown (Issue #136):
 *
 * <pre>
 *     ./gradlew :monada-evaluation:runSpeechFeatureLoadBreakdown -q
 * </pre>
 *
 * <p>Splits {@code FileSpeechFeatureStore.findAll()} into index parse, seek, header read, value read and
 * materialization at N=100/1,000/5,000/10,000 for 64 and 128 dimensions, and decides whether a bounded windowed
 * sequential read is justified.
 */
public final class SpeechFeatureLoadBreakdownMain {

    private SpeechFeatureLoadBreakdownMain() {
    }

    public static void main(String[] args) throws IOException {
        var workdir = Files.createTempDirectory("monada-speech-feature-load-");
        try {
            var report = new SpeechFeatureLoadBreakdownRunner().run(workdir);
            System.out.println(TextEvaluationCatalog.SPEECH_FEATURE_LOAD_BREAKDOWN.render(report.render()));
        } finally {
            EvaluationTempDirectories.deleteRecursively(workdir);
        }
    }
}
