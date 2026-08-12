# Agent Learning Runtime PRE-000 baseline manifest

Status: frozen for the unpublished Room v46 Learning integration.

Frozen at: 2026-08-12 08:52:23 Asia/Shanghai

This manifest records file contents, not only Git HEAD, because the working tree contains
intentional uncommitted Dreaming-X, memory, context and Learning work.

## Repository snapshot

- Branch: `codex/publish-current-code`
- HEAD: `5eb8c37cbaae85a2155466334d00284f04b24096`
- Upstream divergence at freeze: `+0/-0`
- Worktree at the 08:33 audit snapshot: 92 tracked unstaged files, 72 untracked files, 0 staged
  files
- Frozen Dreaming/central-DB monitor set: 134 files
- Aggregate SHA-256 of that monitor set:
  `BA84471284B07002D13B793B456B5D748C4BA6F76953DECF0DF68D472D8B2434`

The final observed Dreaming-related source write was at 08:41:12. The monitor then observed more
than eleven continuous minutes without a Dreaming or central-database source change.

## Pre-Learning Room v46

| Artifact | Frozen value |
|---|---|
| `AppDatabase.version` | `46` |
| Room entity count | `49` |
| `learning_outbox` registered | No |
| Pre-Learning v46 identity | `8ef3ddc71d855013202bb11b0493d6e6` |
| `AppDatabase.kt` SHA-256 | `2D72E7532B2C566BD129C580AFA54A48E8B2991D9B7BE22461E8F6775FE152EF` |
| `Migration_45_46.kt` SHA-256 | `B1F371EF42DCA8306CD440A235C1151240DF32C63DF4C369C2FD0C78D5419F3D` |
| exported `46.json` SHA-256 | `340DACC72C3EB30D66C72A662ACE6E92CAEFCA3A1AC2EEFB5F1A08384B1D5D9A` |
| `ImportedDatabaseReconciler.kt` SHA-256 | `5459211497412FFB6AC5532B8887A4F87FAE437924753855E6FD2EE099F827C3` |
| `DataSourceModule.kt` SHA-256 | `B592AFFD695E2ED6C73EFC29BCFC270F9BC73CFF8079DBA5232C539CEAC31B4F` |

`MIGRATION_45_46` is registered. The pre-Learning reconciler version/hash and exported schema agree.
The identity `8ef3ddc71d855013202bb11b0493d6e6` is henceforth
`PRE_LEARNING_V46_IDENTITY_HASH`; the same-version compatibility path may match only this exact
identity.

## Dreaming-X runtime facts

- Missing or malformed preferences resolve to `generate=false`, `shadow=false`, and `use=false`
  per scope. Model synthesis and prompt projection therefore default off.
- `schemaReady=true` is supplied by trusted DI. `deepRebuild=false` and `relationRoute=false`.
- Dreaming-X is not wholly dormant: memory authority transactions maintain its observer journal
  and privacy scrubbing, and observer/synthesis recovery scheduling is wired in production.
- Enabling the corresponding user flags activates provider synthesis and the GenerationHandler
  projection path.
- Learning has no frozen public, bounded Dreaming identity read API. The current Learning adapter
  must remain unavailable instead of querying Dream DAOs directly.

The shared context path remains:

`ProviderRequestContextPolicy -> GenerationProviderContextPreparer -> GenerationHandler -> final
hard gate -> ProviderCacheIdentityFactory`.

Learning must extend that path and must not create a second prompt composer or bypass the final
hard gate.

## Same-version v46 integration rules

1. Do not create a `46 -> 47` migration for this work.
2. Merge the narrow Learning schema into the existing unpublished `45 -> 46` migration.
3. Re-export the final `46.json` and replace the expected identity with the generated value.
4. Add an exact current-version compatibility delta from the pre-Learning identity recorded above.
5. Never classify an unknown v46 identity as this baseline and then stamp it as current.
6. Test fresh creation, `45 -> final 46`, exact pre-Learning v46 -> final v46, unknown-v46 refusal,
   imported restore, duplicate execution and data preservation.

## Validation at freeze

- External complete app JVM suite: 467 test classes, 2,618 tests, 0 failures, 0 errors, 1 skipped.
  The skipped case was the platform-dependent symbolic-link branch of
  `LearningRestoreQuarantineTest`.
- After the final Learning API tightening:
  - `:app:compileDebugKotlin`: passed.
  - Learning/background/Workflow targeted `:app:testDebugUnitTest`: passed.
  - `:local-llm:testDebugUnitTest --tests me.rerere.locallm.litert.LiteRtRuntimeTest`: passed.
- Android instrumentation and managed-device Room tests: not run yet.
- APK assembly: deliberately not run.
- ADB and the Honor AAK-AN00 primary phone: not used.
