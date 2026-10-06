---
name: monada-executive-summary
description: Use when generating plain-language executive summaries for completed Monada Resonance Store issues and milestones.
---

# Monada Executive Summary

Use this template to generate a high-level, business- and stakeholder-friendly executive summary when closing or commenting on a GitHub Issue.

## Executive Summary Template

```markdown
## 🌟 Executive Summary: <Title / Milestone / Issue #N>
### 🎯 What is this issue about?
<2-3 sentences explaining the high-level context of Monada Neuron and what problem this issue resolves in plain language.>
---
### 💡 Why is this important?
<Explain the tangible value: why stakeholders should care, how it improves reliability, performance, or readiness for future capabilities.>
---
### 🧩 What was delivered?
- 🧪 **<Key Capability 1>**: <Plain-language description of delivered capability.>
- 🌊 **<Key Capability 2>**: <Plain-language description of behavior or benchmark.>
- 🔄 **<Key Capability 3>**: <Plain-language description of lifecycle or workflow.>
- 📊 **<Key Capability 4>**: <Plain-language description of reporting/diagnostics.>
---
### ✅ Key Takeaways & Business Value
- **<Value 1>**: <Concrete positive outcome, e.g., zero guesswork, empirical validation.>
- **<Value 2>**: <Quality assurance outcome, e.g., rock-solid test stability, zero memory leaks.>
- **<Value 3>**: <Next steps enabled by this work, e.g., readiness for hardware acceleration.>
```

## Checklist Before Posting

- [ ] Is the summary written entirely in English?
- [ ] Is the tone professional, accessible, and free of unnecessary code jargon?
- [ ] Are key concepts explained with intuitive metaphors or plain business value?
- [ ] Did you verify the destination? Use a **GitHub Issue** (`gh issue comment <issue-number>`) when one exists; for a milestone without an Issue, use the milestone description (`gh api`) or a document under `docs/`.
