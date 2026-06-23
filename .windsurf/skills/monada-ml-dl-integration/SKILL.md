---
name: monada-ml-dl-integration
description: Use when planning embeddings, ONNX, model-based encoders, learned ranking, or vector quantization.
---

# Monada ML and DL Integration

## Context

Read `docs/vision.md`, `docs/architecture.md`, current encoder, manifest behavior, index baseline, evaluation datasets, and API options.

## Principles

- Keep model behavior opt-in until proven.
- Preserve the deterministic encoder as baseline.
- Do not require a remote service for MVP operation.
- Treat model identity as vector compatibility metadata.
- Compare quality against the deterministic baseline.

## Required Metadata

Record encoder type, model name, version or digest, provider, dimensions, normalization, quantization, and preprocessing.

## Acceptance

Offline tests do not require network calls, compatibility metadata is handled, A/B output is included, and failure behavior is clear.
