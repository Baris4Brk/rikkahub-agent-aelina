# Operit-inspired P0 implementation ledger

Fixed point: `be51e31635ba68f5c15713b24436c1d6e4280718`
Reference only: Operit `723c313db3c18b974388a7c49eecbe48747c58fe` (LGPL-3.0)

This work is a clean-room RikkaHub implementation. Operit supplies behavioural ideas and
contracts only; its ToolPkg/ObjectBox/runtime implementations are not copied.

## Invariants

- Keep the existing ConversationRuntime, steering UI, final-answer recovery, AI-key overlay,
  YOYO binding, emergency stop, and self-preservation policies.
- Never mutate or test against `啥子七` / `啥子七报道` or Android user 100.
- Do not modify or build the Operit checkout.
- Run one Gradle process at a time on Windows.

## Baseline

- `ai` and `search` unit tests passed at the fixed point.
- App production Kotlin compiled at the fixed point. The prior installed build recorded 1,735
  passing JVM tests; the first fresh baseline attempt on this host was interrupted while compiling
  App test sources by a Kotlin daemon native-memory crash, before assertions ran.
- `workspace:testDebugUnitTest` has four pre-existing Windows host-tool failures (`tar`/shell
  process launch); new workspace-rules tests use the rules interface and remain host-neutral.

## Progress

| Step | Status | Verification |
|---|---|---|
| P0-1 sub-agent execution contract | IMPLEMENTED | Target tests passed; see `01-subagent-contract.md` |
| P0-2 memory Top-K | IMPLEMENTED | JVM and HONOR migration tests passed; see `02-memory-retrieval.md` |
| P0-3 workspace rules | IMPLEMENTED | Target tests passed; see `03-workspace-rules.md` |
| P0-4 bounded deep research | IMPLEMENTED | Target tests passed; see `04-deep-research.md` |
| P0-5 setup transaction | IMPLEMENTED | Target tests passed; see `05-setup-transaction.md` |
| Release verification | COMPLETE | 1,574 JVM tests, v170 APK, backup, migration, and safe model smoke passed; see `90-verification.md` |
