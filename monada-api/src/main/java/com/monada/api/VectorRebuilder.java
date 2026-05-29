package com.monada.api;

import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.WeightedText;
import com.monada.encoder.WeightedToken;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.FileManifestStore;
import com.monada.storage.Manifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public final class VectorRebuilder {

    private VectorRebuilder() {
    }

    public static void rebuild(Path root, MonadaMemoryOptions targetOptions) throws IOException {
        var manifestStore = new FileManifestStore(root);
        var manifest = manifestStore.load()
                .orElseThrow(() -> new IllegalArgumentException("No manifest found at " + root));

        var dimensions = manifest.dimensions();
        var atomStore = new FileAtomStore(root, manifest.atomSegment());
        var atoms = atomStore.findAll();

        var tempDir = root.resolve("rebuild_temp");
        deleteDirectory(tempDir);
        Files.createDirectories(tempDir);

        try {
            var tempFreqStore = new FileFrequencyStore(tempDir, manifest.vectorSegment(), dimensions);
            var encoder = new SimpleFrequencyEncoder(dimensions);

            for (var atom : atoms) {
                var tokens = new ArrayList<WeightedToken>();
                var normalizedContent = targetOptions.textNormalizer().normalize(atom.content());
                tokens.addAll(normalizedContent.toWeightedText(targetOptions.expansionOptions()).tokens());

                if (!atom.aliases().isEmpty()) {
                    var aliasOptions = new LexicalExpansionOptions(
                            targetOptions.expansionOptions().expansionWeight(),
                            Math.min(targetOptions.expansionOptions().expansionWeight(),
                                    targetOptions.expansionOptions().expansionWeight() * targetOptions.expansionOptions().expansionWeight())
                    );
                    tokens.addAll(
                            atom.aliases().stream()
                                    .map(targetOptions.textNormalizer()::normalize)
                                    .flatMap(normalized -> normalized.toWeightedText(aliasOptions).tokens().stream())
                                    .toList()
                    );
                }

                WeightedText weightedText = new WeightedText(tokens);
                tempFreqStore.save(atom.id(), encoder.encode(weightedText));
            }

            var targetVectorFile = root.resolve(manifest.vectorSegment());
            var targetVectorMap = FileFrequencyStore.resolveVectorMapPath(root);
            var targetManifestFile = root.resolve("manifest.json");

            var tempVectorFile = tempDir.resolve(manifest.vectorSegment());
            var tempVectorMap = FileFrequencyStore.resolveVectorMapPath(tempDir);

            Files.createDirectories(targetVectorFile.getParent());
            Files.createDirectories(targetVectorMap.getParent());

            var targetVectorFileBak = root.resolve(manifest.vectorSegment() + ".bak");
            var targetVectorMapBak = targetVectorMap.resolveSibling(targetVectorMap.getFileName().toString() + ".bak");
            var targetManifestFileBak = root.resolve("manifest.json.bak");

            // Track each artifact that has actually been moved to its backup, so the
            // rollback path can restore exactly what was moved even if a failure
            // occurs midway through the backup phase.
            boolean vectorFileBackedUp = false;
            boolean vectorMapBackedUp = false;
            boolean manifestBackedUp = false;
            boolean transactionCommitted = false;
            try {
                if (Files.exists(targetVectorFile)) {
                    Files.move(targetVectorFile, targetVectorFileBak, StandardCopyOption.REPLACE_EXISTING);
                    vectorFileBackedUp = true;
                }
                if (Files.exists(targetVectorMap)) {
                    Files.move(targetVectorMap, targetVectorMapBak, StandardCopyOption.REPLACE_EXISTING);
                    vectorMapBackedUp = true;
                }
                if (Files.exists(targetManifestFile)) {
                    Files.move(targetManifestFile, targetManifestFileBak, StandardCopyOption.REPLACE_EXISTING);
                    manifestBackedUp = true;
                }

                Files.move(tempVectorFile, targetVectorFile, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempVectorMap, targetVectorMap, StandardCopyOption.REPLACE_EXISTING);

                var profile = MonadaMemory.getExpectedProfile(dimensions, targetOptions);
                var updatedManifest = new Manifest(
                        "0.3",
                        dimensions,
                        manifest.vectorSegment(),
                        manifest.atomSegment(),
                        manifest.feedbackSegment(),
                        profile
                );
                manifestStore.save(updatedManifest);
                transactionCommitted = true;

                // Deleting backup files on success. Failures during cleanup should not trigger rollback
                // since the transaction is already fully committed.
                try {
                    if (Files.exists(targetVectorFileBak)) {
                        Files.delete(targetVectorFileBak);
                    }
                    if (Files.exists(targetVectorMapBak)) {
                        Files.delete(targetVectorMapBak);
                    }
                    if (Files.exists(targetManifestFileBak)) {
                        Files.delete(targetManifestFileBak);
                    }
                } catch (IOException cleanupEx) {
                    // Suppress backup cleanup exceptions so they don't fail the overall successful rebuild
                }
            } catch (IOException e) {
                if (!transactionCommitted) {
                    try {
                        if (vectorFileBackedUp && Files.exists(targetVectorFileBak)) {
                            Files.move(targetVectorFileBak, targetVectorFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (vectorMapBackedUp && Files.exists(targetVectorMapBak)) {
                            Files.move(targetVectorMapBak, targetVectorMap, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (manifestBackedUp && Files.exists(targetManifestFileBak)) {
                            Files.move(targetManifestFileBak, targetManifestFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException rollbackEx) {
                        e.addSuppressed(rollbackEx);
                    }
                }
                throw e;
            }

        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                var paths = stream.sorted((p1, p2) -> p2.compareTo(p1)).toList();
                for (var p : paths) {
                    Files.delete(p);
                }
            }
        }
    }
}
