package com.monada.evaluation;

import java.util.Locale;

/** Shared deterministic text rendering for standard and profile reports. */
final class TextEncodingDiagnosticRenderer {

    private TextEncodingDiagnosticRenderer() {
    }

    static void append(StringBuilder target, TextEncodingDiagnostic diagnostic, String indent) {
        target.append(indent).append("Encoding Contributions\n");
        target.append(indent).append("  Query terms:\n");
        appendRepresentation(target, diagnostic.queryRepresentation(), indent + "    ");

        for (TextEncodingCandidateDiagnostic candidate : diagnostic.candidates()) {
            target.append(indent).append("  Candidate: ").append(candidate.label())
                    .append(" [").append(candidate.selection());
            if (candidate.rank().isPresent()) {
                target.append(String.format(
                        Locale.ROOT,
                        candidate.selection() == TextEncodingCandidateSelection.MISSED_EXPECTED
                                ? ", full-corpus-rank=%d, score=%.6f"
                                : ", rank=%d, score=%.6f",
                        candidate.rank().getAsInt(),
                        candidate.score().getAsDouble()));
            } else {
                target.append(", unranked");
            }
            target.append("]\n");
            target.append(indent).append("    Lexical overlap: ")
                    .append(candidate.overlappingTerms().isEmpty()
                            ? "(none)"
                            : candidate.overlappingTerms())
                    .append('\n');
            target.append(indent).append("    Terms:\n");
            appendRepresentation(target, candidate.representation(), indent + "      ");
        }

        if (diagnostic.omittedTopResultCount() > 0) {
            target.append(indent).append("  Omitted top-ranked candidates: ")
                    .append(diagnostic.omittedTopResultCount()).append('\n');
        }
        if (diagnostic.omittedMissedExpectedCount() > 0) {
            target.append(indent).append("  Omitted missed expected candidates: ")
                    .append(diagnostic.omittedMissedExpectedCount()).append('\n');
        }
    }

    private static void appendRepresentation(
            StringBuilder target,
            TextEncodingRepresentationDiagnostic representation,
            String indent) {
        if (representation.terms().isEmpty()) {
            target.append(indent).append("(none)\n");
        } else {
            for (TextEncodingTermContribution term : representation.terms()) {
                target.append(String.format(
                        Locale.ROOT,
                        "%s%s: %s weight=%.6f occurrences=%d total=%.6f%n",
                        indent,
                        term.source(),
                        term.term(),
                        term.appliedWeight(),
                        term.occurrences(),
                        term.totalWeight()));
            }
        }
        if (representation.omittedTermCount() > 0) {
            target.append(indent).append("... omitted terms: ")
                    .append(representation.omittedTermCount()).append('\n');
        }
    }
}
