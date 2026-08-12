package com.monada.evaluation;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/** Deterministic adversarial evidence for the current normalized feedback key. */
public record NormalizedQueryKeyStressReport(
        List<NormalizedQueryKeyStressObservation> observations,
        List<NormalizedQueryKeyStressCategorySummary> categorySummaries,
        NormalizedQueryKeyStressDecision decision
) {
    public NormalizedQueryKeyStressReport {
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        categorySummaries = List.copyOf(Objects.requireNonNull(categorySummaries, "categorySummaries"));
        Objects.requireNonNull(decision, "decision");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("observations must not be empty");
        }
        Set<NormalizationStressCategory> seen = EnumSet.noneOf(NormalizationStressCategory.class);
        for (NormalizedQueryKeyStressCategorySummary summary : categorySummaries) {
            Objects.requireNonNull(summary, "category summary");
            if (!seen.add(summary.category())) {
                throw new IllegalArgumentException("duplicate category summary: " + summary.category());
            }
        }
        if (!seen.equals(EnumSet.allOf(NormalizationStressCategory.class))) {
            throw new IllegalArgumentException("category summaries must cover every stress category");
        }
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append("Normalized Query-Key Adversarial Stress\n");
        sb.append("=======================================\n\n");
        sb.append("Mode: EXPLORATORY (production defaults are unchanged)\n");
        sb.append("Strategy: NormalizedQueryKeyStrategy\n");
        sb.append("Store: isolated real FileFeedbackStore JSONL per pair\n");
        sb.append("Full-corpus ranks: separate diagnostic topK(corpusSize) executions\n");
        sb.append("Pairs: ").append(observations.size()).append("\n\n");
        appendSummaryMatrix(sb);
        appendEvidenceByCategory(sb);
        sb.append("Decision signal: ").append(decision).append('\n');
        sb.append("ExactQueryKeyStrategy remains the production/default behavior.\n");
        return sb.toString();
    }

    private void appendSummaryMatrix(StringBuilder sb) {
        sb.append("Transformation Summary\n");
        sb.append("----------------------\n");
        sb.append("Category | pairs | equivalent match | distinct collisions | contamination\n");
        for (NormalizedQueryKeyStressCategorySummary summary : categorySummaries) {
            sb.append(summary.category()).append(" | ")
                    .append(summary.pairCount()).append(" | ")
                    .append(rate(summary.equivalentKeyMatchCount(), summary.equivalentPairCount(),
                            summary.equivalentKeyMatchRate())).append(" | ")
                    .append(rate(summary.distinctKeyCollisionCount(), summary.distinctPairCount(),
                            summary.distinctKeyCollisionRate())).append(" | ")
                    .append(rate(summary.contaminationCount(), summary.distinctPairCount(),
                            summary.contaminationRate())).append('\n');
        }
        sb.append('\n');
    }

    private void appendEvidenceByCategory(StringBuilder sb) {
        sb.append("Per-Pair Evidence\n");
        sb.append("-----------------\n");
        for (NormalizationStressCategory category : NormalizationStressCategory.values()) {
            sb.append("Category: ").append(category).append('\n');
            observations.stream()
                    .filter(observation -> observation.stressCase().category() == category)
                    .forEach(observation -> appendObservation(sb, observation));
            sb.append('\n');
        }
    }

    private void appendObservation(
            StringBuilder sb,
            NormalizedQueryKeyStressObservation observation) {
        NormalizedQueryKeyStressCase stressCase = observation.stressCase();
        sb.append("  Case: ").append(stressCase.id()).append('\n');
        sb.append("    Semantic relationship: ").append(stressCase.semanticRelationship()).append('\n');
        sb.append("    Expected key relation: ").append(stressCase.expectedKeyRelation()).append('\n');
        sb.append("    Query A: ").append(visible(stressCase.queryA())).append('\n');
        sb.append("    Query B: ").append(visible(stressCase.queryB())).append('\n');
        sb.append("    Normalized A: ").append(visible(observation.normalizedA())).append('\n');
        sb.append("    Normalized B: ").append(visible(observation.normalizedB())).append('\n');
        sb.append("    Feedback key A: ").append(visible(observation.queryKeyA())).append('\n');
        sb.append("    Feedback key B: ").append(visible(observation.queryKeyB())).append('\n');
        sb.append("    Keys matched: ").append(observation.keysMatched()).append('\n');
        sb.append("    Relevant targets A: ").append(stressCase.relevantTargetsA()).append('\n');
        sb.append("    Relevant targets B: ").append(stressCase.relevantTargetsB()).append('\n');
        sb.append("    Replay target: ").append(stressCase.feedbackTargetLabel())
                .append(" (relevant to B: ")
                .append(stressCase.relevantTargetsB().contains(stressCase.feedbackTargetLabel()))
                .append(")\n");
        sb.append(String.format(Locale.ROOT,
                "    Target baseline: full-corpus-rank=%d, score=%.6f%n",
                observation.targetBeforeReplay().fullCorpusRank(),
                observation.targetBeforeReplay().score()));
        sb.append(String.format(Locale.ROOT,
                "    Target replayed: full-corpus-rank=%d, score=%.6f%n",
                observation.targetAfterReplay().fullCorpusRank(),
                observation.targetAfterReplay().score()));
        sb.append("    Target rank change: ").append(observation.targetRankChange()).append('\n');
        sb.append("    Target score changed: ").append(observation.scoreChanged()).append('\n');
        sb.append("    Baseline top-K: ").append(observation.baselineTopK()).append('\n');
        sb.append("    Baseline top-K prefix consistent: ")
                .append(observation.baselineTopKPrefixConsistent()).append('\n');
        sb.append("    Replayed top-K: ").append(observation.replayedTopK()).append('\n');
        sb.append("    Replayed top-K prefix consistent: ")
                .append(observation.replayedTopKPrefixConsistent()).append('\n');
        if (!observation.baselineTopKPrefixConsistent()
                || !observation.replayedTopKPrefixConsistent()) {
            sb.append("    Prefix warning: full-corpus-rank comes from a separate ")
                    .append("topK(corpusSize) diagnostic execution and must not be interpreted as ")
                    .append("equivalent to normal evaluation top-K placement.\n");
        }
        sb.append("    Classification: ").append(observation.classification()).append('\n');
    }

    private String rate(int numerator, int denominator, OptionalDouble rate) {
        if (rate.isEmpty()) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "%d/%d (%.4f)", numerator, denominator, rate.getAsDouble());
    }

    private String visible(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n");
    }
}
