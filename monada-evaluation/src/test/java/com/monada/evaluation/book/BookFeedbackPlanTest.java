package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import com.monada.evaluation.book.BookFeedbackPlan.Event;
import com.monada.evaluation.book.BookFeedbackPlan.Plan;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.storage.feedback.FeedbackSignal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The predeclared feedback plan (Issue #170): derived from DEV judgments only, positive for evidence and negative for
 * confounders, stable, and rejected before anything is written when it would leak into HOLDOUT or probe queries or
 * name an atom outside the chunk set.
 */
class BookFeedbackPlanTest {
    @TempDir
    Path dir;

    private BookQueryDataset dataset;
    private BookChunkFiles.Loaded chunks;
    private Set<String> atoms;

    @BeforeEach
    void seed() throws Exception {
        Path work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        dataset = BookRecallFixture.dataset(work);
        chunks = BookChunkFiles.load(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR));
        atoms = new LinkedHashSet<>();
        chunks.entries().forEach(e -> atoms.add(KnowledgeAtom.text(e.text()).id()));
    }

    private Case holdout() {
        return dataset.cases().stream().filter(c -> c.split() == Split.HOLDOUT).findFirst().orElseThrow();
    }

    @Test
    void derivesFromDevJudgmentsOnly() throws Exception {
        Plan plan = BookFeedbackPlan.derive(dataset, chunks);
        Set<String> dev = new LinkedHashSet<>();
        dataset.cases().stream().filter(c -> c.split() == Split.DEV).forEach(c -> dev.add(c.id()));
        assertTrue(plan.events().stream().allMatch(e -> dev.contains(e.caseId())), plan.events().toString());
        assertTrue(plan.events().stream().noneMatch(e -> e.caseId().equals(holdout().id())));

        Event positive = plan.events().stream().filter(e -> e.caseId().equals("CON-01")
                && e.signal() == FeedbackSignal.POSITIVE).findFirst().orElseThrow();
        assertEquals(BookRecallFixture.KEY + "s001/c0001", positive.chunkKey());
        assertEquals("E2", positive.source());
        Event negative = plan.events().stream().filter(e -> e.caseId().equals("CON-01")
                && e.signal() == FeedbackSignal.NEGATIVE).findFirst().orElseThrow();
        assertEquals(BookRecallFixture.KEY + "s002/c0002", negative.chunkKey());
        assertEquals("C0", negative.source());
        // A NO_ANSWER case has no evidence: only its judged near miss is pushed down.
        assertTrue(plan.events().stream().filter(e -> e.caseId().equals("NOA-01"))
                .allMatch(e -> e.signal() == FeedbackSignal.NEGATIVE));
        assertEquals(plan.count(FeedbackSignal.POSITIVE) + plan.count(FeedbackSignal.NEGATIVE),
                plan.events().size());
        BookFeedbackPlan.validate(plan, dataset, atoms, List.of());
    }

    @Test
    void repeatedTextIsOneAtomAndThePlanIsStable() throws Exception {
        Plan plan = BookFeedbackPlan.derive(dataset, chunks);
        Plan again = BookFeedbackPlan.derive(dataset, chunks);
        assertEquals(plan, again);
        assertTrue(plan.digest().matches("[0-9a-f]{64}"), plan.digest());
        // The split rule makes LIT-02 (the repeated line) the fixture's HOLDOUT case: none of its atoms gets feedback.
        assertEquals("LIT-02", holdout().id());
        assertEquals(0, plan.sharedWithHoldout());
        assertEquals(plan.events().size(), plan.events().stream().map(e -> e.query() + "|" + e.atomId()).distinct()
                .count(), "one event per (query, atom)");
    }

    @Test
    void leakingOrInvalidPlansAreRejected() throws Exception {
        Plan plan = BookFeedbackPlan.derive(dataset, chunks);
        Event first = plan.events().getFirst();
        Case holdout = holdout();

        assertRejected(with(plan, new Event(holdout.id(), holdout.text(), first.atomId(), first.chunkKey(),
                FeedbackSignal.POSITIVE, "E2")), List.of(), "may only come from a DEV case");
        assertRejected(with(plan, new Event(first.caseId(), holdout.text(), first.atomId(), first.chunkKey(),
                first.signal(), first.source())), List.of(), "not the case's exact text");
        assertRejected(with(plan, new Event(first.caseId(), first.query(), BookFeedbackExperiment.UNKNOWN_ATOM,
                first.chunkKey(), first.signal(), first.source())), List.of(), "no feedback is written for an invalid"
                + " atom id");
        assertRejected(with(plan, first), List.of(), "duplicate feedback");
        assertRejected(plan, List.of(first.query()), "equals a contamination probe");
        assertRejected(with(plan, new Event(first.caseId(), first.query(), first.atomId(), first.chunkKey(),
                first.signal() == FeedbackSignal.POSITIVE ? FeedbackSignal.NEGATIVE : FeedbackSignal.POSITIVE,
                first.source())), List.of(), "duplicate feedback");
    }

    private static Plan with(Plan plan, Event extra) {
        List<Event> events = new ArrayList<>(plan.events());
        events.add(extra);
        return new Plan(events, plan.digest(), plan.casesWithFeedback(), plan.sharedWithHoldout());
    }

    private void assertRejected(Plan plan, List<String> probes, String message) {
        BookInputException e = assertThrows(BookInputException.class,
                () -> BookFeedbackPlan.validate(plan, dataset, atoms, probes));
        assertTrue(e.getMessage().startsWith("invalid feedback plan: "), e.getMessage());
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
