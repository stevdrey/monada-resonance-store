package com.monada.evaluation.speech;

import com.monada.speech.retrieval.SpeechSampleRetriever;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Command-line entrypoint for the generated fixed-profile hybrid robustness and missing-modality policy studies. */
public final class SpeechHybridRobustnessMain {

    private SpeechHybridRobustnessMain() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("monada-speech-hybrid-robustness-");
        try {
            SpeechHybridRobustnessRunner runner = new SpeechHybridRobustnessRunner();
            Path developmentRoot = root.resolve("development");
            GeneratedSpeechHybridRobustnessFixture.Fixture fixture = GeneratedSpeechHybridRobustnessFixture.create(developmentRoot);
            SpeechHybridPreparedCases development = runner.prepare(
                    fixture.cases(),
                    fixture.k(),
                    new SpeechSampleRetriever(fixture.encoder()),
                    fixture.sampleStore(),
                    fixture.featureStore(),
                    developmentRoot.resolve("transcript-memory"));
            System.out.print(runner.report(SpeechModalityEvidence.GENERATED_CI,
                    GeneratedSpeechHybridRobustnessFixture.LABEL, development).render());

            Path holdoutRoot = root.resolve("holdout");
            GeneratedSpeechHybridRobustnessFixture.Fixture holdoutFixture =
                    GeneratedSpeechHybridRobustnessFixture.createHoldout(holdoutRoot);
            SpeechHybridPreparedCases holdout = runner.prepare(
                    holdoutFixture.cases(),
                    holdoutFixture.k(),
                    new SpeechSampleRetriever(holdoutFixture.encoder()),
                    holdoutFixture.sampleStore(),
                    holdoutFixture.featureStore(),
                    holdoutRoot.resolve("transcript-memory"));
            System.out.println();
            System.out.print(new SpeechMissingModalityPolicyRunner().run(SpeechModalityEvidence.GENERATED_CI, List.of(
                    new SpeechMissingModalityStudySplit(SpeechMissingModalityPolicyRunner.DEVELOPMENT_SPLIT, GeneratedSpeechHybridRobustnessFixture.LABEL, development),
                    new SpeechMissingModalityStudySplit(SpeechMissingModalityPolicyRunner.HOLDOUT_SPLIT, GeneratedSpeechHybridRobustnessFixture.HOLDOUT_LABEL, holdout)))
                    .render());
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) throw exception;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
