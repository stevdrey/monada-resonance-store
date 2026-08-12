package com.monada.evaluation.datasets;

import com.monada.evaluation.DatasetAtom;
import com.monada.evaluation.EvaluationDataset;
import com.monada.evaluation.EvaluationQuery;
import com.monada.evaluation.NormalizedQueryKeyStressCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Project-memory corpus plus focused atoms for unambiguous normalization stress judgments. */
public final class NormalizedQueryKeyStressDataset {

    private static final List<DatasetAtom> STRESS_ATOMS = List.of(
            atom("ka_stress_coordination_and",
                    "The validation contract requires both storage and index checks; both checks must pass."),
            atom("ka_stress_coordination_or",
                    "The validation contract permits either the storage check or the index check; one is enough."),
            atom("ka_stress_rebuild_and_reject",
                    "The migration procedure rebuilds vectors and rejects the old store; it performs both actions."),
            atom("ka_stress_rebuild_or_reject",
                    "The migration procedure either rebuilds vectors or rejects the old store; it chooses one action."),
            atom("ka_stress_database_to_ledger",
                    "Database to ledger replication copies database records into a durable ledger."),
            atom("ka_stress_database_in_ledger",
                    "A database in a ledger stores relational state inside the ledger container."),
            atom("ka_stress_speech_in_text",
                    "Speech metadata in text memory is stored inside the text-memory representation."),
            atom("ka_stress_speech_on_text",
                    "Speech retrieval on text memory runs above text memory as an input layer."),
            atom("ka_stress_ranking_with_feedback",
                    "Ranking with feedback combines resonance scores with historical feedback signals."),
            atom("ka_stress_ranking_for_feedback",
                    "Ranking for feedback orders candidates so a reviewer can submit feedback afterward."),
            atom("ka_stress_ownership_of_storage",
                    "Ownership of storage identifies the component that controls persisted storage."),
            atom("ka_stress_ownership_for_storage",
                    "Ownership rules for storage describe policies intended to govern storage operations."),
            atom("ka_stress_ledger_to_database",
                    "Ledger to database replication writes durable ledger records into a database."),
            atom("ka_stress_database_to_ledger_order",
                    "Database to ledger replication writes database records into a durable ledger."),
            atom("ka_stress_encoder_to_learning",
                    "The encoder feeds normalized representations to the learning component."),
            atom("ka_stress_learning_to_encoder",
                    "The learning component supplies feedback configuration to the encoder."),
            atom("ka_stress_model_1",
                    "Model 1 is the first acoustic model identity and has its own compatibility metadata."),
            atom("ka_stress_model_2",
                    "Model 2 is the second acoustic model identity and has different compatibility metadata."),
            atom("ka_stress_vector_format_v1",
                    "Vector format v1 is the first persisted vector layout."),
            atom("ka_stress_vector_format_v2",
                    "Vector format v2 is the second persisted vector layout."),
            atom("ka_stress_include_speech",
                    "Include speech in core means speech concepts are added to the core module."),
            atom("ka_stress_exclude_speech",
                    "Do not include speech in core means speech concepts remain outside the core module."),
            atom("ka_stress_feedback_enabled",
                    "Ranking with feedback enables historical feedback adjustments."),
            atom("ka_stress_feedback_excluded",
                    "Ranking without feedback excludes all historical feedback adjustments."),
            atom("ka_stress_and_operator",
                    "The and operator requires both conditions to be true."),
            atom("ka_stress_or_operator",
                    "The or operator permits either condition to be true."),
            atom("ka_stress_to_index",
                    "To index describes movement from the API toward the index."),
            atom("ka_stress_in_index",
                    "In index describes data located inside the index."));

    private NormalizedQueryKeyStressDataset() {
    }

    public static EvaluationDataset forCase(NormalizedQueryKeyStressCase stressCase) {
        Objects.requireNonNull(stressCase, "stressCase");
        var atoms = new ArrayList<DatasetAtom>(ProjectMemoryDataset.get().atoms());
        atoms.addAll(STRESS_ATOMS);
        Set<String> labels = atoms.stream()
                .map(DatasetAtom::label)
                .collect(Collectors.toUnmodifiableSet());
        var referenced = new ArrayList<String>();
        referenced.addAll(stressCase.relevantTargetsA());
        referenced.addAll(stressCase.relevantTargetsB());
        referenced.add(stressCase.feedbackTargetLabel());
        referenced.stream()
                .filter(label -> !labels.contains(label))
                .findFirst()
                .ifPresent(label -> {
                    throw new IllegalArgumentException(
                            "stress case " + stressCase.id() + " references unknown target label: " + label);
                });
        return new EvaluationDataset(
                atoms,
                List.of(new EvaluationQuery(stressCase.queryB(), stressCase.relevantTargetsB())));
    }

    private static DatasetAtom atom(String label, String content) {
        return new DatasetAtom(label, content);
    }
}
