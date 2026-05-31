package com.monada.storage;

import com.monada.storage.feedback.FeedbackStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileManifestStore implements ManifestStore {

    private final Path root;

    public FileManifestStore(Path root) {
        this.root = root;
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("atoms"));
        Files.createDirectories(root.resolve("vectors"));
        Files.createDirectories(root.resolve("indexes"));
        Files.createDirectories(root.resolve("feedback"));
    }

    @Override
    public Optional<Manifest> load() throws IOException {
        ensureDirectories();
        Path manifestFile = root.resolve("manifest.json");
        if (Files.notExists(manifestFile)) {
            return Optional.empty();
        }
        String json = Files.readString(manifestFile, StandardCharsets.UTF_8);
        return Optional.of(parse(json));
    }

    @Override
    public void save(Manifest manifest) throws IOException {
        ensureDirectories();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": \"").append(JsonStrings.escape(manifest.version())).append("\",\n");
        sb.append("  \"dimensions\": ").append(manifest.dimensions()).append(",\n");
        sb.append("  \"vectorSegment\": \"").append(JsonStrings.escape(manifest.vectorSegment())).append("\",\n");
        sb.append("  \"atomSegment\": \"").append(JsonStrings.escape(manifest.atomSegment())).append("\",\n");
        sb.append("  \"feedbackSegment\": \"").append(JsonStrings.escape(manifest.feedbackSegment())).append("\"");
        
        EncodingProfile profile = manifest.encodingProfile();
        if (profile != null) {
            sb.append(",\n");
            sb.append("  \"encoder\": \"").append(JsonStrings.escape(profile.encoder())).append("\",\n");
            sb.append("  \"encoderVersion\": \"").append(JsonStrings.escape(profile.encoderVersion())).append("\",\n");
            sb.append("  \"normalizer\": \"").append(JsonStrings.escape(profile.normalizer())).append("\",\n");
            sb.append("  \"normalizerVersion\": \"").append(JsonStrings.escape(profile.normalizerVersion())).append("\",\n");
            sb.append("  \"weightingStrategy\": \"").append(JsonStrings.escape(profile.weightingStrategy())).append("\",\n");
            sb.append("  \"originalWeight\": ").append(profile.originalWeight()).append(",\n");
            sb.append("  \"expansionWeight\": ").append(profile.expansionWeight()).append(",\n");
            sb.append("  \"aliasOriginalWeight\": ").append(profile.aliasOriginalWeight()).append(",\n");
            sb.append("  \"aliasExpansionWeight\": ").append(profile.aliasExpansionWeight()).append("\n");
        } else {
            sb.append("\n");
        }
        sb.append("}\n");
        Files.writeString(root.resolve("manifest.json"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static Manifest parse(String json) {
        var fields = JsonStrings.parseFlat(json, "manifest.json");
        String version = fields.get("version");
        String vectorSegment = fields.get("vectorSegment");
        String atomSegment = fields.get("atomSegment");
        String feedbackSegment = fields.getOrDefault("feedbackSegment", FeedbackStore.DEFAULT_SEGMENT);
        String dimensionsStr = fields.get("dimensions");
        if (version == null || vectorSegment == null || atomSegment == null || dimensionsStr == null) {
            throw new IllegalStateException(
                    "Invalid manifest.json: missing one of version/dimensions/vectorSegment/atomSegment");
        }
        int dimensions = Integer.parseInt(dimensionsStr);
        
        EncodingProfile profile = null;
        if (fields.containsKey("encoder")) {
            String encoder = fields.get("encoder");
            String encoderVersion = fields.get("encoderVersion");
            String normalizer = fields.get("normalizer");
            String normalizerVersion = fields.get("normalizerVersion");
            String weightingStrategy = fields.get("weightingStrategy");
            String origW = fields.get("originalWeight");
            String expW = fields.get("expansionWeight");
            String aliasOrigW = fields.get("aliasOriginalWeight");
            String aliasExpW = fields.get("aliasExpansionWeight");
            
            if (encoder != null && encoderVersion != null && normalizer != null && normalizerVersion != null 
                    && weightingStrategy != null && origW != null && expW != null && aliasOrigW != null && aliasExpW != null) {
                profile = new EncodingProfile(
                    encoder,
                    encoderVersion,
                    dimensions,
                    normalizer,
                    normalizerVersion,
                    weightingStrategy,
                    Double.parseDouble(origW),
                    Double.parseDouble(expW),
                    Double.parseDouble(aliasOrigW),
                    Double.parseDouble(aliasExpW)
                );
            }
        }
        return new Manifest(version, dimensions, vectorSegment, atomSegment, feedbackSegment, profile);
    }
}
