package com.monada.evaluation.datasets;

import com.monada.evaluation.DatasetAtom;
import com.monada.evaluation.EvaluationDataset;
import com.monada.evaluation.EvaluationQuery;

import java.util.List;
import java.util.Set;

/**
 * Real-world text recall corpus built from the project's own documentation.
 *
 * <p>This dataset treats the Monada Resonance Store repository as a memory: README,
 * architecture, roadmap, ADRs, specs, and glossary become atoms, and the queries
 * simulate questions an implementation agent would ask while working on the project.
 *
 * <p>Concept groups:
 * <ul>
 *   <li>Project identity and design principles (vision, README)</li>
 *   <li>Module ownership (architecture, README)</li>
 *   <li>Runtime flows (text recall, feedback-aware ranking)</li>
 *   <li>Storage and speech architecture</li>
 *   <li>Agent workflow and compatibility boundaries</li>
 *   <li>Roadmap phases and evaluation commands</li>
 * </ul>
 *
 * <p>This dataset is exploratory and is not protected as a strict regression baseline.
 */
public final class ProjectMemoryDataset {

    private ProjectMemoryDataset() {
    }

    public static EvaluationDataset get() {
        var atoms = List.of(
                // Project identity and principles
                new DatasetAtom("ka_project_purpose",
                        "Monada Resonance Store is the local embedded memory layer for Monada Neuron. " +
                                "It stores project knowledge as KnowledgeAtom entries, converts content into " +
                                "deterministic FrequencyVector representations, and retrieves related entries " +
                                "through resonance-style similarity search.",
                        List.of("memory layer for Monada Neuron", "associative memory engine",
                                "embedded resonance memory")),
                new DatasetAtom("ka_project_non_goals",
                        "Monada Resonance Store is explicitly not a relational database, document database, " +
                                "graph database, vector database replacement, full reasoning engine, distributed " +
                                "database, cloud service, or generic wrapper around external embeddings.",
                        List.of("not a database clone", "not a vector database replacement",
                                "not a cloud service")),
                new DatasetAtom("ka_project_value_proposition",
                        "The system remembers independent entries, encodes text or acoustic signals into " +
                                "comparable vectors, retrieves approximate matches by resonance, ranks results with " +
                                "deterministic scoring, adjusts future ranking through explicit feedback, and " +
                                "measures recall quality before optimizing."),
                new DatasetAtom("ka_design_principles",
                        "Measure before optimizing. Keep storage inspectable. Keep defaults conservative. " +
                                "Keep experiments opt-in. Preserve deterministic behavior. Keep compatibility metadata " +
                                "explicit. Prefer targeted diagnostics over hidden heuristics.",
                        List.of("measure before optimizing", "keep defaults conservative",
                                "preserve deterministic behavior")),

                // Module ownership
                new DatasetAtom("ka_module_core",
                        "monada-core owns domain primitives such as KnowledgeAtom, FrequencyVector, MonadaRecall, " +
                                "ResonanceResult, atom types, and feedback signals."),
                new DatasetAtom("ka_module_encoder",
                        "monada-encoder owns deterministic text normalization, lexical resources, aliases, " +
                                "stop words, plural handling, technical synonyms, and frequency encoding.",
                        List.of("text normalization module", "lexical enrichment module",
                                "deterministic encoder module")),
                new DatasetAtom("ka_module_storage",
                        "monada-storage owns local persistence: manifests, append-only atom logs, vector files, " +
                                "indexes, and feedback logs.",
                        List.of("persistence module", "manifest and log storage")),
                new DatasetAtom("ka_module_index",
                        "monada-index owns similarity search, deterministic ranking, and index implementations " +
                                "such as linear scan resonance.",
                        List.of("resonance index module", "similarity search module")),
                new DatasetAtom("ka_module_learning",
                        "monada-learning owns feedback aggregation, query-key strategies, and ranking " +
                                "reinforcement support.",
                        List.of("feedback aggregation module", "query key strategies")),
                new DatasetAtom("ka_module_api",
                        "monada-api owns the developer-facing API and options such as MonadaMemory.",
                        List.of("developer API module", "MonadaMemory module")),
                new DatasetAtom("ka_module_evaluation",
                        "monada-evaluation owns datasets, metrics, reports, A/B comparison, diagnostics, and " +
                                "regression tests.",
                        List.of("evaluation harness module", "metrics and reports module")),
                new DatasetAtom("ka_module_speech",
                        "monada-speech owns speech samples, TORGO-style import, acoustic feature encoding, " +
                                "and speech retrieval experiments.",
                        List.of("speech module", "acoustic feature module")),

                // Runtime flows and architecture
                new DatasetAtom("ka_text_recall_flow",
                        "The text recall flow is: input text -> lexical preprocessing -> FrequencyEncoder -> " +
                                "FrequencyVector -> persisted vector -> ResonanceIndex -> ranked recall results.",
                        List.of("text recall pipeline", "encoding to ranked recall")),
                new DatasetAtom("ka_feedback_aware_ranking_flow",
                        "The feedback-aware ranking flow is: query -> base resonance ranking -> derive feedback " +
                                "query key -> aggregate matching feedback events -> adjusted score -> deterministic " +
                                "ordering.",
                        List.of("feedback ranking pipeline", "feedback adjusted scores")),
                new DatasetAtom("ka_storage_layout",
                        "The monada-memory storage layout contains manifest.json, atoms/segment-000001.log, " +
                                "vectors/segment-000001.f32, indexes/atom-offsets.idx and vector-map.idx, and " +
                                "feedback/feedback-000001.log.",
                        List.of("monada memory layout", "storage directory structure")),
                new DatasetAtom("ka_speech_extension",
                        "Speech is a separate extension layer with SpeechSample metadata, optional transcript " +
                                "KnowledgeAtom, BasicAcousticFeatureEncoder, speech feature vector, and " +
                                "SpeechSampleRetriever. It uses a separate .monada-speech layout and must not " +
                                "contaminate the core text retrieval path.",
                        List.of("speech retrieval architecture", "acoustic recall extension")),
                new DatasetAtom("ka_compatibility_boundary",
                        "Any change that affects persisted vectors must answer: which encoder created the vector, " +
                                "are stored vectors comparable with new queries, does the manifest identify the format " +
                                "and version, and should the store be reused, rebuilt, or rejected.",
                        List.of("storage compatibility", "vector format compatibility")),

                // Agent workflow and governance
                new DatasetAtom("ka_agent_spec_context",
                        "New issues should use the Spec Context format with background, current state, goal, " +
                                "non-goals, affected modules, implementation boundaries, acceptance criteria, " +
                                "verification commands, and expected documentation updates.",
                        List.of("issue template", "spec context requirements")),
                new DatasetAtom("ka_pr_review_checklist",
                        "PR reviews should verify alignment with the issue goal, scope control, module " +
                                "boundaries, tests, evaluation output, compatibility, determinism, documentation, and " +
                                "merge safety.",
                        List.of("pull request review checklist", "review requirements")),
                new DatasetAtom("ka_adr_memory_ownership",
                        "ADR 0002 states that memory is owned by Resonance Store, not by external callers, so " +
                                "the storage layer controls atom identity, persistence, and lifecycle.",
                        List.of("memory ownership decision", "ADR 0002")),

                // Roadmap and commands
                new DatasetAtom("ka_roadmap_phase_p",
                        "Phase P is the near-term direction for speech-specific evaluation metrics, including " +
                                "speech retrieval fixtures, top-K acoustic recall metrics, deterministic reports, " +
                                "metadata-filter diagnostics, and regression tests.",
                        List.of("speech evaluation phase", "Phase P")),
                new DatasetAtom("ka_roadmap_phase_q",
                        "Phase Q introduces a real-world text recall corpus and query set using project-like " +
                                "knowledge instead of only small technology concept fixtures.",
                        List.of("real world corpus phase", "Phase Q")),
                new DatasetAtom("ka_evaluation_commands",
                        "Useful evaluation commands include ./gradlew test, ./gradlew :monada-evaluation:test, " +
                                "./gradlew :monada-evaluation:run -q, ./gradlew :monada-evaluation:runExpanded -q, " +
                                "./gradlew :monada-evaluation:runProfileComparison -q, " +
                                "./gradlew :monada-evaluation:runLatency -q, and " +
                                "./gradlew :monada-evaluation:runProjectMemory -q.",
                        List.of("evaluation command list", "latency profiling command",
                                "project memory evaluation command"))
        );

        var queries = List.of(
                // Direct
                new EvaluationQuery(
                        "which module owns deterministic text encoding and lexical enrichment",
                        Set.of("ka_module_encoder")),
                new EvaluationQuery(
                        "what is the local storage layout for monada memory",
                        Set.of("ka_storage_layout")),
                new EvaluationQuery(
                        "commands to run evaluation and profile comparison",
                        Set.of("ka_evaluation_commands")),

                // Multi-relevant
                new EvaluationQuery(
                        "modules involved in the text recall pipeline from query to ranked results",
                        Set.of("ka_text_recall_flow", "ka_module_encoder", "ka_module_index", "ka_module_api")),
                new EvaluationQuery(
                        "which modules handle project knowledge primitives and the developer API",
                        Set.of("ka_module_core", "ka_module_api")),

                // Paraphrase
                new EvaluationQuery(
                        "how does resonance ranking incorporate historical user signals",
                        Set.of("ka_feedback_aware_ranking_flow")),
                new EvaluationQuery(
                        "where are speech samples and acoustic features kept separate from text memory",
                        Set.of("ka_speech_extension")),
                new EvaluationQuery(
                        "what architectural decision says memory is owned by Resonance Store",
                        Set.of("ka_adr_memory_ownership")),

                // Confusable
                new EvaluationQuery(
                        "difference between monada-index and monada-learning responsibilities",
                        Set.of("ka_module_index", "ka_module_learning")),
                new EvaluationQuery(
                        "which system is an embedded memory subsystem rather than a production database replacement",
                        Set.of("ka_project_purpose", "ka_project_non_goals")),
                new EvaluationQuery(
                        "which module evaluates retrieval quality with A/B comparison and regression tests",
                        Set.of("ka_module_evaluation")),

                // Follow-up / roadmap
                new EvaluationQuery(
                        "what phase added speech-specific evaluation metrics",
                        Set.of("ka_roadmap_phase_p")),
                new EvaluationQuery(
                        "what phase introduced a real-world project memory corpus",
                        Set.of("ka_roadmap_phase_q")),

                // Non-goals and workflow
                new EvaluationQuery(
                        "what is Monada Resonance Store explicitly not designed to replace",
                        Set.of("ka_project_non_goals")),
                new EvaluationQuery(
                        "what information must a new issue include before implementation",
                        Set.of("ka_agent_spec_context")),
                new EvaluationQuery(
                        "what are the design principles for experiments and defaults",
                        Set.of("ka_design_principles"))
        );

        return new EvaluationDataset(atoms, queries);
    }
}
