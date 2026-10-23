package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookFootprintBenchmark.Experiment;
import com.monada.evaluation.book.BookFootprintBenchmark.PhaseResult;
import com.monada.evaluation.book.BookFootprintBenchmark.Result;
import com.monada.evaluation.book.BookFootprintBenchmark.ScaleResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The footprint experiment on the original 10-chunk fixture, which repeats one chunk (Issue #169): phase order,
 * reopen and repeat-import immutability, overlap and duplicate behaviour, input safety, isolation and a deterministic
 * report section. Sizes are asserted only as exact structural equalities, memory never.
 */
class BookFootprintBenchmarkTest {
    @TempDir
    Path dir;

    private Path copyFixture(Path target, String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(BookRecallFixture.FIXTURE + name)) {
            Path file = target.resolve(name);
            Files.write(file, in.readAllBytes());
            return file;
        }
    }

    private record Fixture(BookChunkFiles.Loaded chunks, Path body, Path manifest) {
    }

    private Fixture fixture() throws Exception {
        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        Path manifest = copyFixture(inputs, "manifest.properties");
        Path body = copyFixture(inputs, "body.txt");
        BookChunker.Result result = BookChunker.chunk(VerifiedBookSource.load(manifest, body), BookRecallFixture.POLICY);
        BookChunkOutputWriter.Output rendered = BookChunkOutputWriter.render(result);
        return new Fixture(BookChunkFiles.parse(rendered.chunks(), rendered.sidecar()), body, manifest);
    }

    private static BookMemorySampler sampler() {
        return new BookMemorySampler(BookMemorySampler.unsupported("test"), Optional::empty);
    }

    private Result run(Fixture fixture, String name) throws Exception {
        Path scratch = Files.createDirectory(dir.resolve(name));
        return BookFootprintBenchmark.run(fixture.chunks(), Map.of("corpus", "book-chunking-fixture"),
                BookLatencyScale.parseLadder("50%,100%,x2"), scratch, sampler(),
                List.of(fixture.body(), fixture.manifest()));
    }

    private static PhaseResult phase(List<PhaseResult> phases, String name) {
        return phases.stream().filter(p -> p.phase().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name));
    }

    @Test
    void reopenAndRepeatImportWriteNothingAndEveryCheckPasses() throws Exception {
        Fixture fixture = fixture();
        assertEquals(10, fixture.chunks().entries().size());
        Result result = run(fixture, "scratch");

        assertTrue(result.passed(), result.failures().toString());
        assertEquals(3, result.scales().size());
        for (ScaleResult scale : result.scales()) {
            assertEquals(List.of("EMPTY", "FRESH_IMPORT", "REOPEN", "REPEAT_IMPORT"),
                    scale.phases().stream().map(PhaseResult::phase).toList());
            PhaseResult fresh = phase(scale.phases(), "FRESH_IMPORT");
            PhaseResult reopen = phase(scale.phases(), "REOPEN");
            PhaseResult repeat = phase(scale.phases(), "REPEAT_IMPORT");
            assertEquals(scale.selected().uniqueAtoms(), fresh.snapshot().statistics().activeUniqueAtoms());
            assertTrue(reopen.unchanged() && repeat.unchanged(), scale.selected().scale().spec());
            assertEquals(fresh.snapshot().totalBytes(), repeat.snapshot().totalBytes(), "no growth, no reclaim");
            assertEquals(0, repeat.newAtoms());
            assertEquals(null, phase(scale.phases(), "EMPTY").memory(), "no API call, no sample");
        }
        PhaseResult full = phase(result.scales().get(1).phases(), "FRESH_IMPORT");
        assertEquals(10, full.inputTexts());
        assertEquals(9, full.snapshot().statistics().activeUniqueAtoms(), "the repeated chunk is one atom");
        assertEquals(18, phase(result.scales().get(2).phases(), "FRESH_IMPORT").snapshot().statistics()
                .activeUniqueAtoms(), "x2 replicas get their own atoms");
        assertTrue(result.checks().stream().anyMatch(c -> c.name().equals("inputs.guarded[1].unchanged")));
    }

    @Test
    void overlapAppendsOnlyTheMissingAndDuplicatesMerge() throws Exception {
        Result result = run(fixture(), "scratch");
        Experiment overlap = result.experiments().get(0);
        assertEquals("OVERLAP", overlap.id());
        PhaseResult half = phase(overlap.phases(), "IMPORT_50%");
        PhaseResult all = phase(overlap.phases(), "IMPORT_100%");
        assertEquals(9 - half.expectedUniqueAtoms(), all.newAtoms());
        assertEquals(all.newAtoms(), all.snapshot().statistics().atomLogPhysicalRecords()
                - half.snapshot().statistics().atomLogPhysicalRecords());

        Experiment duplicates = result.experiments().get(1);
        PhaseResult clean = phase(duplicates.phases(), "CLEAN_ONCE");
        PhaseResult twice = phase(duplicates.phases(), "EVERY_CHUNK_TWICE");
        assertEquals(20, twice.inputTexts());
        assertEquals(clean.snapshot().statistics(), twice.snapshot().statistics());
        assertEquals(clean.snapshot().files().get(BookFootprintSnapshot.Role.VECTORS),
                twice.snapshot().files().get(BookFootprintSnapshot.Role.VECTORS));
    }

    @Test
    void everyStoreStaysInsideScratchAndTheInputsAreUntouched() throws Exception {
        Fixture fixture = fixture();
        byte[] body = Files.readAllBytes(fixture.body());
        String inputsBefore = BookStoreInventory.treeDigest(dir.resolve("inputs"));
        run(fixture, "scratch");
        assertEquals(inputsBefore, BookStoreInventory.treeDigest(dir.resolve("inputs")));
        assertTrue(java.util.Arrays.equals(body, Files.readAllBytes(fixture.body())));
        try (Stream<Path> children = Files.list(dir)) {
            assertEquals(List.of("inputs", "scratch"), children.map(p -> p.getFileName().toString()).sorted()
                    .toList());
        }
        try (Stream<Path> children = Files.list(dir.resolve("scratch"))) {
            assertEquals(List.of("experiments", "scale-p100", "scale-p50", "scale-x2"),
                    children.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void theDeterministicSectionIsIdenticalAcrossRunsAndHoldsNoHostOrMemoryFacts() throws Exception {
        Fixture fixture = fixture();
        String first = BookFootprintReport.render(run(fixture, "a"));
        String second = BookFootprintReport.render(run(fixture, "b"));
        String deterministic = BookFootprintReport.deterministicSection(first);
        assertEquals(deterministic, BookFootprintReport.deterministicSection(second));
        assertTrue(deterministic.contains("checks.status=PASS"), deterministic);
        assertFalse(deterministic.contains("host."), "host facts stay out of the deterministic section");
        assertFalse(deterministic.contains("heap"), "memory stays out of the deterministic section");
        assertFalse(deterministic.contains("bytes.atoms"), "atom bytes carry timestamps and are measured only");
        assertFalse(deterministic.contains(dir.toString()), "no scratch path leaks into the report");
        assertTrue(first.contains("allocation.threadBytes=UNSUPPORTED (test)"), first);
        assertTrue(BookFootprintReport.summary(run(fixture, "c")).contains("| UNSUPPORTED | UNKNOWN | UNKNOWN |"));
    }

    @Test
    void ratiosAreNeverDividedByZero() {
        assertEquals("n/a", BookFootprintReport.ratio(10, 0));
        assertEquals("2.500", BookFootprintReport.ratio(5, 2));
    }
}
