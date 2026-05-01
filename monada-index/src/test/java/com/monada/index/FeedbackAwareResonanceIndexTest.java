package com.monada.index;

import com.monada.core.AtomType;
import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.storage.feedback.FeedbackEvent;
import com.monada.storage.feedback.FeedbackSignal;
import com.monada.storage.feedback.FeedbackStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedbackAwareResonanceIndexTest {

    private static final FrequencyVector DUMMY = new FrequencyVector(new float[]{1.0f});

    @Test
    void withoutEventsPreservesDelegateOrdering() throws IOException {
        var base = List.of(result("a", 0.9), result("b", 0.7), result("c", 0.5));
        var index = new FeedbackAwareResonanceIndex(
                new FakeDelegate(base), new FakeFeedbackStore(List.of()), "q");

        var out = index.search(DUMMY, 3, 0.0);
        assertEquals(List.of("a", "b", "c"), ids(out));
    }

    @Test
    void positiveFeedbackPromotesRelevantAtom() throws IOException {
        var base = List.of(result("a", 0.50), result("b", 0.40), result("c", 0.30));
        var events = List.of(event("q", "b", FeedbackSignal.POSITIVE, 0.2));
        var index = new FeedbackAwareResonanceIndex(
                new FakeDelegate(base), new FakeFeedbackStore(events), "q");

        var out = index.search(DUMMY, 3, 0.0);
        assertEquals(List.of("b", "a", "c"), ids(out));
        assertEquals(0.6, out.get(0).score(), 1e-9);
    }

    @Test
    void negativeFeedbackDemotesAtom() throws IOException {
        var base = List.of(result("a", 0.50), result("b", 0.40), result("c", 0.30));
        var events = List.of(event("q", "a", FeedbackSignal.NEGATIVE, -0.3));
        var index = new FeedbackAwareResonanceIndex(
                new FakeDelegate(base), new FakeFeedbackStore(events), "q");

        var out = index.search(DUMMY, 3, 0.0);
        assertEquals(List.of("b", "c", "a"), ids(out));
    }

    @Test
    void tiesAreBrokenByAtomIdAsc() throws IOException {
        var base = List.of(result("b", 0.5), result("a", 0.5), result("c", 0.5));
        var index = new FeedbackAwareResonanceIndex(
                new FakeDelegate(base), new FakeFeedbackStore(List.of()), "q");

        var out = index.search(DUMMY, 3, 0.0);
        assertEquals(List.of("a", "b", "c"), ids(out));
    }

    @Test
    void feedbackOnlyAppliesToMatchingQuery() throws IOException {
        var base = List.of(result("a", 0.50), result("b", 0.40));
        var events = List.of(event("other", "b", FeedbackSignal.POSITIVE, 0.5));
        var index = new FeedbackAwareResonanceIndex(
                new FakeDelegate(base), new FakeFeedbackStore(events), "q");

        var out = index.search(DUMMY, 2, 0.0);
        assertEquals(List.of("a", "b"), ids(out));
    }

    @Test
    void positivePoolExpansionCanSurfaceAtomBelowThreshold() throws IOException {
        // Delegate returns 3 candidates with scores below 0.5; feedback pushes "c" above.
        var base = List.of(result("a", 0.45), result("b", 0.40), result("c", 0.30));
        var events = List.of(event("q", "c", FeedbackSignal.POSITIVE, 0.30));
        var index = new FeedbackAwareResonanceIndex(
                new FakeDelegate(base), new FakeFeedbackStore(events), "q");

        // Caller asks for topK=1 with threshold 0.5 — only "c" (0.60) should pass.
        var out = index.search(DUMMY, 1, 0.5);
        assertEquals(List.of("c"), ids(out));
        assertEquals(0.6, out.get(0).score(), 1e-9);
    }

    private static ResonanceResult result(String id, double score) {
        var atom = new KnowledgeAtom(id, AtomType.TEXT, id, Map.of(), 1.0, Instant.EPOCH);
        return new ResonanceResult(atom, score);
    }

    private static FeedbackEvent event(String query, String atomId, FeedbackSignal signal, double delta) {
        return new FeedbackEvent(query, atomId, signal, delta, Instant.EPOCH);
    }

    private static List<String> ids(List<ResonanceResult> results) {
        return results.stream().map(r -> r.atom().id()).toList();
    }

    private static final class FakeDelegate implements ResonanceIndex {
        private final List<ResonanceResult> results;

        FakeDelegate(List<ResonanceResult> results) {
            this.results = results;
        }

        @Override
        public List<ResonanceResult> search(FrequencyVector queryVector, int topK, double threshold) {
            var sorted = new ArrayList<>(results);
            sorted.sort(Comparator.comparingDouble(ResonanceResult::score).reversed()
                    .thenComparing(r -> r.atom().id()));
            var filtered = new ArrayList<ResonanceResult>();
            for (var r : sorted) {
                if (r.score() >= threshold) {
                    filtered.add(r);
                }
                if (filtered.size() >= topK) {
                    break;
                }
            }
            return List.copyOf(filtered);
        }
    }

    private static final class FakeFeedbackStore implements FeedbackStore {
        private final List<FeedbackEvent> events;

        FakeFeedbackStore(List<FeedbackEvent> events) {
            this.events = events;
        }

        @Override
        public void append(FeedbackEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FeedbackEvent> findAll() {
            return events;
        }

        @Override
        public List<FeedbackEvent> findByQuery(String query) {
            return events.stream().filter(e -> e.query().equals(query)).toList();
        }
    }
}
