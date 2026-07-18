---
name: deep-research
description: Plan and coordinate bounded, source-traceable research with restricted read-only sub-agents.
auto_load: false
---

# Deep Research

Use this skill only when the user explicitly asks for multi-source research or when they approve a
research plan. Ordinary questions should use the normal response path.

## Contract

1. Build an internal plan containing 2–5 independent subtasks. Do not split recursively.
2. Start the plan with `research_start`; each worker receives only `search_web`, `scrape_web`, and
   `web_fetch`. Browser interaction, device actions, configuration, and file mutation are forbidden.
3. Keep every worker within the requested `max_trips` and timeout. Use the configured research
   model when supplied; otherwise inherit the parent model through the sub-agent contract.
4. Every worker must finish with compact JSON containing `summary`, `claims`, and
   `open_questions`. Every claim includes source URLs and `high`, `medium`, or `low` confidence.
5. Never paste a complete web page into the parent context. Summarise only the evidence needed.
6. Treat one failed worker as a visible gap, not a reason to discard successful workers.
7. Deduplicate source URLs before synthesis and keep claims traceable to their originating subtask.
8. Use `research_status` for progress. If the user cancels, call `research_cancel` immediately;
   cancellation cascades to every active child.
9. Only the research coordinator completion may wake the parent conversation. Individual workers
   must never post completion messages to it.
10. End with a concise synthesis that separates supported conclusions, uncertainty, and unanswered
    questions.
