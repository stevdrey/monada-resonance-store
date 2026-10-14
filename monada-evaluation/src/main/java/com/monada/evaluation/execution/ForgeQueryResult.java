package com.monada.evaluation.execution;

import com.monada.evaluation.HitAtK;
import com.monada.evaluation.PrecisionAtK;
import com.monada.evaluation.RecallAtK;
import com.monada.evaluation.ReciprocalRank;
import com.monada.evaluation.execution.ForgeEvaluationReport.RankedHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One judged query with its ranked hits and, when the relevant set is non-empty, its EXPLORATORY metrics.
 * Queries with an empty relevant set (NO_RELEVANT, BLANK) carry no metrics and enter no average.
 */
public record ForgeQueryResult(ForgeFixture.Query query, String projectionState, List<RankedHit> ranked,
                               List<Integer> metricKs, Map<Integer, Double> precision, Map<Integer, Double> recall,
                               Map<Integer, Double> hit, double reciprocalRank, Map<String, String> missing) {

    public ForgeQueryResult {
        ranked = List.copyOf(ranked);
        metricKs = List.copyOf(metricKs);
        precision = Map.copyOf(precision);
        recall = Map.copyOf(recall);
        hit = Map.copyOf(hit);
        missing = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(missing)); // keeps report order stable
    }

    /** Ranked experience ids, best first. */
    public List<String> rankedIds() {
        return ranked.stream().map(RankedHit::experienceId).toList();
    }

    static ForgeQueryResult of(ForgeFixture.Query query, String projectionState, List<RankedHit> ranked,
                               List<Integer> ks) {
        Set<String> relevant = query.relevant();
        if (relevant.isEmpty()) {
            return new ForgeQueryResult(query, projectionState, ranked, ks, Map.of(), Map.of(), Map.of(), 0.0,
                    Map.of());
        }
        List<String> ids = ranked.stream().map(RankedHit::experienceId).toList();
        Map<Integer, Double> p = new LinkedHashMap<>();
        Map<Integer, Double> r = new LinkedHashMap<>();
        Map<Integer, Double> h = new LinkedHashMap<>();
        for (int k : ks) {
            p.put(k, PrecisionAtK.compute(relevant, ids, k));
            r.put(k, RecallAtK.compute(relevant, ids, k));
            h.put(k, HitAtK.compute(relevant, ids, k));
        }
        int deepest = ks.get(ks.size() - 1);
        Map<String, String> missing = new LinkedHashMap<>();
        for (String id : relevant.stream().sorted().toList()) {
            int index = ids.indexOf(id);
            if (index < 0) {
                missing.put(id, "not retrieved within the recall limit of " + ids.size() + " hits");
            } else if (index >= deepest) {
                missing.put(id, "ranked #" + (index + 1) + ", outside the top " + deepest);
            }
        }
        return new ForgeQueryResult(query, projectionState, ranked, ks, p, r, h,
                ReciprocalRank.compute(relevant, ids), missing);
    }
}
