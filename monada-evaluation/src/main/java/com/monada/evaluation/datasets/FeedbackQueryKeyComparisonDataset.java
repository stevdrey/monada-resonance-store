package com.monada.evaluation.datasets;

import com.monada.evaluation.DatasetAtom;
import com.monada.evaluation.EvaluationDataset;
import com.monada.evaluation.EvaluationQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Focused exploratory corpus for measuring feedback query-key transfer and contamination.
 *
 * <p>The base atoms remain the expanded technology corpus. Three extra atoms make the negative
 * cases semantically explicit without changing any protected dataset or production resource.
 */
public final class FeedbackQueryKeyComparisonDataset {

    private FeedbackQueryKeyComparisonDataset() {
    }

    public static EvaluationDataset get() {
        var atoms = new ArrayList<DatasetAtom>(ExpandedTechnologyDataset.get().atoms());
        atoms.add(new DatasetAtom(
                "ka_query_planning",
                "A query planner chooses an execution plan and may use temporary lookup tables "
                        + "while optimizing a database query."));
        atoms.add(new DatasetAtom(
                "ka_database_to_ledger_replication",
                "Database-to-ledger replication copies database records into a durable ledger."));
        atoms.add(new DatasetAtom(
                "ka_ledger_to_database_replication",
                "Ledger-to-database replication writes durable ledger records back into a database."));

        var queries = List.of(
                new EvaluationQuery(
                        "recording state as immutable sequence of changes",
                        Set.of("ka_event_sourcing", "ka_append_only_log")),
                new EvaluationQuery(
                        "embedded lightweight storage engine",
                        Set.of("ka_rocksdb", "ka_sqlite")),
                new EvaluationQuery(
                        "improving search results order using a second pass model",
                        Set.of("ka_ranking", "ka_reranking")),
                new EvaluationQuery(
                        "similarity search metric measuring directional alignment between vectors",
                        Set.of("ka_cosine_similarity")),
                new EvaluationQuery(
                        "temporary lookup table for query planning",
                        Set.of("ka_query_planning")),
                new EvaluationQuery(
                        "ledger replicates database",
                        Set.of("ka_ledger_to_database_replication")));
        return new EvaluationDataset(atoms, queries);
    }
}
