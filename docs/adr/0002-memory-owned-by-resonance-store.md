# ADR 0002: Memory Owned by Resonance Store

## Status

Accepted

## Context

Monada Neuron needs memory, but memory persistence and retrieval have enough complexity to deserve a dedicated subsystem.

## Decision

Monada Resonance Store owns memory storage and retrieval responsibilities. Monada Neuron should consume this project through stable APIs instead of duplicating persistence, encoding, ranking, feedback, or evaluation logic.

## Ownership

Monada Resonance Store owns:

- memory persistence;
- vector encoding contracts;
- resonance search;
- feedback-aware ranking;
- retrieval evaluation;
- speech storage and acoustic retrieval foundations.

Monada Neuron owns:

- higher-level agent orchestration;
- reasoning workflows;
- tool routing;
- task planning;
- integration between memory and other cognitive modules.

## Consequences

- Memory improvements remain measurable inside this repository.
- Monada Neuron can depend on a cleaner memory API.
- Agent tasks should avoid moving memory concerns into other repositories unless a cross-repository Spec Context explicitly requires it.

## Review Checklist

A change respects this ADR when memory behavior remains implemented, tested, and documented inside Monada Resonance Store.
