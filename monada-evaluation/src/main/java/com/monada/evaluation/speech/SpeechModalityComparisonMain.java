package com.monada.evaluation.speech;

import com.monada.speech.retrieval.SpeechSampleRetriever;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Command-line entrypoint for the deterministic generated modality comparison. */
public final class SpeechModalityComparisonMain {

    private SpeechModalityComparisonMain() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("monada-speech-modality-comparison-");
        try {
            GeneratedSpeechModalityFixture.Fixture fixture = GeneratedSpeechModalityFixture.create(root);
            var report = new SpeechModalityComparisonRunner().run(
                    SpeechModalityEvidence.GENERATED_CI,
                    fixture.label(),
                    fixture.queries(),
                    fixture.k(),
                    new SpeechSampleRetriever(fixture.encoder()),
                    fixture.sampleStore(),
                    fixture.featureStore(),
                    root.resolve("transcript-memory"));
            System.out.print(report.render());
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
