package com.monada.evaluation.datasets;

import com.monada.evaluation.DatasetAtom;
import com.monada.evaluation.EvaluationDataset;
import com.monada.evaluation.EvaluationQuery;

import java.util.List;
import java.util.Set;

/**
 * Larger and more challenging evaluation dataset covering multiple technology
 * and knowledge-system concepts.
 *
 * <p>This dataset is exploratory and intentionally harder than
 * {@link DefaultDatabasesDataset}. Its metric values are <b>not</b> protected
 * as a strict regression baseline; lower scores are expected and useful because
 * they expose encoder and ranking limitations.
 *
 * <p>Concept groups: databases/storage, search/retrieval, AI memory and
 * representation, distributed systems/architecture.
 */
public final class ExpandedTechnologyDataset {

    private ExpandedTechnologyDataset() {
    }

    public static EvaluationDataset get() {
        var atoms = List.of(
                // --- Databases and storage ---
                new DatasetAtom("ka_postgresql",
                        "PostgreSQL is a relational database focused on SQL, transactions, and extensibility."),
                new DatasetAtom("ka_mysql",
                        "MySQL is an open-source relational database widely used for web applications and OLTP workloads."),
                new DatasetAtom("ka_oracle",
                        "Oracle Database is an enterprise relational database with advanced SQL, partitioning, and high availability."),
                new DatasetAtom("ka_redis",
                        "Redis is an in-memory key-value data store commonly used for caching and fast lookups."),
                new DatasetAtom("ka_elasticsearch",
                        "Elasticsearch is a distributed search and analytics engine based on inverted indexes."),
                new DatasetAtom("ka_neo4j",
                        "Neo4j is a graph database designed for storing nodes, relationships, and graph traversals."),
                new DatasetAtom("ka_arangodb",
                        "ArangoDB is a multi-model database with document, graph, key-value, and search capabilities."),
                new DatasetAtom("ka_orientdb",
                        "OrientDB is a multi-model database that combines graph and document models."),
                new DatasetAtom("ka_vector_database",
                        "A vector database stores high-dimensional embeddings and supports similarity search over dense vectors."),
                new DatasetAtom("ka_rocksdb",
                        "RocksDB is an embeddable persistent key-value store optimized for fast storage on SSDs."),
                new DatasetAtom("ka_sqlite",
                        "SQLite is a lightweight embedded relational database stored in a single file."),

                // --- Search and retrieval ---
                new DatasetAtom("ka_inverted_index",
                        "An inverted index maps terms to the documents that contain them, enabling fast full-text search."),
                new DatasetAtom("ka_cosine_similarity",
                        "Cosine similarity measures the cosine of the angle between two vectors, indicating their directional alignment."),
                new DatasetAtom("ka_embeddings",
                        "Embeddings are dense vector representations of text or data learned by neural networks for semantic tasks."),
                new DatasetAtom("ka_ann",
                        "Approximate nearest-neighbor search finds the closest vectors without exhaustive comparison, trading accuracy for speed."),
                new DatasetAtom("ka_hnsw",
                        "HNSW is a hierarchical navigable small-world graph algorithm for efficient approximate nearest-neighbor search."),
                new DatasetAtom("ka_lsh",
                        "Locality-sensitive hashing maps similar items to the same hash buckets, enabling sublinear approximate search."),
                new DatasetAtom("ka_ranking",
                        "Ranking orders search results by relevance score so the most useful items appear first."),
                new DatasetAtom("ka_reranking",
                        "Re-ranking refines an initial retrieval list using a more expensive model to improve precision at the top."),

                // --- AI memory and representation ---
                new DatasetAtom("ka_knowledge_atoms",
                        "Knowledge atoms are small independent units of stored knowledge used for modular memory retrieval."),
                new DatasetAtom("ka_hyperdimensional_computing",
                        "Hyperdimensional computing represents information using high-dimensional vectors called hypervectors."),
                new DatasetAtom("ka_hypervectors",
                        "Hypervectors are very high-dimensional distributed representations that support algebraic composition."),
                new DatasetAtom("ka_associative_memory",
                        "Associative memory retrieves stored patterns by similarity to a given cue, even with partial or noisy input."),
                new DatasetAtom("ka_resonance_recall",
                        "Resonance recall activates stored knowledge whose frequency representation is close to the query vector."),
                new DatasetAtom("ka_feedback_aware_ranking",
                        "Feedback-aware ranking adjusts retrieval scores based on accumulated user signals to improve future recall."),
                new DatasetAtom("ka_query_normalization",
                        "Query normalization transforms user queries into a canonical form to improve matching consistency."),

                // --- Distributed systems / architecture ---
                new DatasetAtom("ka_event_sourcing",
                        "Event sourcing persists state changes as an immutable sequence of events rather than mutable records."),
                new DatasetAtom("ka_append_only_log",
                        "An append-only log is a data structure where new entries are always written at the end, preserving history."),
                new DatasetAtom("ka_cqrs",
                        "CQRS separates the read model from the write model so each can be optimized independently."),
                new DatasetAtom("ka_consensus",
                        "Consensus algorithms allow distributed nodes to agree on a single value even in the presence of failures."),
                new DatasetAtom("ka_replication",
                        "Replication copies data across multiple nodes to increase availability and fault tolerance."),
                new DatasetAtom("ka_sharding",
                        "Sharding partitions data across multiple nodes so each node stores only a subset of the total dataset."),
                new DatasetAtom("ka_caching",
                        "Caching stores frequently accessed data in a fast layer to reduce latency and backend load.")
        );

        var queries = List.of(
                // --- Direct queries ---
                new EvaluationQuery(
                        "relational database with sql transactions",
                        Set.of("ka_postgresql", "ka_mysql", "ka_oracle")),
                new EvaluationQuery(
                        "graph database for nodes and relationships",
                        Set.of("ka_neo4j")),
                new EvaluationQuery(
                        "distributed search engine with inverted indexes",
                        Set.of("ka_elasticsearch", "ka_inverted_index")),
                new EvaluationQuery(
                        "embedded lightweight storage engine",
                        Set.of("ka_rocksdb", "ka_sqlite")),

                // --- Multi-relevant queries ---
                new EvaluationQuery(
                        "graph and document database model",
                        Set.of("ka_arangodb", "ka_orientdb")),
                new EvaluationQuery(
                        "approximate nearest-neighbor search algorithms",
                        Set.of("ka_ann", "ka_hnsw", "ka_lsh")),
                new EvaluationQuery(
                        "high-dimensional vector representations for semantic tasks",
                        Set.of("ka_embeddings", "ka_hypervectors", "ka_hyperdimensional_computing")),
                new EvaluationQuery(
                        "recording state as immutable sequence of changes",
                        Set.of("ka_event_sourcing", "ka_append_only_log")),

                // --- Synonym / paraphrase queries ---
                new EvaluationQuery(
                        "fast memory cache for temporary lookups",
                        Set.of("ka_redis", "ka_caching")),
                new EvaluationQuery(
                        "splitting data across multiple machines",
                        Set.of("ka_sharding", "ka_replication")),
                new EvaluationQuery(
                        "improving search results order using a second pass model",
                        Set.of("ka_reranking", "ka_ranking")),
                new EvaluationQuery(
                        "small independent knowledge units for modular retrieval",
                        Set.of("ka_knowledge_atoms")),

                // --- Confusable queries ---
                new EvaluationQuery(
                        "similarity search over high dimensional representations",
                        Set.of("ka_vector_database", "ka_embeddings", "ka_ann")),
                new EvaluationQuery(
                        "measuring directional alignment between vectors",
                        Set.of("ka_cosine_similarity")),
                new EvaluationQuery(
                        "distributed agreement despite node failures",
                        Set.of("ka_consensus", "ka_replication")),
                new EvaluationQuery(
                        "separate read and write paths for optimization",
                        Set.of("ka_cqrs")),

                // --- Feedback-sensitive queries ---
                new EvaluationQuery(
                        "memory recall improved by user signals",
                        Set.of("ka_feedback_aware_ranking", "ka_associative_memory")),
                new EvaluationQuery(
                        "resonance activation retrieving stored knowledge by frequency closeness",
                        Set.of("ka_resonance_recall", "ka_associative_memory"))
        );

        return new EvaluationDataset(atoms, queries);
    }
}
