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
            var targetVectorMap = root.resolve("indexes/vector-map.idx");

            var tempVectorFile = tempDir.resolve(manifest.vectorSegment());
            var tempVectorMap = tempDir.resolve("indexes/vector-map.idx");

            Files.createDirectories(targetVectorFile.getParent());
            Files.createDirectories(targetVectorMap.getParent());

            var targetVectorFileBak = root.resolve(manifest.vectorSegment() + ".bak");
            var targetVectorMapBak = root.resolve("indexes/vector-map.idx.bak");

            boolean backedUp = false;
            try {
                if (Files.exists(targetVectorFile)) {
                    Files.move(targetVectorFile, targetVectorFileBak, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(targetVectorMap)) {
                    Files.move(targetVectorMap, targetVectorMapBak, StandardCopyOption.REPLACE_EXISTING);
                }
                backedUp = true;

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

                if (Files.exists(targetVectorFileBak)) {
                    Files.delete(targetVectorFileBak);
                }
                if (Files.exists(targetVectorMapBak)) {
                    Files.delete(targetVectorMapBak);
                }
            } catch (IOException e) {
                if (backedUp) {
                    try {
                        if (Files.exists(targetVectorFileBak)) {
                            Files.move(targetVectorFileBak, targetVectorFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (Files.exists(targetVectorMapBak)) {
                            Files.move(targetVectorMapBak, targetVectorMap, StandardCopyOption.REPLACE_EXISTING);
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
