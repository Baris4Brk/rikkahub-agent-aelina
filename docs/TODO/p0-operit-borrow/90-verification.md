# P0 release verification

Status: OFFLINE COMPLETE; DEVICE VALIDATION BLOCKED (ADB DEVICE NOT CONNECTED)

Completed so far:

- P0 target JVM matrix: 15 test classes, 58 tests, 0 failures, 0 errors, 0 skipped.
- `ResearchCoordinatorTest`: 7 tests passed, including compensation when `research_start` is
  cancelled after an earlier child has already been dispatched.
- Setup package regression after cancellation/exception compensation: passed.
- Android instrumentation test sources: `:app:compileDebugAndroidTestKotlin` passed.
- Complete JVM regression: 225 test classes, 1,574 tests, 0 failures, 0 errors, 0 skipped.
- Production Kotlin compilation and final `:app:assembleDebug`: passed.
- Standards review fixed new-line-length, stale database-version documentation, and duplicated
  compensation code. Spec review found and fixed the missing DeferredExecution classification for
  `research_start`, `research_status`, and `research_cancel`; no findings remain.
- Final ARM64 APK: versionCode 169, versionName `2.3.1-agent-up242.5`, 100,107,965 bytes.
- APK v2 signature verified; signer certificate SHA-256:
  `DAB0E125537683B4E6F161AEDBF126DEF730C90DE94A1A98317E12A77481A45B`.
- ARM64 APK SHA-256: `9965DA69EA6BB9C579FE44F42E7A6E6953067F7B8255472DE2F830A02FA45B0D`.
- APK content inspection confirmed the bundled Deep Research skill and arm64 SQLite extension are
  present.

Still required before device validation can be marked complete:

- Verified user-0 backup, covered installation, v29->v30 device migration test, and clone-Assistant
  smoke checks without touching Android user 100, YOYO, or the protected personal Assistants.
- On 2026-07-18, both `adb devices -l` and Windows PnP enumeration returned no Android/HONOR
  device. No installation or device mutation was attempted without the required backup.

The final pass records unit-test counts, schema migration, standards/spec review, APK hashes,
user-0 backup verification, installation, and clone-Assistant device checks.
