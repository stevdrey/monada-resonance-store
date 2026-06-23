# Monada Resonance Store Rule

Use this rule for repository-wide work.

## Project Intent

This project is a local memory and retrieval engine for Monada Neuron.

## Do

- Read `AGENTS.md` before editing.
- Preserve module boundaries.
- Keep default behavior conservative.
- Make experiments opt-in.
- Document storage and ranking changes.
- Include evaluation output for retrieval changes.

## Avoid

- External services in the default path.
- Replacing the scan baseline without an option.
- Mixing incompatible persisted vectors.
- Hiding ranking changes inside storage code.
