# P2.3 second-user complete app-control audit

Baseline: `feature/owner-pet-library-control@ffa466b1`, Room v42.

## Protected compatibility boundary

- The active local second user keeps the DIRECT tool surface and automatic execution policy.
- The existing 11 Owner tool names and 83 registered actions remain compatible.
- Authority, epoch, the protected assistant/conversation, HARDLINE, Emergency Stop, Android permission prompts, and strong-biometric secret sessions are unchanged.
- Provider URLs remain readable. Vault plaintext remains process-memory-only and is available to the bound cloud model only during the existing 30-minute biometric session.
- No tool catalog/search/open step is introduced for Owner controls.

## Current implementation

Implemented: global second-user authority, protected deletion, Owner operation ledger, idempotent one-call execution, Provider/Vault basics, Generic HTTP TTS, local services and EmotionTTS, MCP, Skill, Workflow, typed navigation, Doctor, and direct Provider tool injection.

Known gaps at this baseline:

- `OwnerToolFamily` exposes 11 families; large application domains still have UI-only mutation paths.
- `ExistingHostOwnerOperationHandler.isProtectedIdentityMutation()` is an empty guard.
- Owner action metadata, argument guides, handler field maps, and tool schemas are maintained separately.
- several handlers report success as verification instead of re-reading authoritative state.
- `OwnerOperationExecutor` serializes every operation through one global mutex.
- TTS playback-speed actions exist in the handler but are absent from the model-facing Owner specification.
- no app-private managed pet-package library exists yet.

## P2.3 target

Owner actions are generated from one authoritative registry and delegated to the same domain facades used by UI. The active local second user can directly manage stable RikkaHub state without new approval cards. Permanent protection is limited to Owner identity; replaceable resources use validate/test/switch/verify/delete transitions instead of permanent locks.

Room remains v42 unless implementation proves an unavoidable persistent-field gap. Pet archives reuse `managed_files`; queued command ordering reuses the existing `sequence` column.

