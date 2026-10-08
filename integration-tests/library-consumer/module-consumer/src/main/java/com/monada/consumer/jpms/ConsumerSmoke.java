package com.monada.consumer.jpms;

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
 * JPMS (module-path) consumer of the published Monada library artifacts.
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

        Path root = Files.createTempDirectory("monada-consumer-jpms-");
        try {
            String rememberedId;
            double scoreBeforeFeedback;
            {
                MonadaMemory memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
                KnowledgeAtom graph = memory.remember(
                        "OrientDB is a multi-model database that combines graph and document models.");
                memory.remember("Redis is an in-memory data structure store often used as a cache.");
                rememberedId = graph.id();

                MonadaRecall recall = memory.resonate(QUERY).topK(3).threshold(0.0).execute();
                scoreBeforeFeedback = expectTop(recall, rememberedId, "initial recall");

                memory.feedback(QUERY, rememberedId, FeedbackSignal.POSITIVE);
                expectInvalidTopKRejected(memory);
            }

            MonadaMemory reopened = MonadaMemory.open(root);
            MonadaRecall recall = reopened.resonate(QUERY).topK(3).threshold(0.0).execute();
            double scoreAfterReopen = expectTop(recall, rememberedId, "recall after reopen");
            expectFeedbackPersisted(scoreBeforeFeedback, scoreAfterReopen);
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
        expectModule(MonadaMemory.class, "com.monada.api");
        expectModule(KnowledgeAtom.class, "com.monada.core");
        expectModule(TextNormalizer.class, "com.monada.encoder");
        expectModule(FeedbackSignal.class, "com.monada.storage");
        expectModule(ConsumerSmoke.class, "com.monada.consumer.jpms");
        for (String name : List.of("com.monada.index", "com.monada.learning")) {
            if (ModuleLayer.boot().findModule(name).isEmpty()) {
                throw new IllegalStateException("Runtime module not resolved: " + name);
            }
            System.out.println("[evidence] boot layer resolved " + name);
        }
    }

    private static double expectTop(MonadaRecall recall, String expectedId, String label) {
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
        return recall.results().getFirst().score();
    }

    private static void expectFeedbackPersisted(double scoreBeforeFeedback, double scoreAfterReopen) {
        // Feedback-aware ranking is the default, so the persisted POSITIVE event must
        // raise the reopened score above the score observed before feedback was recorded.
        if (!(scoreAfterReopen > scoreBeforeFeedback)) {
            throw new IllegalStateException("feedback not persisted across reopen: score before feedback "
                    + scoreBeforeFeedback + ", after reopen " + scoreAfterReopen);
        }
        System.out.println("[smoke] feedback persisted across reopen: score "
                + scoreBeforeFeedback + " -> " + scoreAfterReopen);
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

    private static void expectModule(Class<?> type, String expected) {
        Module module = type.getModule();
        if (!module.isNamed() || !expected.equals(module.getName())) {
            throw new IllegalStateException(type.getName() + " expected in module " + expected
                    + " but was " + (module.isNamed() ? module.getName() : "<unnamed>"));
        }
    }
}
