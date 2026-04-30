package com.monada.evaluation.datasets;

import com.monada.evaluation.DatasetAtom;
import com.monada.evaluation.EvaluationDataset;
import com.monada.evaluation.EvaluationQuery;

import java.util.List;
import java.util.Set;

/**
 * Small fixed dataset focused on databases and knowledge systems.
 *
 * <p>This dataset is intentionally tiny so the evaluation harness can run
 * deterministically and quickly. It is a baseline, not a benchmark.
 */
public final class DefaultDatabasesDataset {

    private DefaultDatabasesDataset() {
    }

    public static EvaluationDataset get() {
        List<DatasetAtom> atoms = List.of(
                new DatasetAtom("ka_orientdb",
                        "OrientDB is a multi-model database that combines graph and document models."),
                new DatasetAtom("ka_arangodb",
                        "ArangoDB is a multi-model database with document, graph, key-value, and search capabilities."),
                new DatasetAtom("ka_postgresql",
                        "PostgreSQL is a relational database focused on SQL, transactions, and extensibility."),
                new DatasetAtom("ka_neo4j",
                        "Neo4j is a graph database designed for storing nodes, relationships, and graph traversals."),
                new DatasetAtom("ka_elasticsearch",
                        "Elasticsearch is a distributed search and analytics engine based on inverted indexes."),
                new DatasetAtom("ka_redis",
                        "Redis is an in-memory key-value data store commonly used for caching and fast lookups."),
                new DatasetAtom("ka_vector_db",
                        "A vector database stores high-dimensional embeddings and supports similarity search."),
                new DatasetAtom("ka_hdc",
                        "Hyperdimensional computing represents information using high-dimensional vectors or hypervectors.")
        );

        List<EvaluationQuery> queries = List.of(
                new EvaluationQuery(
                        "database with graph and document model",
                        Set.of("ka_orientdb", "ka_arangodb")),
                new EvaluationQuery(
                        "sql relational transactions database",
                        Set.of("ka_postgresql")),
                new EvaluationQuery(
                        "nodes relationships graph traversal",
                        Set.of("ka_neo4j")),
                new EvaluationQuery(
                        "search engine inverted index analytics",
                        Set.of("ka_elasticsearch")),
                new EvaluationQuery(
                        "cache key value in memory lookup",
                        Set.of("ka_redis")),
                new EvaluationQuery(
                        "embedding similarity search high dimensional vectors",
                        Set.of("ka_vector_db", "ka_hdc"))
        );

        return new EvaluationDataset(atoms, queries);
    }
}
