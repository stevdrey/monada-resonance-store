package com.monada.consumer.classpath;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.KnowledgeAtom;
import com.monada.core.MonadaRecall;
import com.monada.encoder.TextNormalizer;
import com.monada.storage.feedback.FeedbackSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Plain class-path consumer of the published Monada library artifacts.
 *
 * <p>Runs open, remember, recall, feedback and reopen against a temporary store using
 * only the published {@code com.monada:monada-api} coordinate and its transitive metadata.
 */
public final class ConsumerSmoke {

    private static final String QUERY = "database with graph and document model";

    private ConsumerSmoke() {
    }

    public static void main(String[] args) throws IOException {
        printEvidence();

        Path root = Files.createTempDirectory("monada-consumer-classpath-");
        try {
            String rememberedId;
            {
                MonadaMemory memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
                KnowledgeAtom graph = memory.remember(
                        "OrientDB is a multi-model database that combines graph and document models.");
                memory.remember("Redis is an in-memory data structure store often used as a cache.");
                rememberedId = graph.id();

                MonadaRecall recall = memory.resonate(QUERY).topK(3).threshold(0.0).execute();
                expectTop(recall, rememberedId, "initial recall");

                memory.feedback(QUERY, rememberedId, FeedbackSignal.POSITIVE);
                expectInvalidTopKRejected(memory);
            }

            MonadaMemory reopened = MonadaMemory.open(root);
            MonadaRecall recall = reopened.resonate(QUERY).topK(3).threshold(0.0).execute();
            expectTop(recall, rememberedId, "recall after reopen");
            System.out.println("[smoke] open -> remember -> recall -> feedback -> reopen: OK");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void printEvidence() {
        System.out.println("[evidence] java.version=" + System.getProperty("java.version"));
        System.out.println("[evidence] jdk.module.path=" + System.getProperty("jdk.module.path", ""));
        System.out.println("[evidence] java.class.path=" + System.getProperty("java.class.path", ""));
        List<Class<?>> exposed = List.of(
                MonadaMemory.class, KnowledgeAtom.class, TextNormalizer.class, FeedbackSignal.class);
        for (Class<?> type : exposed) {
            Module module = type.getModule();
            System.out.println("[evidence] " + type.getName() + " -> module "
                    + (module.isNamed() ? module.getName() : "<unnamed>"));
        }
        Module self = ConsumerSmoke.class.getModule();
        System.out.println("[evidence] consumer -> module " + (self.isNamed() ? self.getName() : "<unnamed>"));
        for (Class<?> type : exposed) {
            if (type.getModule().isNamed()) {
                throw new IllegalStateException(type.getName() + " should load from the class path");
            }
        }
    }

    private static void expectTop(MonadaRecall recall, String expectedId, String label) {
        if (recall.results().isEmpty()) {
            throw new IllegalStateException(label + ": no results");
        }
        String topId = recall.results().getFirst().atom().id();
        if (!expectedId.equals(topId)) {
            throw new IllegalStateException(label + ": expected top " + expectedId + " but was " + topId);
        }
        System.out.println("[smoke] " + label + ": top=" + topId
                + " score=" + recall.results().getFirst().score()
                + " results=" + recall.results().size());
    }

    private static void expectInvalidTopKRejected(MonadaMemory memory) {
        try {
            memory.resonate(QUERY).topK(0);
        } catch (IllegalArgumentException expected) {
            System.out.println("[smoke] invalid topK rejected: " + expected.getMessage());
            return;
        }
        throw new IllegalStateException("topK(0) was not rejected");
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
