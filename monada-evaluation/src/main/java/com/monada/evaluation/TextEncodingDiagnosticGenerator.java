package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.core.KnowledgeAtom;
import com.monada.encoder.WeightedAtomEncoder;
import com.monada.encoder.WeightedToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Builds bounded diagnostics from the same weighted representations used by retrieval. */
final class TextEncodingDiagnosticGenerator {

    private static final Comparator<RankedResult> RANK_ORDER = Comparator
            .comparingInt(RankedResult::rank)
            .thenComparing(RankedResult::label);
    private static final Comparator<TermKey> TERM_ORDER = Comparator
            .comparing(TermKey::source)
            .thenComparing(TermKey::term)
            .thenComparingDouble(TermKey::weight);

    private TextEncodingDiagnosticGenerator() {
    }

    static TextEncodingDiagnostic generate(
            EvaluationDataset dataset,
            EvaluationQuery query,
            List<RankedResult> standardRanking,
            List<RankedResult> diagnosticRanking,
            MonadaMemoryOptions memoryOptions,
            TextEncodingDiagnosticOptions diagnosticOptions) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(query, "query");
        standardRanking = List.copyOf(Objects.requireNonNull(standardRanking, "standardRanking"));
        diagnosticRanking = List.copyOf(Objects.requireNonNull(diagnosticRanking, "diagnosticRanking"));
        Objects.requireNonNull(memoryOptions, "memoryOptions");
        Objects.requireNonNull(diagnosticOptions, "diagnosticOptions");
        if (!diagnosticOptions.enabled()) {
            throw new IllegalArgumentException("diagnosticOptions must be enabled");
        }

        Map<String, DatasetAtom> atomsByLabel = new HashMap<>();
        for (DatasetAtom atom : dataset.atoms()) {
            atomsByLabel.put(atom.label(), atom);
        }

        var normalizedQuery = memoryOptions.textNormalizer().normalize(query.text());
        var queryParts = normalizedQuery.toWeightedTextParts(memoryOptions.expansionOptions());
        List<TaggedToken> queryTokens = new ArrayList<>();
        addTagged(queryTokens, queryParts.originalTokens(), TextEncodingContributionSource.ORIGINAL);
        addTagged(queryTokens, queryParts.expansionTokens(), TextEncodingContributionSource.EXPANSION);
        Set<String> queryTerms = tokenSet(queryTokens);
        var queryRepresentation = representation(
                queryTokens, diagnosticOptions.maxTermsPerRepresentation());

        List<TextEncodingCandidateDiagnostic> candidates = new ArrayList<>();
        int selectedTopCount = Math.min(
                standardRanking.size(), diagnosticOptions.maxTopResults());
        for (int i = 0; i < selectedTopCount; i++) {
            RankedResult ranked = standardRanking.get(i);
            candidates.add(candidate(
                    requireAtom(atomsByLabel, ranked.label()),
                    TextEncodingCandidateSelection.TOP_RANKED,
                    ranked,
                    queryTerms,
                    memoryOptions,
                    diagnosticOptions.maxTermsPerRepresentation()));
        }

        Set<String> returnedLabels = new HashSet<>();
        for (RankedResult result : standardRanking) {
            returnedLabels.add(result.label());
        }
        Map<String, RankedResult> diagnosticByLabel = new HashMap<>();
        for (RankedResult result : diagnosticRanking) {
            diagnosticByLabel.put(result.label(), result);
        }

        List<MissingExpected> missingExpected = new ArrayList<>();
        for (String expected : query.expectedLabels()) {
            if (!returnedLabels.contains(expected)) {
                missingExpected.add(new MissingExpected(expected, diagnosticByLabel.get(expected)));
            }
        }
        missingExpected.sort(Comparator
                .comparing(MissingExpected::ranked, Comparator.nullsLast(RANK_ORDER))
                .thenComparing(MissingExpected::label));

        int selectedMissingCount = Math.min(
                missingExpected.size(), diagnosticOptions.maxMissedExpected());
        for (int i = 0; i < selectedMissingCount; i++) {
            MissingExpected missing = missingExpected.get(i);
            candidates.add(candidate(
                    requireAtom(atomsByLabel, missing.label()),
                    TextEncodingCandidateSelection.MISSED_EXPECTED,
                    missing.ranked(),
                    queryTerms,
                    memoryOptions,
                    diagnosticOptions.maxTermsPerRepresentation()));
        }

        return new TextEncodingDiagnostic(
                queryRepresentation,
                candidates,
                standardRanking.size() - selectedTopCount,
                missingExpected.size() - selectedMissingCount);
    }

    private static TextEncodingCandidateDiagnostic candidate(
            DatasetAtom atom,
            TextEncodingCandidateSelection selection,
            RankedResult ranked,
            Set<String> queryTerms,
            MonadaMemoryOptions memoryOptions,
            int maxTerms) {
        var knowledgeAtom = KnowledgeAtom.text(atom.content(), atom.aliases());
        var parts = WeightedAtomEncoder.toWeightedTextParts(
                knowledgeAtom,
                memoryOptions.textNormalizer(),
                memoryOptions.expansionOptions());

        List<TaggedToken> tokens = new ArrayList<>();
        addTagged(tokens, parts.originalTokens(), TextEncodingContributionSource.ORIGINAL);
        addTagged(tokens, parts.expansionTokens(), TextEncodingContributionSource.EXPANSION);
        addTagged(tokens, parts.aliasTokens(), TextEncodingContributionSource.ALIAS);
        addTagged(tokens, parts.aliasExpansionTokens(), TextEncodingContributionSource.ALIAS_EXPANSION);

        Set<String> overlaps = new TreeSet<>(tokenSet(tokens));
        overlaps.retainAll(queryTerms);

        return new TextEncodingCandidateDiagnostic(
                atom.label(),
                selection,
                ranked == null ? OptionalInt.empty() : OptionalInt.of(ranked.rank()),
                ranked == null ? OptionalDouble.empty() : OptionalDouble.of(ranked.score()),
                representation(tokens, maxTerms),
                List.copyOf(overlaps));
    }

    private static TextEncodingRepresentationDiagnostic representation(
            List<TaggedToken> tokens,
            int maxTerms) {
        Map<TermKey, Integer> occurrences = new HashMap<>();
        for (TaggedToken tagged : tokens) {
            var key = new TermKey(
                    tagged.source(), tagged.token().token(), tagged.token().weight());
            occurrences.merge(key, 1, Integer::sum);
        }

        List<TermKey> keys = new ArrayList<>(occurrences.keySet());
        keys.sort(TERM_ORDER);
        int displayed = Math.min(keys.size(), maxTerms);
        List<TextEncodingTermContribution> contributions = new ArrayList<>(displayed);
        for (int i = 0; i < displayed; i++) {
            TermKey key = keys.get(i);
            int count = occurrences.get(key);
            contributions.add(new TextEncodingTermContribution(
                    key.term(), key.source(), key.weight(), count, key.weight() * count));
        }
        return new TextEncodingRepresentationDiagnostic(
                contributions, keys.size() - displayed);
    }

    private static void addTagged(
            List<TaggedToken> target,
            List<WeightedToken> tokens,
            TextEncodingContributionSource source) {
        for (WeightedToken token : tokens) {
            target.add(new TaggedToken(source, token));
        }
    }

    private static Set<String> tokenSet(List<TaggedToken> tokens) {
        return tokens.stream()
                .map(TaggedToken::token)
                .map(WeightedToken::token)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static DatasetAtom requireAtom(Map<String, DatasetAtom> atomsByLabel, String label) {
        DatasetAtom atom = atomsByLabel.get(label);
        if (atom == null) {
            throw new IllegalStateException("ranked label is not present in the evaluation dataset: " + label);
        }
        return atom;
    }

    record RankedResult(String label, int rank, double score) {
        RankedResult {
            Objects.requireNonNull(label, "label");
            if (label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
            if (rank <= 0) {
                throw new IllegalArgumentException("rank must be positive: " + rank);
            }
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("score must be finite: " + score);
            }
        }
    }

    private record TaggedToken(TextEncodingContributionSource source, WeightedToken token) {
    }

    private record TermKey(TextEncodingContributionSource source, String term, double weight) {
    }

    private record MissingExpected(String label, RankedResult ranked) {
    }
}
