package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookFeedbackExperiment.Check;
import com.monada.evaluation.book.BookFeedbackExperiment.Result;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookRecallEvaluator.CaseResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The feedback A/B and explicit note over the seeded original fixture with the real API (Issue #170): isolated arms,
 * a control that reproduces the baseline, persist/reopen parity, untouched HOLDOUT and confusable probes, no
 * abstention from feedback alone, the note persisted next to every original chunk, repeat-run determinism, and
 * contamination checks that do fail when feedback is made to leak.
 */
class BookFeedbackExperimentTest {
    @TempDir
    Path dir;

    private BookChunker.Result chunked;
    private BookQueryDataset dataset;
    private byte[] baseline;

    @BeforeEach
    void prepare() throws Exception {
        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        chunked = BookChunker.chunk(VerifiedBookSource.load(copy(inputs, "manifest.properties"),
                copy(inputs, "body.txt")), BookRecallFixture.POLICY);
        Path reference = BookRecallFixture.seededWork(dir, "reference", BookRecallFixture.POLICY);
        dataset = BookRecallFixture.dataset(reference);
        baseline = BookRecallReport.render(BookRecallEvaluator.evaluate(reference, dataset))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Path copy(Path dir, String name) throws IOException {
        try (InputStream in = BookFeedbackExperimentTest.class.getResourceAsStream(BookRecallFixture.FIXTURE + name)) {
            Path target = dir.resolve(name);
            Files.write(target, in.readAllBytes());
            return target;
        }
    }

    private static Check check(Result result, String name) {
        return result.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyHardCheckPassesOnTheRealApi() throws Exception {
        Result result = BookFeedbackExperiment.run(chunked, dataset, baseline, dir.resolve("run"));
        assertTrue(result.passed(), result.failures().toString());
        for (String name : List.of("control.cleanSeed", "treatment.cleanSeed", "note.cleanSeed",
                "control.reproducesBaseline", "guard.unknownAtomRejected", "treatment.feedbackLogMatchesPlan",
                "treatment.reopenParity", "contamination.holdoutUnchanged",
                "contamination.devWithoutFeedbackUnchanged", "contamination.confusableProbesUnchanged",
                "isolation.controlStoreUnchanged", "note.newAtomId", "note.sidecarReadsBack",
                "note.persistedNextToOriginals", "note.chunkReadbackSeesOneExtraAtom",
                "note.rankingsWithoutNoteUnchanged", "isolation.controlStoreUnchangedAfterNote")) {
            assertTrue(check(result, name).passed(), name);
        }
        assertFalse(result.plan().events().isEmpty());
        assertEquals(2 * result.plan().casesWithFeedback(), result.probes().size());
    }

    @Test
    void feedbackMovesOnlyItsOwnQueriesAndCannotMakeTheStoreAbstain() throws Exception {
        Result result = BookFeedbackExperiment.run(chunked, dataset, baseline, dir.resolve("run"));
        boolean changed = false;
        for (int i = 0; i < result.control().cases().size(); i++) {
            CaseResult c = result.control().cases().get(i);
            CaseResult t = result.treatment().cases().get(i);
            boolean same = BookFeedbackExperiment.hits(c).equals(BookFeedbackExperiment.hits(t));
            boolean hasFeedback = result.plan().queries().contains(c.query().text());
            if (!hasFeedback) {
                assertTrue(same, c.query().id() + " has no feedback of its own and must not move");
            }
            changed |= !same;
            if (!c.answerable()) {
                assertEquals(c.hits().size(), t.hits().size(), "feedback never empties a NO_ANSWER result");
                assertEquals(BookRecallFailure.FALSE_POSITIVE, t.failure());
            }
        }
        assertTrue(changed, "the treatment must differ from control somewhere, or nothing was applied");
        Case holdout = dataset.cases().stream().filter(c -> c.split() == Split.HOLDOUT).findFirst().orElseThrow();
        assertFalse(result.plan().queries().contains(holdout.text()));
        assertTrue(result.probes().stream().allMatch(BookFeedbackExperiment.Probe::identical));
    }

    @Test
    void theNoteIsANewAtomNextToEveryOriginalChunk() throws Exception {
        Result result = BookFeedbackExperiment.run(chunked, dataset, baseline, dir.resolve("run"));
        BookFeedbackExperiment.NoteResult note = result.note();
        assertEquals(BookFeedbackNote.atomId(), note.atomId());
        assertEquals(note.chunkAtoms() + 1, note.inventoryAtoms());
        assertEquals(note.chunkAtoms(), note.chunkAtomsExact());
        assertTrue(note.noteExact());
        assertFalse(note.readbackPassed(), "the chunk-only readback contract must notice the extra atom");
        assertEquals(1, note.readbackUnexpectedAtoms());
        assertTrue(BookFeedbackNote.readsBack(note.sidecar(), note.atomId()));
        assertEquals(BookFeedbackNote.PROBES.size(), note.probes().size());
    }

    @Test
    void aVariantEqualToAFeedbackQueryIsNoProbe() throws Exception {
        // LIT-01 already lower-case: its CASE_FOLDED variant would be the feedback query itself.
        List<String> queries = BookRecallFixture.QUERIES.stream().map(row -> row.startsWith("LIT-01|")
                ? "LIT-01|LITERAL|how many gulls circled the breakwater?|Counted in chapter one." : row).toList();
        Path reference = BookRecallFixture.seededWork(dir, "lower", BookRecallFixture.POLICY);
        BookQueryDataset lower = BookRecallFixture.dataset(reference, queries, BookRecallFixture.JUDGMENTS);
        byte[] lowerBaseline = BookRecallReport.render(BookRecallEvaluator.evaluate(reference, lower))
                .getBytes(StandardCharsets.UTF_8);

        Result result = BookFeedbackExperiment.run(chunked, lower, lowerBaseline, dir.resolve("run"));

        assertTrue(result.passed(), result.failures().toString());
        List<String> lit01 = result.probes().stream().filter(p -> p.source().id().equals("LIT-01"))
                .map(BookFeedbackExperiment.Probe::id).toList();
        assertEquals(List.of("LIT-01/REWORDED"), lit01);
        assertTrue(result.probes().stream().noneMatch(p -> result.plan().queries().contains(p.text())));
    }

    @Test
    void twoRunsOnNewStoresRenderIdenticalReports() throws Exception {
        String first = BookFeedbackReport.body(BookFeedbackExperiment.run(chunked, dataset, baseline,
                dir.resolve("a")));
        String second = BookFeedbackReport.body(BookFeedbackExperiment.run(chunked, dataset, baseline,
                dir.resolve("b")));
        assertEquals(first, second);
        assertTrue(first.startsWith("Monada book feedback report"), first);
        assertTrue(first.contains("checks=PASS"), first);
        assertFalse(first.contains(dir.toString()), "the report contains no paths");
        for (BookChunk chunk : chunked.chunks()) {
            if (dataset.cases().stream().anyMatch(c -> c.text().equals(chunk.text().strip()))) {
                continue; // the fixture's repeated line doubles as a query text, which the report does quote
            }
            assertFalse(first.contains(chunk.text().strip()), "the report contains no book text: " + chunk.key());
        }
    }

    @Test
    void aBaselineThatControlDoesNotReproduceFailsTheRun() throws Exception {
        byte[] stale = (new String(baseline, StandardCharsets.UTF_8) + "edited\n").getBytes(StandardCharsets.UTF_8);
        Result result = BookFeedbackExperiment.run(chunked, dataset, stale, dir.resolve("run"));
        assertFalse(check(result, "control.reproducesBaseline").passed());
        assertFalse(result.passed());
    }

    @Test
    void feedbackThatGeneralizesBeyondItsExactQueryIsDetected() throws Exception {
        // A sink that also records every event under the lower-cased query: what a normalizing key would do.
        Result result = BookFeedbackExperiment.run(chunked, dataset, baseline, dir.resolve("run"), (memory, e) -> {
            BookFeedbackExperiment.PUBLIC_API.apply(memory, e);
            memory.feedback(e.query().toLowerCase(Locale.ROOT), e.atomId(), e.signal());
        });
        assertFalse(check(result, "contamination.confusableProbesUnchanged").passed());
        assertFalse(check(result, "treatment.feedbackLogMatchesPlan").passed());
        assertTrue(result.probes().stream().anyMatch(p -> !p.identical()));
        assertTrue(BookFeedbackReport.body(result).contains(" TRANSFER "), "the report names the transfer");
    }

    @Test
    void feedbackRecordedUnderAHoldoutQueryIsDetected() throws Exception {
        Case holdout = dataset.cases().stream().filter(c -> c.split() == Split.HOLDOUT).findFirst().orElseThrow();
        Result result = BookFeedbackExperiment.run(chunked, dataset, baseline, dir.resolve("run"), (memory, e) -> {
            BookFeedbackExperiment.PUBLIC_API.apply(memory, e);
            memory.feedback(holdout.text(), e.atomId(), e.signal());
        });
        assertFalse(check(result, "contamination.holdoutUnchanged").passed());
        assertFalse(result.passed());
        assertNotEquals(BookFeedbackReport.body(result).indexOf("FAIL contamination.holdoutUnchanged"), -1);
    }
}
