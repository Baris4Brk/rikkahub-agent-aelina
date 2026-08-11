# P0 release verification

Status: COMPLETE (OFFLINE VERIFICATION ENVIRONMENT)

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
- A verified offline backup was completed before installation. Local paths, timestamps, archive
  sizes, entry counts, and digests are intentionally omitted from this document.
- Covered installation preserved application data. The migration fixture opened at version 30 with
  the expected memory metadata, FTS5 projection, `simple` tokenizer, synchronization triggers,
  and Room identity.
- The compatibility environment's framework SQLite lacks FTS5. The production and migration-test
  paths now share the bundled SQLite adapter; the isolated migration verification passed.
- A temporary, tool-free clone of an Assistant explicitly marked for testing completed one real
  model turn with the expected fixed answer. The clone, conversation, reports, test databases,
  instrumentation package, and temporary instrumentation sources were deleted afterward.
- Final v170 restarted successfully in the explicitly authorized offline environment. The crash
  buffer remained empty and required access bindings remained enabled.
- Final ARM64 APK: versionCode 170, versionName `2.3.1-agent-up242.6`, 99,393,032 bytes.
- APK v2 signature verified; signer certificate SHA-256:
  `DAB0E125537683B4E6F161AEDBF126DEF730C90DE94A1A98317E12A77481A45B`.
- ARM64 APK SHA-256: `A814A9209C3AEAE45FB4A62E05076077B3F02FDE97E296F116A2AE36E10B2989`.
- APK content inspection confirmed the bundled Deep Research skill and arm64 SQLite extension are
  present.

Scope remained the explicitly authorized owner profile throughout. Non-owner profiles, protected
production assistants, OEM assistant settings, and the read-only reference checkout were not modified.
