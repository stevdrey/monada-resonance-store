package com.monada.storage.audit;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.storage.EncodingProfile;
import com.monada.storage.JsonStrings;
import com.monada.storage.Manifest;
import com.monada.storage.VectorFormatProfile;
import com.monada.storage.feedback.FeedbackSignal;
import com.monada.storage.feedback.FeedbackStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class StorageIntegrityAuditor {

    private static final String DEFAULT_ATOM_SEGMENT = "atoms/segment-000001.log";
    private static final String DEFAULT_VECTOR_SEGMENT = "vectors/segment-000001.f32";
    private static final String DEFAULT_VECTOR_MAP = "indexes/vector-map.idx";
    private static final String DEFAULT_FEEDBACK_SEGMENT = FeedbackStore.DEFAULT_SEGMENT;

    public StorageIntegrityReport audit(Path storeRoot) {
        Objects.requireNonNull(storeRoot, "storeRoot");
        List<StorageIntegrityFinding> findings = new ArrayList<>();

        if (Files.notExists(storeRoot)) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.SEGMENT_MISSING,
                    storeRoot.toString(),
                    "Store root directory does not exist"));
            return new StorageIntegrityReport(storeRoot, null, findings, StorageIntegrityStatistics.empty());
        }

        if (!Files.isDirectory(storeRoot)) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    storeRoot.toString(),
                    "Store root path is not a directory"));
            return new StorageIntegrityReport(storeRoot, null, findings, StorageIntegrityStatistics.empty());
        }

        // 1. Audit Manifest
        ParsedManifestResult manifestResult = auditManifest(storeRoot, findings);
        Manifest manifest = manifestResult.manifest();
        String atomSegment = manifest != null ? manifest.atomSegment() : DEFAULT_ATOM_SEGMENT;
        String vectorSegment = manifest != null ? manifest.vectorSegment() : DEFAULT_VECTOR_SEGMENT;
        String feedbackSegment = manifest != null ? manifest.feedbackSegment() : DEFAULT_FEEDBACK_SEGMENT;
        Integer declaredDimensions = manifest != null ? manifest.dimensions() : null;
        VectorFormatProfile vectorFormatProfile = manifest != null ? manifest.vectorFormatProfile() : null;

        // 2. Audit Segment Existence
        Path atomPath = storeRoot.resolve(atomSegment);
        Path vectorPath = storeRoot.resolve(vectorSegment);
        Path vectorMapPath = storeRoot.resolve(DEFAULT_VECTOR_MAP);
        Path feedbackPath = storeRoot.resolve(feedbackSegment);

        if (Files.notExists(atomPath)) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.SEGMENT_MISSING,
                    atomSegment,
                    "Atom segment file does not exist: " + atomSegment));
        }

        if (Files.notExists(vectorPath)) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.SEGMENT_MISSING,
                    vectorSegment,
                    "Vector segment file does not exist: " + vectorSegment));
        }

        if (Files.notExists(vectorMapPath)) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.SEGMENT_MISSING,
                    DEFAULT_VECTOR_MAP,
                    "Vector index file does not exist: " + DEFAULT_VECTOR_MAP));
        }

        // 3. Audit Atom Log
        AtomLogAuditResult atomResult = auditAtomLog(atomPath, atomSegment, findings);

        // 4. Audit Vector Map
        VectorMapAuditResult vectorMapResult = auditVectorMap(vectorMapPath, findings);

        // 5. Audit Vector Segment Structures and Framing
        auditVectorSegment(vectorPath, vectorSegment, declaredDimensions, vectorFormatProfile, vectorMapResult.entries(), findings);

        // 6. Audit Referential Consistency between Atoms and Vectors
        auditReferentialConsistency(atomSegment, atomResult.activeAtoms().keySet(), vectorMapResult.atomIdToOffsets().keySet(), findings);

        // 7. Audit Feedback Log
        long feedbackRecords = auditFeedbackLog(feedbackPath, feedbackSegment, atomResult.activeAtoms().keySet(), findings);

        // Compute overall statistics
        long atomPhysicalRecords = atomResult.physicalRecords();
        long activeUniqueAtoms = atomResult.activeAtoms().size();
        long atomHistoryDuplicates = atomResult.historyDuplicates();

        long vectorIndexEntries = vectorMapResult.totalEntries();
        long uniqueVectorIndexIds = vectorMapResult.atomIdToOffsets().size();
        long duplicateVectorIndexIds = vectorMapResult.atomIdToOffsets().values().stream()
                .mapToLong(offsets -> Math.max(0, offsets.size() - 1))
                .sum();
        long distinctVectorOffsets = vectorMapResult.offsetToAtomIds().size();
        long duplicateVectorOffsets = vectorMapResult.offsetToAtomIds().values().stream()
                .mapToLong(atomIds -> Math.max(0, atomIds.size() - 1))
                .sum();

        Set<String> activeAtomIds = atomResult.activeAtoms().keySet();
        Set<String> vectorIndexAtomIds = vectorMapResult.atomIdToOffsets().keySet();

        long activeAtomsWithVector = activeAtomIds.stream().filter(vectorIndexAtomIds::contains).count();
        long activeAtomsWithoutVector = activeAtomIds.size() - activeAtomsWithVector;
        long vectorEntriesWithoutActiveAtom = vectorIndexAtomIds.stream().filter(id -> !activeAtomIds.contains(id)).count();

        StorageIntegrityStatistics statistics = new StorageIntegrityStatistics(
                atomPhysicalRecords,
                activeUniqueAtoms,
                atomHistoryDuplicates,
                vectorIndexEntries,
                uniqueVectorIndexIds,
                duplicateVectorIndexIds,
                distinctVectorOffsets,
                duplicateVectorOffsets,
                activeAtomsWithVector,
                activeAtomsWithoutVector,
                vectorEntriesWithoutActiveAtom,
                feedbackRecords
        );

        return new StorageIntegrityReport(storeRoot, manifest, findings, statistics);
    }

    private ParsedManifestResult auditManifest(Path storeRoot, List<StorageIntegrityFinding> findings) {
        Path manifestPath = storeRoot.resolve("manifest.json");
        if (Files.notExists(manifestPath)) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "manifest.json does not exist in store root"));
            return new ParsedManifestResult(null);
        }

        String json;
        try {
            json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Failed to read manifest.json: " + e.getMessage()));
            return new ParsedManifestResult(null);
        }

        Map<String, String> fields;
        try {
            fields = JsonStrings.parseFlat(json, "manifest.json");
        } catch (RuntimeException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Malformed JSON in manifest.json: " + e.getMessage()));
            return new ParsedManifestResult(null);
        }

        String version = fields.get("version");
        String vectorSegment = fields.get("vectorSegment");
        String atomSegment = fields.get("atomSegment");
        String feedbackSegment = fields.getOrDefault("feedbackSegment", DEFAULT_FEEDBACK_SEGMENT);
        String dimensionsStr = fields.get("dimensions");

        if (version == null) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "manifest.json missing required field: version"));
        }
        if (vectorSegment == null) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "manifest.json missing required field: vectorSegment"));
        }
        if (atomSegment == null) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "manifest.json missing required field: atomSegment"));
        }

        Integer dimensions = null;
        if (dimensionsStr == null) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "manifest.json missing required field: dimensions"));
        } else {
            try {
                dimensions = Integer.parseInt(dimensionsStr);
                if (dimensions <= 0) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.FATAL,
                            StorageIntegrityCategory.MANIFEST_INVALID,
                            "manifest.json",
                            "manifest.json dimensions must be greater than zero: " + dimensions));
                    dimensions = null;
                }
            } catch (NumberFormatException e) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.FATAL,
                        StorageIntegrityCategory.MANIFEST_INVALID,
                        "manifest.json",
                        "manifest.json dimensions must be a valid integer: " + dimensionsStr));
            }
        }

        VectorFormatProfile vectorFormatProfile = null;
        if (version != null) {
            if ("0.4".equals(version)) {
                vectorFormatProfile = validateManifest04PhysicalFields(fields, dimensions, findings);
            } else if ("0.3".equals(version) || "0.2".equals(version) || "0.1".equals(version)) {
                validateLegacyManifestPhysicalAbsence(version, fields, findings);
                if (dimensions != null) {
                    vectorFormatProfile = VectorFormatProfile.currentFixedRaw(dimensions);
                }
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.INFO,
                        StorageIntegrityCategory.FORMAT_INCOMPATIBLE,
                        "manifest.json",
                        "Manifest version is legacy " + version + "; fixed-frame Float32 big-endian format is inferred"));
            } else {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.FATAL,
                        StorageIntegrityCategory.FORMAT_INCOMPATIBLE,
                        "manifest.json",
                        "Unsupported manifest version '" + version + "'; supported: '0.4', '0.3', '0.2', '0.1'"));
            }
        }

        EncodingProfile encodingProfile = validateEncodingProfile(fields, dimensions, findings);

        Manifest manifest = null;
        if (version != null && vectorSegment != null && atomSegment != null && dimensions != null) {
            try {
                manifest = new Manifest(version, dimensions, vectorSegment, atomSegment, feedbackSegment, encodingProfile, vectorFormatProfile);
            } catch (RuntimeException e) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.FATAL,
                        StorageIntegrityCategory.MANIFEST_INVALID,
                        "manifest.json",
                        "Failed to construct manifest record: " + e.getMessage()));
            }
        }

        return new ParsedManifestResult(manifest);
    }

    private VectorFormatProfile validateManifest04PhysicalFields(Map<String, String> fields, Integer dimensions, List<StorageIntegrityFinding> findings) {
        var physicalKeys = List.of(
                "vectorFormatVersion",
                "vectorScalarType",
                "vectorByteOrder",
                "vectorFraming",
                "vectorDimensions",
                "vectorIndexFormatVersion");
        List<String> missing = physicalKeys.stream().filter(k -> !fields.containsKey(k)).toList();
        if (!missing.isEmpty()) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Manifest 0.4 requires all physical vector format metadata; missing: " + String.join(", ", missing)));
            return null;
        }

        Integer formatVersion = null;
        try {
            formatVersion = Integer.parseInt(fields.get("vectorFormatVersion"));
            if (formatVersion != VectorFormatProfile.CURRENT_FORMAT_VERSION) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.FATAL,
                        StorageIntegrityCategory.FORMAT_INCOMPATIBLE,
                        "manifest.json",
                        "Unsupported vectorFormatVersion " + formatVersion + "; supported: " + VectorFormatProfile.CURRENT_FORMAT_VERSION));
            }
        } catch (NumberFormatException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Invalid non-integer vectorFormatVersion: " + fields.get("vectorFormatVersion")));
        }

        VectorFormatProfile.ScalarType scalarType = null;
        try {
            scalarType = VectorFormatProfile.ScalarType.valueOf(fields.get("vectorScalarType"));
        } catch (IllegalArgumentException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.FORMAT_INCOMPATIBLE,
                    "manifest.json",
                    "Unsupported vectorScalarType '" + fields.get("vectorScalarType") + "'; supported: FLOAT32"));
        }

        VectorFormatProfile.ByteOrder byteOrder = null;
        try {
            byteOrder = VectorFormatProfile.ByteOrder.valueOf(fields.get("vectorByteOrder"));
        } catch (IllegalArgumentException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.FORMAT_INCOMPATIBLE,
                    "manifest.json",
                    "Unsupported vectorByteOrder '" + fields.get("vectorByteOrder") + "'; supported: BIG_ENDIAN"));
        }

        VectorFormatProfile.Framing framing = null;
        try {
            framing = VectorFormatProfile.Framing.valueOf(fields.get("vectorFraming"));
        } catch (IllegalArgumentException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.FORMAT_INCOMPATIBLE,
                    "manifest.json",
                    "Unsupported vectorFraming '" + fields.get("vectorFraming") + "'; supported: FIXED_RAW, LEGACY_LENGTH_PREFIXED"));
        }

        Integer vectorDims = null;
        try {
            vectorDims = Integer.parseInt(fields.get("vectorDimensions"));
            if (dimensions != null && !dimensions.equals(vectorDims)) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.FATAL,
                        StorageIntegrityCategory.MANIFEST_INVALID,
                        "manifest.json",
                        "vectorDimensions (" + vectorDims + ") do not match manifest dimensions (" + dimensions + ")"));
            }
        } catch (NumberFormatException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Invalid non-integer vectorDimensions: " + fields.get("vectorDimensions")));
        }

        Integer indexVersion = null;
        try {
            indexVersion = Integer.parseInt(fields.get("vectorIndexFormatVersion"));
            if (indexVersion != VectorFormatProfile.CURRENT_INDEX_FORMAT_VERSION) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.FATAL,
                        StorageIntegrityCategory.FORMAT_INCOMPATIBLE,
                        "manifest.json",
                        "Unsupported vectorIndexFormatVersion " + indexVersion + "; supported: " + VectorFormatProfile.CURRENT_INDEX_FORMAT_VERSION));
            }
        } catch (NumberFormatException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Invalid non-integer vectorIndexFormatVersion: " + fields.get("vectorIndexFormatVersion")));
        }

        if (formatVersion != null && scalarType != null && byteOrder != null && framing != null && vectorDims != null && indexVersion != null) {
            try {
                return new VectorFormatProfile(formatVersion, scalarType, byteOrder, framing, vectorDims, indexVersion);
            } catch (RuntimeException e) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.FATAL,
                        StorageIntegrityCategory.MANIFEST_INVALID,
                        "manifest.json",
                        "Failed to construct VectorFormatProfile: " + e.getMessage()));
            }
        }
        return null;
    }

    private void validateLegacyManifestPhysicalAbsence(String version, Map<String, String> fields, List<StorageIntegrityFinding> findings) {
        var physicalKeys = List.of(
                "vectorFormatVersion",
                "vectorScalarType",
                "vectorByteOrder",
                "vectorFraming",
                "vectorDimensions",
                "vectorIndexFormatVersion");
        List<String> present = physicalKeys.stream().filter(fields::containsKey).toList();
        if (!present.isEmpty()) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.ERROR,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Manifest version " + version + " must not declare physical vector format metadata ("
                            + String.join(", ", present) + "); rebuild explicitly to manifest version 0.4"));
        }
    }

    private EncodingProfile validateEncodingProfile(Map<String, String> fields, Integer dimensions, List<StorageIntegrityFinding> findings) {
        if (!fields.containsKey("encoder")) {
            return null;
        }
        String encoder = fields.get("encoder");
        String encoderVersion = fields.get("encoderVersion");
        String normalizer = fields.get("normalizer");
        String normalizerVersion = fields.get("normalizerVersion");
        String weightingStrategy = fields.get("weightingStrategy");
        String origW = fields.get("originalWeight");
        String expW = fields.get("expansionWeight");
        String aliasOrigW = fields.get("aliasOriginalWeight");
        String aliasExpW = fields.get("aliasExpansionWeight");

        if (encoder == null || encoderVersion == null || normalizer == null || normalizerVersion == null || weightingStrategy == null
                || origW == null || expW == null || aliasOrigW == null || aliasExpW == null || dimensions == null) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.ERROR,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    "Incomplete encodingProfile metadata in manifest.json"));
            return null;
        }

        try {
            double ow = parsePositiveFiniteWeight("originalWeight", origW);
            double ew = parsePositiveFiniteWeight("expansionWeight", expW);
            double aow = parsePositiveFiniteWeight("aliasOriginalWeight", aliasOrigW);
            double aew = parsePositiveFiniteWeight("aliasExpansionWeight", aliasExpW);
            return new EncodingProfile(encoder, encoderVersion, dimensions, normalizer, normalizerVersion, weightingStrategy, ow, ew, aow, aew);
        } catch (IllegalArgumentException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.ERROR,
                    StorageIntegrityCategory.MANIFEST_INVALID,
                    "manifest.json",
                    e.getMessage()));
            return null;
        }
    }

    private double parsePositiveFiniteWeight(String name, String value) {
        double d;
        try {
            d = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a valid number: " + value, e);
        }
        if (!Double.isFinite(d) || d <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive: " + value);
        }
        return d;
    }

    private AtomLogAuditResult auditAtomLog(Path atomPath, String atomSegment, List<StorageIntegrityFinding> findings) {
        if (Files.notExists(atomPath)) {
            return new AtomLogAuditResult(0, 0, Map.of());
        }

        long physicalRecords = 0;
        Map<String, KnowledgeAtom> activeAtoms = new LinkedHashMap<>();
        Map<String, Integer> atomCounts = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(atomPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) {
                    continue;
                }
                physicalRecords++;
                String[] parts = line.split("\t", -1);
                if (parts.length != 5 && parts.length != 6) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                            atomSegment,
                            "Line " + lineNum + ": Malformed atom log entry: expected 5 or 6 tab-delimited fields but found " + parts.length,
                            line));
                    continue;
                }

                String id = parts[0];
                if (id.isBlank()) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                            atomSegment,
                            "Line " + lineNum + ": Empty atom ID",
                            line));
                    continue;
                }

                AtomType atomType;
                try {
                    atomType = AtomType.valueOf(parts[1]);
                } catch (IllegalArgumentException e) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                            atomSegment,
                            "Line " + lineNum + ": Invalid atom type '" + parts[1] + "' for atomId=" + id,
                            line));
                    continue;
                }

                double weight;
                try {
                    weight = Double.parseDouble(parts[2]);
                    if (!Double.isFinite(weight) || weight <= 0.0) {
                        findings.add(new StorageIntegrityFinding(
                                StorageIntegritySeverity.ERROR,
                                StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                                atomSegment,
                                "Line " + lineNum + ": Non-positive or non-finite weight (" + parts[2] + ") for atomId=" + id,
                                line));
                        continue;
                    }
                } catch (NumberFormatException e) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                            atomSegment,
                            "Line " + lineNum + ": Non-numeric weight '" + parts[2] + "' for atomId=" + id,
                            line));
                    continue;
                }

                Instant createdAt;
                try {
                    createdAt = Instant.parse(parts[3]);
                } catch (Exception e) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                            atomSegment,
                            "Line " + lineNum + ": Corrupt createdAt timestamp '" + parts[3] + "' for atomId=" + id,
                            line));
                    continue;
                }

                String content;
                try {
                    content = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                            atomSegment,
                            "Line " + lineNum + ": Corrupt Base64 content for atomId=" + id,
                            line));
                    continue;
                }

                List<String> aliases = List.of();
                if (parts.length == 6 && !parts[5].isBlank()) {
                    try {
                        aliases = List.of(parts[5].split(",")).stream()
                                .map(alias -> new String(Base64.getDecoder().decode(alias), StandardCharsets.UTF_8))
                                .toList();
                    } catch (IllegalArgumentException e) {
                        findings.add(new StorageIntegrityFinding(
                                StorageIntegritySeverity.ERROR,
                                StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                                atomSegment,
                                "Line " + lineNum + ": Corrupt Base64 alias for atomId=" + id,
                                line));
                        continue;
                    }
                }

                KnowledgeAtom atom = new KnowledgeAtom(id, atomType, content, aliases, Map.of(), weight, createdAt);
                activeAtoms.put(id, atom);
                atomCounts.merge(id, 1, Integer::sum);
            }
        } catch (IOException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                    atomSegment,
                    "Failed to read atom segment: " + e.getMessage()));
            return new AtomLogAuditResult(0, 0, Map.of());
        }

        long historyDuplicates = atomCounts.values().stream()
                .mapToLong(count -> Math.max(0, count - 1))
                .sum();
        if (historyDuplicates > 0) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.INFO,
                    StorageIntegrityCategory.ATOM_LOG_CORRUPT,
                    atomSegment,
                    "Atom log contains " + historyDuplicates + " historical update records across "
                            + activeAtoms.size() + " active unique atoms (last-wins deduplication applied)"));
        }

        return new AtomLogAuditResult(physicalRecords, historyDuplicates, activeAtoms);
    }

    private VectorMapAuditResult auditVectorMap(Path vectorMapPath, List<StorageIntegrityFinding> findings) {
        if (Files.notExists(vectorMapPath)) {
            return new VectorMapAuditResult(0, List.of(), Map.of(), Map.of());
        }

        long totalEntries = 0;
        List<AuditIndexEntry> entries = new ArrayList<>();
        Map<Long, List<String>> offsetToAtomIds = new LinkedHashMap<>();
        Map<String, List<Long>> atomIdToOffsets = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(vectorMapPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) {
                    continue;
                }
                totalEntries++;
                String[] parts = line.split("\t", 2);
                if (parts.length != 2 || parts[0].isBlank()) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.VECTOR_INDEX_MALFORMED,
                            DEFAULT_VECTOR_MAP,
                            "Line " + lineNum + ": Malformed vector index entry: " + line));
                    continue;
                }

                String atomId = parts[0];
                long offset;
                try {
                    offset = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException e) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.VECTOR_INDEX_MALFORMED,
                            DEFAULT_VECTOR_MAP,
                            "Line " + lineNum + ": Invalid numeric offset '" + parts[1] + "' for atomId=" + atomId));
                    continue;
                }

                if (offset < 0) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.VECTOR_OFFSET_OUT_OF_BOUNDS,
                            DEFAULT_VECTOR_MAP,
                            "Line " + lineNum + ": Negative offset (" + offset + ") for atomId=" + atomId));
                    continue;
                }

                entries.add(new AuditIndexEntry(lineNum, atomId, offset));
                offsetToAtomIds.computeIfAbsent(offset, k -> new ArrayList<>()).add(atomId);
                atomIdToOffsets.computeIfAbsent(atomId, k -> new ArrayList<>()).add(offset);
            }
        } catch (IOException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.VECTOR_INDEX_MALFORMED,
                    DEFAULT_VECTOR_MAP,
                    "Failed to read vector index: " + e.getMessage()));
            return new VectorMapAuditResult(0, List.of(), Map.of(), Map.of());
        }

        for (var entry : offsetToAtomIds.entrySet()) {
            if (entry.getValue().size() > 1) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.ERROR,
                        StorageIntegrityCategory.DUPLICATE_VECTOR_OFFSET,
                        DEFAULT_VECTOR_MAP,
                        "Duplicate vector offset " + entry.getKey() + " shared by multiple index entries: "
                                + String.join(", ", entry.getValue())));
            }
        }

        for (var entry : atomIdToOffsets.entrySet()) {
            if (entry.getValue().size() > 1) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.WARNING,
                        StorageIntegrityCategory.DUPLICATE_VECTOR_INDEX_ID,
                        DEFAULT_VECTOR_MAP,
                        "Duplicate vector index entry for atomId='" + entry.getKey()
                                + "' with offsets " + entry.getValue() + "; lookup semantics return the first entry"));
            }
        }

        return new VectorMapAuditResult(totalEntries, entries, offsetToAtomIds, atomIdToOffsets);
    }

    private void auditVectorSegment(
            Path vectorPath,
            String vectorSegment,
            Integer declaredDimensions,
            VectorFormatProfile vectorFormatProfile,
            List<AuditIndexEntry> entries,
            List<StorageIntegrityFinding> findings
    ) {
        if (Files.notExists(vectorPath)) {
            return;
        }

        long actualSize;
        try {
            actualSize = Files.size(vectorPath);
        } catch (IOException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.FATAL,
                    StorageIntegrityCategory.VECTOR_SEGMENT_SIZE_MISMATCH,
                    vectorSegment,
                    "Failed to read vector segment size: " + e.getMessage()));
            return;
        }

        if (declaredDimensions == null || vectorFormatProfile == null) {
            return;
        }

        VectorFormatProfile.Framing framing = vectorFormatProfile.framing();

        long frameSize = framing == VectorFormatProfile.Framing.FIXED_RAW
                ? (long) declaredDimensions * Float.BYTES
                : Integer.BYTES + ((long) declaredDimensions * Float.BYTES);

        for (AuditIndexEntry entry : entries) {
            if (entry.offset() < 0) {
                continue;
            }
            if (entry.offset() % frameSize != 0) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.ERROR,
                        StorageIntegrityCategory.VECTOR_OFFSET_MISALIGNED,
                        vectorSegment,
                        "Offset " + entry.offset() + " for atomId=" + entry.atomId()
                                + " is not aligned to frame size (" + frameSize + " bytes)"));
            }
            if (entry.offset() >= actualSize) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.ERROR,
                        StorageIntegrityCategory.VECTOR_OFFSET_OUT_OF_BOUNDS,
                        vectorSegment,
                        "Offset " + entry.offset() + " is beyond end of vector segment (" + actualSize
                                + " bytes) for atomId=" + entry.atomId()));
            } else if (entry.offset() + frameSize > actualSize) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.ERROR,
                        StorageIntegrityCategory.VECTOR_OFFSET_OUT_OF_BOUNDS,
                        vectorSegment,
                        "Vector frame starting at offset " + entry.offset() + " (length " + frameSize
                                + " bytes) extends beyond end of vector segment (" + actualSize
                                + " bytes) for atomId=" + entry.atomId()));
            }
        }

        long expectedSize = (long) entries.size() * frameSize;
        if (actualSize != expectedSize) {
            if (actualSize < expectedSize) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.ERROR,
                        StorageIntegrityCategory.VECTOR_SEGMENT_SIZE_MISMATCH,
                        vectorSegment,
                        "Vector segment size (" + actualSize + " bytes) is smaller than expected " + expectedSize
                                + " bytes for " + entries.size() + " indexed frames of " + frameSize + " bytes (truncated segment)"));
            } else {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.WARNING,
                        StorageIntegrityCategory.VECTOR_SEGMENT_SIZE_MISMATCH,
                        vectorSegment,
                        "Vector segment size (" + actualSize + " bytes) exceeds expected " + expectedSize
                                + " bytes for " + entries.size() + " indexed frames of " + frameSize
                                + " bytes (trailing unindexed data)"));
            }
        }

        if (framing == VectorFormatProfile.Framing.LEGACY_LENGTH_PREFIXED) {
            validateLengthPrefixedHeaders(vectorPath, vectorSegment, declaredDimensions, entries, actualSize, findings);
        }
    }

    private void validateLengthPrefixedHeaders(
            Path vectorPath,
            String vectorSegment,
            int declaredDimensions,
            List<AuditIndexEntry> entries,
            long actualSize,
            List<StorageIntegrityFinding> findings
    ) {
        try (RandomAccessFile file = new RandomAccessFile(vectorPath.toFile(), "r")) {
            for (AuditIndexEntry entry : entries) {
                if (entry.offset() < 0 || entry.offset() + Integer.BYTES > actualSize) {
                    continue;
                }
                file.seek(entry.offset());
                int storedDimensions = file.readInt();
                if (storedDimensions != declaredDimensions) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.VECTOR_SEGMENT_SIZE_MISMATCH,
                            vectorSegment,
                            "Length-prefixed vector dimensions (" + storedDimensions + ") at offset "
                                    + entry.offset() + " do not match manifest dimensions (" + declaredDimensions
                                    + ") for atomId=" + entry.atomId()));
                }
            }
        } catch (IOException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.ERROR,
                    StorageIntegrityCategory.VECTOR_SEGMENT_SIZE_MISMATCH,
                    vectorSegment,
                    "Failed while inspecting length-prefixed vector headers: " + e.getMessage()));
        }
    }

    private void auditReferentialConsistency(
            String atomSegment,
            Set<String> activeAtomIds,
            Set<String> vectorIndexAtomIds,
            List<StorageIntegrityFinding> findings
    ) {
        for (String activeAtomId : activeAtomIds) {
            if (!vectorIndexAtomIds.contains(activeAtomId)) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.ERROR,
                        StorageIntegrityCategory.ATOM_WITHOUT_VECTOR,
                        atomSegment,
                        "Active atom '" + activeAtomId + "' has no corresponding entry in vector index"));
            }
        }

        for (String vectorIndexAtomId : vectorIndexAtomIds) {
            if (!activeAtomIds.contains(vectorIndexAtomId)) {
                findings.add(new StorageIntegrityFinding(
                        StorageIntegritySeverity.WARNING,
                        StorageIntegrityCategory.VECTOR_WITHOUT_ATOM,
                        DEFAULT_VECTOR_MAP,
                        "Vector index contains entry for atomId '" + vectorIndexAtomId
                                + "' which is not present in active atom log (orphan vector)"));
            }
        }
    }

    private long auditFeedbackLog(
            Path feedbackPath,
            String feedbackSegment,
            Set<String> activeAtomIds,
            List<StorageIntegrityFinding> findings
    ) {
        if (Files.notExists(feedbackPath)) {
            return 0;
        }

        long records = 0;
        try (BufferedReader reader = Files.newBufferedReader(feedbackPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) {
                    continue;
                }
                records++;
                try {
                    var fields = JsonStrings.parseFlat(line, "feedback log");
                    String query = fields.get("query");
                    String atomId = fields.get("atomId");
                    String signal = fields.get("signal");
                    String delta = fields.get("delta");
                    String createdAt = fields.get("createdAt");

                    if (query == null || atomId == null || signal == null || delta == null || createdAt == null) {
                        findings.add(new StorageIntegrityFinding(
                                StorageIntegritySeverity.ERROR,
                                StorageIntegrityCategory.FEEDBACK_LOG_CORRUPT,
                                feedbackSegment,
                                "Line " + lineNum + ": Missing required field in feedback log entry",
                                line));
                        continue;
                    }

                    FeedbackSignal.valueOf(signal);
                    Double.parseDouble(delta);
                    Instant.parse(createdAt);

                    if (!activeAtomIds.isEmpty() && !activeAtomIds.contains(atomId)) {
                        findings.add(new StorageIntegrityFinding(
                                StorageIntegritySeverity.INFO,
                                StorageIntegrityCategory.FEEDBACK_LOG_CORRUPT,
                                feedbackSegment,
                                "Line " + lineNum + ": Feedback references atomId '" + atomId + "' not present in active atom log"));
                    }
                } catch (Exception e) {
                    findings.add(new StorageIntegrityFinding(
                            StorageIntegritySeverity.ERROR,
                            StorageIntegrityCategory.FEEDBACK_LOG_CORRUPT,
                            feedbackSegment,
                            "Line " + lineNum + ": Malformed feedback log entry: " + e.getMessage(),
                            line));
                }
            }
        } catch (IOException e) {
            findings.add(new StorageIntegrityFinding(
                    StorageIntegritySeverity.ERROR,
                    StorageIntegrityCategory.FEEDBACK_LOG_CORRUPT,
                    feedbackSegment,
                    "Failed to read feedback segment: " + e.getMessage()));
            return 0;
        }
        return records;
    }

    private record ParsedManifestResult(Manifest manifest) {
    }

    private record AtomLogAuditResult(long physicalRecords, long historyDuplicates, Map<String, KnowledgeAtom> activeAtoms) {
    }

    private record VectorMapAuditResult(
            long totalEntries,
            List<AuditIndexEntry> entries,
            Map<Long, List<String>> offsetToAtomIds,
            Map<String, List<Long>> atomIdToOffsets
    ) {
    }

    private record AuditIndexEntry(int lineNumber, String atomId, long offset) {
    }
}
