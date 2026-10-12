package com.monada.api.execution;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.KnowledgeAtom;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.WeightedAtomEncoder;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.Manifest;
import com.monada.storage.StoredVector;
import com.monada.storage.VectorFormatProfile;
import com.monada.storage.audit.StorageIntegrityAuditor;
import com.monada.storage.audit.StorageIntegrityCategory;
import com.monada.storage.audit.StorageIntegrityReport;
import com.monada.storage.audit.StorageIntegritySeverity;
import com.monada.storage.execution.ProjectionCheckpoint;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read-only verification of a projection generation's memory against its verified checkpoint, run before the
 * memory is trusted (contract v1, section 18.3). Every check reads only: the fixed layout must be complete
 * (opening {@link MonadaMemory} would otherwise recreate missing files), the feedback log empty, the existing
 * side-effect-free {@link StorageIntegrityAuditor} clean (no error, no duplicate vector), the manifest a 0.4
 * store with the default profile at the format-1 dimension, every live atom stored, and the stored vector of
 * every known atom (live and retired) equal to the re-encoding of its projection text.
 */
final class ProjectionVerifier {
    /** Manifest version of every projection memory (the current {@code MonadaMemory} format). */
    private static final String PROJECTION_MANIFEST_VERSION = "0.4";

    /**
     * Outcome: {@code problems} is empty when the memory is sound. Then {@code memory} is the opened store,
     * {@code atoms}/{@code vectors} its full content (read once, reusable by a frozen snapshot), {@code present}
     * the stored atom IDs, and {@code extra} the stored atoms the checkpoint does not know.
     */
    record Result(List<String> problems, MonadaMemory memory, List<KnowledgeAtom> atoms, List<StoredVector> vectors,
                  Set<String> present, Set<String> extra) {
        static Result failed(List<String> problems) {
            return new Result(problems, null, List.of(), List.of(), Set.of(), Set.of());
        }
    }

    private ProjectionVerifier() {
    }

    /**
     * Verifies the memory of a generation whose checkpoint {@code snapshot} already matched the ledger
     * ({@code replayed} is the projection of its covered prefix). {@code caughtUp} means the checkpoint covers
     * the whole ledger with a clean tail, so stored atoms it does not know are damage rather than an interrupted
     * write.
     */
    static Result verify(ProjectionLayout layout, Path memoryDir, ProjectionCheckpoint.Snapshot snapshot,
                         Projector replayed, boolean caughtUp) throws IOException {
        List<String> problems = new ArrayList<>(layout.memoryLayoutProblems(memoryDir));
        if (!problems.isEmpty()) {
            return Result.failed(problems);
        }
        if (Files.size(memoryDir.resolve(ProjectionLayout.FEEDBACK_SEGMENT)) != 0) {
            // Feedback would change ranking while the checkpoint still matches the ledger.
            return Result.failed(List.of(
                    "projection memory holds retrieval feedback; projections are derived from the ledger only"));
        }
        Manifest manifest = manifestProblems(memoryDir, snapshot.dimensions(), problems);
        if (!problems.isEmpty()) {
            return Result.failed(problems);
        }
        MonadaMemory memory;
        try {
            memory = MonadaMemory.open(memoryDir, MonadaMemoryOptions.defaults());
        } catch (RuntimeException e) {
            return Result.failed(List.of("projection memory is incompatible: " + e.getMessage()));
        }
        List<KnowledgeAtom> atoms = new FileAtomStore(memoryDir).findAll();
        Set<String> present = new HashSet<>();
        atoms.forEach(a -> present.add(a.id()));
        // Live atoms must be stored; retired atoms may be absent (a rebuild compacts them away).
        for (String atom : snapshot.refsByAtom().keySet()) {
            if (!present.contains(atom)) {
                return Result.failed(List.of("mapped atom " + atom + " is missing from the projection memory"));
            }
        }
        List<StoredVector> vectors = FileFrequencyStore.openExisting(memoryDir, ProjectionLayout.VECTOR_SEGMENT,
                manifest.vectorFormatProfile()).findAll();
        String vectorProblem = vectorProblem(vectors, manifest, replayed, present);
        if (vectorProblem != null) {
            return Result.failed(List.of(vectorProblem));
        }
        Set<String> extra = new TreeSet<>(present);
        extra.removeAll(snapshot.refsByAtom().keySet());
        extra.removeAll(snapshot.retiredAtoms());
        if (caughtUp && !extra.isEmpty()) {
            return Result.failed(List.of("projection memory holds atoms without experience refs: "
                    + extra.iterator().next()));
        }
        return new Result(List.of(), memory, atoms, vectors, Set.copyOf(present), Set.copyOf(extra));
    }

    /**
     * Audits the store with the existing side-effect-free {@link StorageIntegrityAuditor} and checks its manifest:
     * the standard segments, version 0.4, the default encoding profile and the current vector format, all at
     * {@code dimensions} (the format-1 dimension recorded in the checkpoint header, never the manifest's own).
     */
    private static Manifest manifestProblems(Path memoryDir, int dimensions, List<String> problems) {
        StorageIntegrityReport report = new StorageIntegrityAuditor().audit(memoryDir);
        report.findings().stream()
                .filter(f -> f.severity() == StorageIntegritySeverity.ERROR
                        || f.severity() == StorageIntegritySeverity.FATAL
                        // Projection writes never index an atom or a frame twice; a duplicate would let an
                        // extra frame change the atom's score.
                        || f.category() == StorageIntegrityCategory.DUPLICATE_VECTOR_INDEX_ID
                        || f.category() == StorageIntegrityCategory.DUPLICATE_VECTOR_OFFSET)
                .limit(3)
                .forEach(f -> problems.add("projection memory " + f.category() + " in " + f.target() + ": "
                        + f.message()));
        Manifest manifest = report.manifest();
        if (problems.isEmpty() && (manifest == null
                || !ProjectionLayout.ATOM_SEGMENT.equals(manifest.atomSegment())
                || !ProjectionLayout.VECTOR_SEGMENT.equals(manifest.vectorSegment())
                || !ProjectionLayout.FEEDBACK_SEGMENT.equals(manifest.feedbackSegment()))) {
            problems.add("projection memory manifest does not name the standard projection segments");
        }
        if (problems.isEmpty() && manifest.dimensions() != dimensions) {
            problems.add("projection memory dimension " + manifest.dimensions()
                    + " differs from the generation's dimension " + dimensions);
        }
        // A legacy or different profile would encode queries with semantics the stored vectors were not built with.
        if (problems.isEmpty() && (!PROJECTION_MANIFEST_VERSION.equals(manifest.version())
                || !MonadaMemory.getExpectedProfile(dimensions, MonadaMemoryOptions.defaults())
                        .equals(manifest.encodingProfile())
                || !VectorFormatProfile.currentFixedRaw(dimensions).equals(manifest.vectorFormatProfile()))) {
            problems.add("projection memory manifest is not a " + PROJECTION_MANIFEST_VERSION
                    + " store with the default encoding and vector profile");
        }
        return manifest;
    }

    /**
     * Re-encodes the projection text of every stored atom the checkpoint knows, live and retired (a later
     * correction can reactivate a retired atom without rewriting it), with the production pipeline
     * ({@link WeightedAtomEncoder}, the side-effect-free helper {@code MonadaMemory.remember} relies on, at the
     * manifest's verified dimension) and requires it to equal the stored vector. Null when all match.
     */
    private static String vectorProblem(List<StoredVector> stored, Manifest manifest, Projector replayed,
                                        Set<String> present) {
        Map<String, float[]> vectors = new HashMap<>();
        stored.forEach(v -> vectors.put(v.atomId(), v.vector().values()));
        MonadaMemoryOptions options = MonadaMemoryOptions.defaults();
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(manifest.dimensions());
        TreeSet<String> known = new TreeSet<>(replayed.refsByAtom().keySet());
        known.addAll(replayed.retired());
        known.retainAll(present);
        for (String atomId : known) {
            float[] expected = encoder.encode(WeightedAtomEncoder.toWeightedText(
                    KnowledgeAtom.text(replayed.textOf(atomId)), options.textNormalizer(),
                    options.expansionOptions())).values();
            if (!Arrays.equals(expected, vectors.get(atomId))) {
                return "stored vector of atom " + atomId + " does not match its projected text";
            }
        }
        return null;
    }
}
