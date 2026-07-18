# P0-1 Sub-agent execution contract

Goal: make model, system prompt, tool restriction, and maxTrips affect the real child run without
mutating the parent Assistant or duplicating GenerationHandler.

Status: IMPLEMENTED

Delivered:

- Resolve one immutable child execution profile from the actual parent turn.
- Validate model overrides against enabled configured models; never mutate the parent Assistant.
- Resolve prompt precedence as request override, Assistant default, then bounded built-in default.
- Intersect requested tools with both the parent's published surface and the headless-safe surface.
- Reject unknown, unauthorized, interactive, recursive, and management tools before dispatch.
- Apply `maxTrips` to the real GenerationHandler loop and remove the profile with run-owned CAS.
- Let a research coordinator own completion notification so child runs cannot wake the parent twice.

Verification: the P0 target pass on 2026-07-18 included all six `subagent.*` test classes and
`ToolNameSurfaceTest` with zero failures.
