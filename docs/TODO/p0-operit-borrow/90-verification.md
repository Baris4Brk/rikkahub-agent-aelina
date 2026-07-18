# P0 release verification

Status: COMPLETE (OFFLINE AND HONOR DEVICE)

Completed:

- P0 target JVM matrix: 15 test classes, 58 tests, 0 failures, 0 errors, 0 skipped.
- `ResearchCoordinatorTest`: 7 tests passed, including compensation when `research_start` is
  cancelled after an earlier child has already been dispatched.
- Setup package regression after cancellation/exception compensation: passed.
- Android instrumentation test sources: `:app:compileDebugAndroidTestKotlin` passed.
- Complete JVM regression: 225 test classes, 1,574 tests, 0 failures, 0 errors, 0 skipped.
- Production Kotlin compilation, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed.
- Standards review fixed new-line-length, stale database-version documentation, and duplicated
  compensation code. Spec review found and fixed the missing DeferredExecution classification for
  `research_start`, `research_status`, and `research_cancel`.
- A complete user-0 pre-P0 backup was written to
  `I:\RikkaHubBackups\AAK-AN00\20260718-115149` before installation. The 11,368,338,944-byte
  archive has 165,976 entries and SHA-256
  `0D457F55638EF8C789FB6E5AAFC4082FCF62DCF5B4BD1609C194F412F4E1A2EC`.
- Covered installation preserved application data. The real database opened at version 30 with
  the expected memory metadata, FTS5 projection, `simple` tokenizer, synchronization triggers,
  and Room identity.
- The device's framework SQLite lacks FTS5. The production and migration-test paths now share the
  bundled SQLite adapter; the final device run of `Migration_29_30_Test` reported `OK (1 test)`.
- A temporary, tool-free clone of an Assistant explicitly marked for testing completed one real
  model turn with the expected fixed answer. The clone, conversation, reports, test databases,
  instrumentation package, and temporary instrumentation sources were deleted afterward.
- Final v170 restarted successfully after a user-0 force-stop. The crash buffer remained empty,
  notification-listener access remained enabled, and MagicVoice/YOYO remained the assistant,
  voice-interaction service, recognition service, and `ROLE_ASSISTANT` holder.
- Final ARM64 APK: versionCode 170, versionName `2.3.1-agent-up242.6`, 99,393,032 bytes.
- APK v2 signature verified; signer certificate SHA-256:
  `DAB0E125537683B4E6F161AEDBF126DEF730C90DE94A1A98317E12A77481A45B`.
- ARM64 APK SHA-256: `A814A9209C3AEAE45FB4A62E05076077B3F02FDE97E296F116A2AE36E10B2989`.
- APK content inspection confirmed the bundled Deep Research skill and arm64 SQLite extension are
  present.

Scope remained Android user 0 throughout. Android user 100, the protected personal Assistants
`啥子七` / `啥子七报道`, YOYO settings, and the read-only Operit checkout were not modified.
