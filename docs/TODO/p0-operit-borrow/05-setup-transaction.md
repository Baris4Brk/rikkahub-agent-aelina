# P0-5 Setup transaction

Goal: plan, apply, verify, and compensate typed safe changes without whole-Settings rollback or
secret disclosure.

Status: IMPLEMENTED

Delivered:

- `setup_plan`, `setup_apply`, and `setup_verify` expose only sealed, typed, non-secret changes.
- P0 supports existing Assistant model/workspace/local-tool/installed-skill/existing-MCP bindings,
  selected Assistant flags, selected app flags, and existing global model bindings.
- Arbitrary Settings keys, credentials, Provider/TTS/STT/Telegram secrets, and resource installation
  are rejected. This intentionally corrects the original checklist's unsafe P0 install/add wording;
  installers require a separate security-reviewed transaction adapter in P1.
- Plan is read-only. Apply uses per-field CAS, targeted Doctor checks, reverse compensation, and
  preserves externally changed values as rollback conflicts instead of overwriting them.
- Cancellation and unexpected backend failures compensate in `NonCancellable`; no transaction is
  left `APPLYING` by those paths.
- Setup schemas are injected only into a complete, unlocked, non-headless LocalChat privileged
  conversation. `setup_apply` is Always Ask and cannot be Always Allowed.
- AgentRun metadata contains only transaction id, change types, and count; values and secrets are
  never recorded.

Verification: all four `setup.*` test classes passed, including the real Settings adapter,
owner scope, resource validation, cancellation, audit metadata, and hardline approval behavior.
