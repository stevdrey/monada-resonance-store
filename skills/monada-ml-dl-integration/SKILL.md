---
name: monada-ml-dl-integration
description: Use when planning or implementing embeddings, ONNX, external model providers, learned ranking, vector quantization, ANN, or other machine learning/deep learning integrations for Monada Resonance Store.
---

# Monada Machine Learning and Deep Learning Integration

## Purpose

Guide ML/DL work without prematurely turning Monada Resonance Store into a generic vector database or opaque AI service wrapper.

Use this skill for:

- embedding-based encoders.
- ONNX Runtime experiments.
- external embedding providers.
- learned re-rankers.
- vector quantization.
- ANN indexes related to embedding vectors.
- ML evaluation methodology.
- migration from deterministic encoders to model-based encoders.

## Required context before editing

Inspect:

1. `README.md` product vision, non-goals, future additions, and evaluation sections.
2. current deterministic encoder in `monada-encoder`.
3. current storage manifest and vector format.
4. `monada-index` linear scan baseline.
5. `monada-evaluation` baseline and expanded datasets.
6. `monada-api` options/defaults.

## Integration principles

- Keep model-based behavior opt-in until proven.
- Preserve deterministic simple encoder as baseline.
- Never require an external service for MVP operation.
- Treat embedding model identity as part of vector compatibility.
- Record model/provider/version/dimensions in metadata.
- Compare quality against deterministic baseline.
- Keep failure modes explicit when a model is unavailable.

## Encoder compatibility requirements

For any model-based encoder, record enough metadata to prevent incompatible comparisons:

- encoder type.
- model name.
- model version or digest.
- provider name.
- vector dimensions.
- vector normalization behavior.
- quantization method if any.
- lexical preprocessing applied before model input, if any.

If any of these changes, existing vectors may be incompatible. Provide one of:

- explicit rebuild.
- clear rejection.
- side-by-side vector namespace.
- compatibility adapter with documented limits.

## ML experiment workflow

1. Define the hypothesis.
   - Example: embeddings should improve synonym/paraphrase recall without harming direct database queries.
2. Freeze a baseline.
   - run default baseline.
   - run expanded dataset.
   - save per-query top-K.
3. Implement behind opt-in options.
   - do not change `MonadaMemoryOptions.defaults()`.
4. Run A/B evaluation.
   - compare Precision@K, Recall@K, Hit@K, MRR.
   - inspect confusable queries.
5. Analyze operational trade-offs.
   - latency.
   - memory.
   - disk size.
   - model availability.
   - reproducibility.
6. Decide whether this remains experimental or becomes a candidate default.

## External provider boundary

If using an external embedding provider:

- do not put provider calls in tests that must run offline.
- abstract the encoder interface.
- provide deterministic test doubles.
- document API key/config requirements outside production defaults.
- avoid storing secrets.
- handle rate limits, timeouts, and failures explicitly.

## ONNX/local model boundary

If using ONNX Runtime or local models:

- keep the dependency isolated.
- avoid loading models during simple encoder tests.
- document model file location and licensing expectations.
- validate dimensions at load time.
- make model loading errors actionable.

## Learned ranking boundary

If adding a learned re-ranker:

- keep base score visible.
- keep adjusted score visible.
- include diagnostics explaining rank changes.
- avoid training on the evaluation set without documenting leakage.
- keep feedback-aware ranking deterministic unless explicitly experimenting.

## Acceptance checklist

An ML/DL integration is acceptable only when:

- it is opt-in.
- deterministic baseline remains available.
- vector compatibility metadata is handled.
- offline tests do not require network calls.
- A/B evaluation includes per-query analysis.
- storage impact is documented.
- model/provider failure behavior is tested or clearly handled.

## Anti-patterns

- Replacing the deterministic encoder as default without migration.
- Calling embeddings an improvement without A/B evidence.
- Depending on a remote model in core tests.
- Storing vectors without model identity.
- Hiding model-driven rank changes from evaluation reports.
- Turning the project into a generic vector DB clone.
