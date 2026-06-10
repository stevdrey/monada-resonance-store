---
name: monada-resonance-algorithm-design
description: Use when designing, changing, or reviewing Monada Resonance Store algorithms for frequency encoding, resonance scoring, lexical enrichment, feedback-aware ranking, query-key derivation, or recall improvement.
---

# Monada Resonance Algorithm Design

## Purpose

Guide changes to the core retrieval behavior of Monada Resonance Store while preserving the project direction: associative recall through deterministic, inspectable resonance representations.

Use this skill for:

- `FrequencyEncoder` changes.
- lexical preprocessing, synonym expansion, stop words, aliases, and plural normalization.
- resonance scoring and ranking decorators.
- feedback query-key strategies.
- recall improvements in the default or expanded evaluation datasets.
- algorithmic alternatives such as hypervectors, weighted expansion, re-ranking, or approximate indexes.

## Required context before editing

Read the current `main` branch before making changes:

1. `README.md`, especially product vision, non-goals, architecture, storage layout, evaluation sections, and feedback-aware ranking.
2. `monada-encoder` code and resources.
3. `monada-index` ranking/search code.
4. `monada-learning` feedback code.
5. `monada-api` options/defaults.
6. `monada-evaluation` datasets, reports, and regression tests.

## Design principles

- Measure before optimizing.
- Keep default behavior conservative, deterministic, and auditable.
- Prefer explicit resources over hidden heuristics.
- Keep lexical improvements reviewable in resource files.
- Keep production defaults backward-compatible unless the Issue explicitly requires a breaking change.
- Do not hide ranking behavior inside untestable magic constants.
- Avoid broad synonym expansions that merge unrelated concepts.
- Keep original query text visible in evaluation reports, even when encoded text or feedback query keys are transformed.

## Safe algorithm-change workflow

1. Identify the exact retrieval failure.
   - Which query fails?
   - Which expected atom is missing or under-ranked?
   - Which irrelevant atom is over-ranked?
2. Classify the failure.
   - lexical mismatch.
   - alias gap.
   - confusable overlap.
   - feedback scope issue.
   - scoring tie or deterministic ordering issue.
   - encoder limitation with no lexical path.
3. Choose the smallest intervention.
   - Add or adjust aliases for atom-specific meaning.
   - Add lexical resources only when reusable across multiple queries.
   - Add weighting only when unweighted expansion creates measurable regression.
   - Add a ranking decorator only when base score must remain intact.
4. Add diagnostics before or with the change.
   - report encoded query text when relevant.
   - report feedback query key when feedback affects ranking.
   - expose score deltas or ranking adjustments when useful.
5. Validate with A/B output.
   - baseline before change.
   - candidate after change.
   - per-query metrics.
   - aggregate metrics.
   - explicit regression analysis.

## Compatibility boundaries

Do not silently change the encoding semantics for existing persisted stores.

If atom vectors are persisted and the encoding changes, implement one of:

- retain legacy behavior for existing manifests;
- record encoding options in the manifest and reject incompatible vectors;
- bump manifest/vector encoding metadata and rebuild vectors through an explicit migration path.

Never mix old atom vectors with newly encoded queries under incompatible encoding semantics.

## Acceptance checklist

A retrieval algorithm change is acceptable only when:

- unit tests cover the changed behavior.
- evaluation output explains the before/after impact.
- deterministic ordering remains stable for ties.
- original atom content remains unchanged unless the Issue asks for content changes.
- aliases and lexical resources remain explicit and reviewable.
- no external ML service is introduced.
- compatibility with existing manifests and persisted vectors is addressed.

## Anti-patterns

- Optimizing because a technique is popular rather than because the report shows a gap.
- Adding generic synonyms that make feedback generalization unsafe.
- Replacing deterministic ranking with opaque model output.
- Changing `MonadaMemoryOptions.defaults()` without a migration story.
- Improving one query while silently degrading protected baselines.
- Treating the expanded exploratory dataset as a locked benchmark without explicitly deciding thresholds.
