# ADR 0003: Agent Workflow

## Status

Accepted

## Context

The project is frequently changed by coding agents. Agents need durable context, task boundaries, and repeatable review procedures to avoid scope creep.

## Decision

Use repository-owned context files as the agent onboarding layer:

- `AGENTS.md` for root instructions;
- `docs/` for durable product and architecture context;
- `docs/specs/spec-context-template.md` for Issue creation;
- `docs/specs/pr-review-checklist.md` for PR validation;
- `.agents/skills/` for Devin-compatible skills;
- `.windsurf/rules/` for local IDE/Cascade-compatible rules.

## Consequences

- Agents have a predictable entry path.
- Skills are discoverable by Devin in the supported location.
- Issues can provide implementation context and explicit non-goals.
- PR reviews can focus on alignment, safety, tests, and merge readiness.

## Review Checklist

A task follows this ADR when:

- it starts from current `main` context;
- the Issue uses Spec Context;
- the PR explains verification commands;
- docs or rules are updated when the workflow changes.
