# Glossary

## Core Terms

### Monada Resonance Store

The local embedded memory and retrieval engine that stores content, encodes it into vectors, and retrieves related results by resonance-style similarity.

### Monada Neuron

The broader intelligent modular system that consumes Monada Resonance Store as its memory layer.

### KnowledgeAtom

The main domain record for remembered content. It contains an id, type, content, aliases, weight, and creation metadata.

### FrequencyVector

A fixed-size numeric representation used for similarity search. Text and acoustic inputs can both be projected into this shape.

### Resonance

The project term for approximate retrieval by similarity between a query vector and stored vectors.

### Recall Result

A ranked item returned from a resonance query, including score and associated content.

## Encoding Terms

### FrequencyEncoder

A component that converts content into a `FrequencyVector`.

### Lexical Enrichment

Deterministic preprocessing that can include stop-word handling, plural normalization, synonym expansion, and aliases.

### Alias

Additional searchable text attached to a domain record without changing the original primary content.

### Encoded Text

The text representation actually sent to the encoder after normalization and enrichment.

## Ranking Terms

### Base Score

The raw similarity score before feedback adjustments.

### Adjusted Score

The score after feedback deltas are applied.

### Query Key

A derived key used to decide which feedback events apply to a query.

### Feedback-Aware Ranking

A ranking layer that applies explicit positive or negative feedback events to base resonance results.

## Evaluation Terms

### Precision@K

How many of the top K results are relevant.

### Recall@K

How many expected relevant results appear within the top K.

### Hit@K

Whether at least one expected result appears within the top K.

### Mean Reciprocal Rank

How early the first expected result appears.

### Protected Baseline

A regression baseline whose expected metrics should not change without explicit rationale.

### Exploratory Dataset

A harder dataset used to expose gaps and guide future Issues, not necessarily to lock every metric.

## Speech Terms

### SpeechSample

A speech-specific metadata record containing dataset source, speaker, path, transcript, condition, task type, language, and audio metadata.

### Acoustic Feature Vector

A `FrequencyVector` generated from audio characteristics rather than text.

### TORGO-style Import

A conservative importer path for local directory layouts following TORGO-like speaker/session/task/audio conventions.

### Acoustic Retrieval

Similarity search over speech feature vectors.
