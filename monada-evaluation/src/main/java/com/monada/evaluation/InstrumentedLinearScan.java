package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.index.ResonanceIndex;
import com.monada.storage.AtomStore;
import com.monada.storage.FrequencyStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Evaluation-only, phase-instrumented equivalent of
 * {@code com.monada.index.LinearScanResonanceIndex#search}.
 *
 * <p>It performs the same work in the same I/O order (atoms first, then vectors), with the same
 * cosine formula, {@code >=} threshold, keep-max dedup by atom id, and score-desc/id-asc ordering,
 * but splits the production loop into separately timed phases. Splitting scoring from dedup costs
 * an extra score array and extra timer reads, so the sum of phases is an upper-bound estimate of
 * the production total, not an exact decomposition. Result parity with the production index is
 * verified by the callers, never assumed.
 */
final class InstrumentedLinearScan {

    private InstrumentedLinearScan() {
    }

    /** Per-phase wall-clock nanos (diagnostic) plus machine-independent structural counts. */
    record PhaseTrace(
            List<ResonanceResult> results,
            List<ResonanceResult> candidates,
            long atomLoadNanos,
            long vectorLoadNanos,
            long scoringNanos,
            long dedupNanos,
            long sortNanos,
            long truncateNanos,
            long totalNanos,
            long atomsLoaded,
            long vectorsVisited,
            long orphanVectors,
            long cosineEvaluations,
            long candidateCount,
            long resultsReturned) {

        long sumOfPhasesNanos() {
            return atomLoadNanos + vectorLoadNanos + scoringNanos + dedupNanos + sortNanos + truncateNanos;
        }

        /** Timer reads and phase splitting not attributable to any single phase. */
        long unattributedNanos() {
            return Math.max(0L, totalNanos - sumOfPhasesNanos());
        }
    }

    static PhaseTrace search(
            AtomStore atomStore,
            FrequencyStore frequencyStore,
            FrequencyVector queryVector,
            int topK,
            double threshold) throws IOException {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }
        long t0 = System.nanoTime();

        // Phase 1: atom load + id map.
        var atomsById = new HashMap<String, KnowledgeAtom>();
        var atoms = atomStore.findAll();
        for (var atom : atoms) {
            atomsById.put(atom.id(), atom);
        }
        long t1 = System.nanoTime();

        // Phase 2: vector load (I/O + materialization of every stored vector).
        var stored = frequencyStore.findAll();
        long t2 = System.nanoTime();

        // Phase 3: cosine scoring of every vector whose atom exists (orphans skipped).
        var scores = new double[stored.size()];
        var scoredAtoms = new KnowledgeAtom[stored.size()];
        int scored = 0;
        for (var storedVector : stored) {
            var atom = atomsById.get(storedVector.atomId());
            if (atom == null) {
                continue;
            }
            scoredAtoms[scored] = atom;
            scores[scored] = cosineSimilarity(queryVector, storedVector.vector());
            scored++;
        }
        long t3 = System.nanoTime();

        // Phase 4: threshold filter + keep-max dedup by atom id, in storage order.
        var resultsByAtomId = new HashMap<String, ResonanceResult>();
        for (int i = 0; i < scored; i++) {
            var score = scores[i];
            if (score >= threshold) {
                var atom = scoredAtoms[i];
                var current = resultsByAtomId.get(atom.id());
                if (current == null || score > current.score()) {
                    resultsByAtomId.put(atom.id(), new ResonanceResult(atom, score));
                }
            }
        }
        var results = new ArrayList<>(resultsByAtomId.values());
        long t4 = System.nanoTime();
        var candidates = List.copyOf(results);
        // The defensive copy above is only for diagnostics/parity and is not timed as a phase;
        // re-anchor the clock so it is not charged to the sort.
        long t4b = System.nanoTime();

        // Phase 5: full O(M log M) sort, score desc then atom id asc.
        results.sort(
                Comparator.comparingDouble(ResonanceResult::score).reversed()
                        .thenComparing(result -> result.atom().id()));
        long t5 = System.nanoTime();

        // Phase 6: truncate to topK.
        List<ResonanceResult> finalResults = results.size() > topK
                ? List.copyOf(results.subList(0, topK))
                : List.copyOf(results);
        long t6 = System.nanoTime();

        return new PhaseTrace(
                finalResults,
                candidates,
                t1 - t0,
                t2 - t1,
                t3 - t2,
                t4 - t3,
                t5 - t4b,
                t6 - t5,
                t6 - t0,
                atoms.size(),
                stored.size(),
                stored.size() - scored,
                scored,
                candidates.size(),
                finalResults.size());
    }

    /**
     * Proves the trace equals the production index for this query: the final top-K and the full
     * deduplicated candidate set (obtained by asking the production index for every candidate)
     * must match by atom id and bit-identical score, in the same order.
     */
    static void verifyParity(
            ResonanceIndex production,
            FrequencyVector queryVector,
            int topK,
            double threshold,
            PhaseTrace trace) throws IOException {
        assertSame("top-K", production.search(queryVector, topK, threshold), trace.results());
        var sortedCandidates = new ArrayList<>(trace.candidates());
        sortedCandidates.sort(
                Comparator.comparingDouble(ResonanceResult::score).reversed()
                        .thenComparing(result -> result.atom().id()));
        assertSame("candidate set", production.search(queryVector, Integer.MAX_VALUE, threshold), sortedCandidates);
    }

    private static void assertSame(String what, List<ResonanceResult> expected, List<ResonanceResult> actual) {
        boolean same = expected.size() == actual.size();
        for (int i = 0; same && i < expected.size(); i++) {
            same = expected.get(i).atom().id().equals(actual.get(i).atom().id())
                    && Double.doubleToLongBits(expected.get(i).score())
                    == Double.doubleToLongBits(actual.get(i).score());
        }
        if (!same) {
            throw new IllegalStateException("instrumented scan diverged from production on " + what
                    + ": production=" + ids(expected) + ", instrumented=" + ids(actual));
        }
    }

    private static List<String> ids(List<ResonanceResult> results) {
        return results.stream().map(r -> r.atom().id()).toList();
    }

    /** Verbatim copy of the production cosine formula so doubles are bit-identical. */
    static double cosineSimilarity(FrequencyVector left, FrequencyVector right) {
        var leftValues = left.values();
        var rightValues = right.values();
        if (leftValues.length != rightValues.length) {
            throw new IllegalArgumentException("Vectors must have the same dimensions");
        }

        var dot = 0.0;
        var leftMagnitude = 0.0;
        var rightMagnitude = 0.0;
        for (var i = 0; i < leftValues.length; i++) {
            dot += leftValues[i] * rightValues[i];
            leftMagnitude += leftValues[i] * leftValues[i];
            rightMagnitude += rightValues[i] * rightValues[i];
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }
}
