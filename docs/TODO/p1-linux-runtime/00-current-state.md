# P1 Linux Runtime current-state baseline

Baseline: branch `backup/2026-07-25-before-changes`, commit `216fb5fd`, database v35 is unreleased.

## Runtime boundaries

- `TERMUX_NATIVE` uses Termux `RUN_COMMAND`; managed capture already has an authenticated
  supervisor, stable execution id, PID/PGID/start-ticks checks, TERM→KILL, and tmux PTY sessions.
- `WORKSPACE_PROOT` is the app-owned Ubuntu rootfs under `files/workspaces/<id>/linux`. Its
  `/workspace` bind is the user file tree; the rootfs and managed-process metadata stay private.
- RikkaHub and Termux are separate Android UIDs. Their private directories cannot be joined by
  policy code. The common zero-copy boundary is shared storage.

## Storage contract

- Existing workspaces migrate as `PRIVATE`.
- `SHARED` workspaces bind `/workspace` to
  `/storage/emulated/0/RikkaHubExchange/workspaces/<workspaceId>`; Termux sees the same bytes at
  `~/storage/shared/RikkaHubExchange/workspaces/<workspaceId>`.
- Storage mode is persisted in Room and mirrored by a private resolver marker so background
  Workspace processes resolve the same root after process restart.

## Authority contract

- The second-user principal is `assistantId:privilegedConversationId`, never Android user 100.
- Linux and full shared-storage access require persistent scoped grants. Every call still checks
  selected conversation, local origin, unlocked device, HardDeny and emergency stop.
- Revocation blocks subsequent queued calls at the gate and force-stops owned managed executions.
- Shizuku/Sui remains a separate structured capability; the storage grant never implies raw
  system-shell authority.

## Borrowed concepts, rejected implementations

Operit's explicit environment selection, session vocabulary and current-directory behavior are
useful. Its hard-coded root path, static callback ownership, timeout-as-success behavior and weak
process identity are not copied.
