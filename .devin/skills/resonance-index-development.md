# Skill: Resonance Index Development

Use this skill when implementing or modifying resonance search/index logic.

## Goal

Retrieve the most relevant `KnowledgeAtom` entries by comparing a query `FrequencyVector` against stored atom vectors.

For MVP, use linear scan.

## Core Flow

```text
query content
  -> FrequencyEncoder
  -> query FrequencyVector
  -> ResonanceIndex.search(queryVector, topK, threshold)
  -> scored atom candidates
  -> MonadaRecall
```

## MVP Scoring

Prefer cosine similarity for dense vectors:

```text
score = dot(query, atom) / (norm(query) * norm(atom))
```

If vectors are already normalized:

```text
score = dot(query, atom)
```

## Rules

- Keep scoring deterministic.
- Keep ranking stable for equal scores when possible.
- Validate vector dimensions before comparing.
- Do not mutate stored vectors during search.
- Do not read/write atom logs directly from the index layer unless explicitly designed through an abstraction.

## Linear Scan Implementation

Linear scan is acceptable for v0.1:

```text
for each stored vector:
    score = similarity(query, vector)
    keep if score >= threshold
    maintain topK
```

## Avoid for MVP

- HNSW;
- LSH;
- external vector databases;
- GPU acceleration;
- distributed indexes;
- background indexing;
- complex caching.

## Testing Checklist

Add tests for:

- identical vectors produce high score;
- unrelated vectors produce lower score;
- topK returns correct count;
- threshold filters weak results;
- dimension mismatch fails clearly;
- repeated searches produce same ranking.

## Future Direction

After v0.1 is stable, introduce approximate nearest-neighbor indexing behind the same `ResonanceIndex` abstraction.