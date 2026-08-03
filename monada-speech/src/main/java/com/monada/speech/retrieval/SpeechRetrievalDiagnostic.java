package com.monada.speech.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Side-effect-free structural and score diagnostics for one acoustic query.
 *
 * <p>Score statistics are {@code 0.0} when no candidate was scored. {@code tieCount}
 * counts each scored candidate after the first in an exact-score tie group.
 */
public record SpeechRetrievalDiagnostic(
        int scannedVectorCount,
        int orphanVectorCount,
        int incompatibleDimensionCount,
        int metadataFilteredCandidateCount,
        int scoredCandidateCount,
        int tieCount,
        double minimumScore,
        double meanScore,
        double maximumScore,
        double topResultScore,
        double topScoreGap,
        List<SpeechCandidateDiagnostic> candidates
) {
    public SpeechRetrievalDiagnostic {
        requireNonNegative(scannedVectorCount, "scannedVectorCount");
        requireNonNegative(orphanVectorCount, "orphanVectorCount");
        requireNonNegative(incompatibleDimensionCount, "incompatibleDimensionCount");
        requireNonNegative(metadataFilteredCandidateCount, "metadataFilteredCandidateCount");
        requireNonNegative(scoredCandidateCount, "scoredCandidateCount");
        requireNonNegative(tieCount, "tieCount");
        requireFinite(minimumScore, "minimumScore");
        requireFinite(meanScore, "meanScore");
        requireFinite(maximumScore, "maximumScore");
        requireFinite(topResultScore, "topResultScore");
        requireFinite(topScoreGap, "topScoreGap");

        int classifiedCount = orphanVectorCount + incompatibleDimensionCount
                + metadataFilteredCandidateCount + scoredCandidateCount;
        if (classifiedCount != scannedVectorCount) {
            throw new IllegalArgumentException(
                    "classified candidate counts must equal scannedVectorCount: "
                            + classifiedCount + " != " + scannedVectorCount);
        }
        if (tieCount > Math.max(0, scoredCandidateCount - 1)) {
            throw new IllegalArgumentException("tieCount exceeds possible scored-candidate ties: " + tieCount);
        }
        if (topScoreGap < 0.0) {
            throw new IllegalArgumentException("topScoreGap must be non-negative: " + topScoreGap);
        }
        if (scoredCandidateCount == 0
                && (minimumScore != 0.0 || meanScore != 0.0 || maximumScore != 0.0
                || topResultScore != 0.0 || topScoreGap != 0.0)) {
            throw new IllegalArgumentException("score statistics must be 0.0 when no candidate was scored");
        }
        if (scoredCandidateCount > 0) {
            if (minimumScore > meanScore || meanScore > maximumScore) {
                throw new IllegalArgumentException("score statistics must satisfy minimum <= mean <= maximum");
            }
            if (Double.compare(topResultScore, maximumScore) != 0) {
                throw new IllegalArgumentException("topResultScore must equal maximumScore");
            }
        }

        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (candidates.size() != scannedVectorCount) {
            throw new IllegalArgumentException(
                    "candidates.size() must equal scannedVectorCount: "
                            + candidates.size() + " != " + scannedVectorCount);
        }
        for (int i = 1; i < candidates.size(); i++) {
            if (Comparator.comparing(SpeechCandidateDiagnostic::sampleId)
                    .compare(candidates.get(i - 1), candidates.get(i)) > 0) {
                throw new IllegalArgumentException("candidates must be sorted by sampleId");
            }
        }

        int actualOrphanCount = 0;
        int actualIncompatibleCount = 0;
        int actualFilteredCount = 0;
        List<SpeechCandidateDiagnostic> scoredByRank = new ArrayList<>();
        for (SpeechCandidateDiagnostic candidate : candidates) {
            switch (candidate.status()) {
                case ORPHAN_VECTOR -> actualOrphanCount++;
                case INCOMPATIBLE_DIMENSIONS -> actualIncompatibleCount++;
                case METADATA_FILTERED -> actualFilteredCount++;
                case SCORED -> scoredByRank.add(candidate);
            }
        }
        if (actualOrphanCount != orphanVectorCount
                || actualIncompatibleCount != incompatibleDimensionCount
                || actualFilteredCount != metadataFilteredCandidateCount
                || scoredByRank.size() != scoredCandidateCount) {
            throw new IllegalArgumentException("candidate statuses must match aggregate counts");
        }

        scoredByRank.sort(Comparator.comparingInt(SpeechCandidateDiagnostic::rank));
        int actualTieCount = 0;
        for (int i = 0; i < scoredByRank.size(); i++) {
            if (scoredByRank.get(i).rank() != i + 1) {
                throw new IllegalArgumentException("scored candidate ranks must be contiguous from 1");
            }
            if (i > 0 && Double.compare(
                    scoredByRank.get(i).score(),
                    scoredByRank.get(i - 1).score()) == 0) {
                actualTieCount++;
            }
        }
        if (actualTieCount != tieCount) {
            throw new IllegalArgumentException("candidate scores must match tieCount");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative: " + value);
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
