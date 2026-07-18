# P0-3 Workspace rules

Goal: inject bounded, cached project rules independently of Linux shell readiness.

Status: IMPLEMENTED

Delivered:

- Resolve `/workspace/RULES.md`, root/deeper `AGENTS.md`, and
  `/workspace/.rikkahub/AGENTS.md` with deterministic later-rule precedence.
- Reject absolute escape paths, `..`, NUL input, invalid UTF-8, empty files, and read/stat errors.
- Cache by workspace, normalized path, size, and modification time; invalidate stale path entries.
- Limit each file to 16,384 characters and the combined rule content to 32,768 characters while
  preserving the highest-priority rules first.
- Escape prompt XML and load through a repository seam independent of Linux shell readiness.

Verification: `WorkspaceRulesResolverTest` passed in the 57-test P0 target pass.
