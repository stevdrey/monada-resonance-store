package com.monada.api.execution;

import static com.monada.api.execution.Experiences.appended;
import static com.monada.api.execution.Experiences.checkpoint;
import static com.monada.api.execution.Experiences.experience;
import static com.monada.api.execution.Experiences.projectionDir;
import static com.monada.api.execution.Experiences.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The #158 projection open path (atoms built once, stored vectors reused, cheaper digests) must accept and reject
 * exactly what it did before: the same states, the same first-mismatch diagnostics, the same recall.
 */
class ProjectionOpenEquivalenceTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");
    private static final List<String> QUERIES = List.of("flaky test", "gradle build cache", "release notes changelog",
            "database migration rollback", "session cookie login", "docker image size");

    @TempDir
    Path root;

    /** Six experiences with different texts, then a correction that retires x-1's original atom. */
    private static void corpus(ExecutionMemory m) {
        experience(m, SCOPE, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
        experience(m, SCOPE, "x-2", "Speed up the gradle build", "Enabled the configuration cache", null);
        experience(m, SCOPE, "x-3", "Write release notes", "Listed merged pull requests", "Group by label");
        experience(m, SCOPE, "x-4", "Roll back a database migration", "Restored from the snapshot", null);
        experience(m, SCOPE, "x-5", "Shrink the docker image", "Used a distroless base image", null);
        experience(m, SCOPE, "x-6", "Fix the flaky upload test", "Retried the upload on timeouts", null);
        appended(m, Experiences.correctFinish(SCOPE, "x-1", "a1", "x-1-fix", "x-1-a1-fin", 2,
                "Mocked the session", null));
    }

    private static Map<String, List<String>> recallAll(ExecutionMemory m) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String q : QUERIES) {
            out.put(q, render(m.recall(SCOPE, q, 10)));
        }
        return out;
    }

    private static String vectorMismatch(String atomId) {
        return "stored vector of atom " + atomId + " does not match its projected text";
    }

    private static void assertIncompatible(Path store, String expectedDiagnostic) {
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state(), "read-only");
            assertTrue(status.diagnostics().stream().anyMatch(d -> d.contains(expectedDiagnostic)),
                    status.diagnostics().toString());
        }
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state(), "writable");
            assertTrue(status.diagnostics().stream().anyMatch(d -> d.contains(expectedDiagnostic)),
                    status.diagnostics().toString());
        }
    }

    /** Vector frame offset of every stored atom, from the projection memory's vector map. */
    private Map<String, Integer> frameOffsets(Path store) throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        Path index = projectionDir(store, SCOPE).resolve("memory/indexes/vector-map.idx");
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t");
            out.put(parts[0], Integer.parseInt(parts[1]));
        }
        return out;
    }

    private Path segment(Path store) {
        return projectionDir(store, SCOPE).resolve("memory/vectors/segment-000001.f32");
    }

    @Test
    void manyAtomsVerifyAndRankIdenticallyWritableReadOnlyAndAfterRebuild() {
        Map<String, List<String>> recorded;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            recorded = recallAll(m);
        }
        assertTrue(recorded.values().stream().filter(r -> !r.isEmpty()).count() >= 5, recorded.toString());
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.projectionStatus(SCOPE).state());
            assertEquals(recorded, recallAll(m), "writable reopen verifies every atom and ranks the same");
        }
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.projectionStatus(SCOPE).state());
            assertEquals(recorded, recallAll(m), "the frozen read-only index ranks the same");
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            assertEquals(recorded, recallAll(m));
        }
    }

    @Test
    void swappedLiveVectorsReportTheFirstAtomInIdOrder() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        String a = KnowledgeAtom.text("Speed up the gradle build\nEnabled the configuration cache").id();
        String b = KnowledgeAtom.text("Shrink the docker image\nUsed a distroless base image").id();
        Map<String, Integer> offsets = frameOffsets(root);
        assertTrue(offsets.containsKey(a) && offsets.containsKey(b), offsets.toString());
        byte[] bytes = Files.readAllBytes(segment(root));
        int frame = bytes.length / offsets.size();
        byte[] swapped = bytes.clone();
        System.arraycopy(bytes, offsets.get(a), swapped, offsets.get(b), frame);
        System.arraycopy(bytes, offsets.get(b), swapped, offsets.get(a), frame);
        Files.write(segment(root), swapped);
        assertIncompatible(root, vectorMismatch(a.compareTo(b) < 0 ? a : b));
    }

    @Test
    void alteredRetiredVectorIsReportedByItsAtom() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        String retired = KnowledgeAtom.text("Repair the flaky login test\nWaited for the session cookie").id();
        String other = KnowledgeAtom.text("Roll back a database migration\nRestored from the snapshot").id();
        Map<String, Integer> offsets = frameOffsets(root);
        assertTrue(offsets.containsKey(retired) && offsets.containsKey(other), offsets.toString());
        byte[] bytes = Files.readAllBytes(segment(root));
        int frame = bytes.length / offsets.size();
        byte[] altered = bytes.clone();
        System.arraycopy(bytes, offsets.get(other), altered, offsets.get(retired), frame);
        Files.write(segment(root), altered);
        assertIncompatible(root, vectorMismatch(retired));
    }

    @Test
    void foreignLedgerIsReportedAtTheFirstDivergingSequence() throws IOException {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        try (ExecutionMemory m = ExecutionMemory.open(a, SCOPE)) {
            corpus(m);
        }
        try (ExecutionMemory m = ExecutionMemory.open(b, SCOPE)) {
            // the same first execution (sequences 1-3), then a different start at sequence 4
            experience(m, SCOPE, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
            experience(m, SCOPE, "x-2", "A different task", "Another solution", null);
            experience(m, SCOPE, "x-3", "Yet another task", "More", null);
        }
        Files.copy(checkpoint(b, SCOPE), checkpoint(a, SCOPE), StandardCopyOption.REPLACE_EXISTING);
        assertIncompatible(a, "checkpoint was built from a different ledger at sequence 4");
    }
}
