---
name: monada-evaluation-statistics
description: Use when adding or interpreting Monada evaluation datasets, A/B reports, retrieval metrics, query diagnostics, regression thresholds, statistical summaries, or quality gates.
---

# Monada Evaluation and Statistics

## Purpose

Guide evaluation work so every retrieval improvement is measurable, reproducible, and diagnosable.

Use this skill for:

- new evaluation datasets.
- A/B evaluation reports.
- Precision@K, Recall@K, Hit@K, MRR, and average score changes.
- query-key diagnostics.
- regression baselines.
- per-query failure analysis.
- statistical summaries.
- deciding whether a retrieval change is safe to merge.

## Required context before editing

Inspect:

1. `README.md` metrics and evaluation sections.
2. `monada-evaluation` datasets and runners.
3. protected baseline regression tests.
4. expanded exploratory dataset tests.
5. recent A/B report output from the Issue or PR.
6. ranking, encoder, and feedback code touched by the evaluation.

## Evaluation principles

- Measure before optimizing.
- Separate protected regression baselines from exploratory stress tests.
- Report per-query results, not only aggregate averages.
- Preserve original query text in reports.
- Show derived encoded query text or feedback query key when it explains behavior.
- Treat small datasets carefully: one query can dominate averages.
- Do not hide regressions behind aggregate improvements.

## Required A/B report shape

For changes that affect retrieval quality, include:

```text
Baseline:
- command used
- commit or branch
- dataset
- aggregate metrics
- per-query top-K output

Candidate:
- command used
- commit or branch
- dataset
- aggregate metrics
- per-query top-K output

Analysis:
- improved queries
- regressed queries
- unchanged queries
- expected trade-offs
- decision: safe / needs adjustment / not enough evidence
```

## Metric interpretation

### Precision@K

Use to measure how clean the top-K result set is.

Watch for:

- precision drops caused by broad lexical expansion.
- high precision with low recall when only one expected item appears.

### Recall@K

Use to measure whether all relevant atoms are reachable within K.

Watch for:

- multi-relevant queries where one correct atom dominates and others disappear.
- recall improvements that introduce confusable false positives.

### Hit@K

Use to measure whether at least one expected atom appears.

Watch for:

- Hit@1 can look good while Recall@5 remains poor.
- Hit@K is useful but insufficient for multi-relevant recall.

### Mean Reciprocal Rank

Use to measure how early the first relevant result appears.

Watch for:

- MRR can remain high while secondary relevant atoms regress.

### Average score

Use carefully. It is not a quality metric by itself.

Watch for:

- score inflation from feedback or lexical expansion.
- score compression that changes ranking ties.

## Query diagnostics guidance

Add diagnostics when a developer cannot explain why feedback or ranking changed.

Useful fields:

- original query.
- normalized query.
- encoded query text.
- feedback query key.
- top-K atom ids.
- base score.
- feedback delta.
- adjusted score.
- matched aliases or lexical expansions when available.

Diagnostics should be deterministic and should not alter ranking behavior.

## Regression gates

For protected baselines:

- exact expected values are acceptable when the dataset is intentionally stable.
- update locked values only when the Issue explicitly accepts the new behavior.
- document why a regression is acceptable if any protected metric drops.

For exploratory datasets:

- avoid locking every metric too early.
- prefer trend analysis and targeted assertions for known failure cases.
- use exploratory failures to define the next Issue.

## Acceptance checklist

An evaluation change is acceptable only when:

- commands are documented.
- reports are reproducible.
- per-query output exists for changed behavior.
- aggregate metrics do not hide regressions.
- diagnostics explain feedback/query-key behavior when relevant.
- protected baseline changes are justified.
- tests distinguish regression baseline from exploratory measurement.

## Anti-patterns

- Reporting only aggregate averages.
- Calling a change successful because one query improved.
- Locking exploratory metrics prematurely.
- Changing datasets and algorithms in the same PR without explaining the impact.
- Removing hard queries because they make metrics look bad.
