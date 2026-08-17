# LearningDB v9 retention / exact-scope erase / restore-reset matrix

Status: frozen audit of the current `LearningDatabase.kt` v9 inventory (29 Room entities). This
matrix is normative for P1-003B, P5-003, P5-004, and P5-007. The checked-in v8 predecessor stays
immutable at 26 entities; v9 adds only the three append-only observed-utility ledger entities. The
exported Room schemas and the production DAO/store paths named below jointly define this audit.

Common rules:

- All durations originate in `LearningRetentionDecisionPolicyV1`; DAOs receive a frozen cutoff.
- Every ordinary sweep is bounded to 1..128 rows/category and runs through the low-priority local
  `LearningResourceGovernor` route. Retrieval correctness never depends on a later sweep.
- Exact-scope erase first removes Policy FTS text, then performs the Room graph mutation in one
  transaction. Workers are fenced by deleting exact-scope jobs while the runtime operation lane is
  held. Recent source tombstones retain only content-free authority/audit metadata.
- A verified authoritative-main-DB restore quarantines the complete Learning DB/WAL/SHM set and
  requires process restart. No derived Learning table is imported from the backup.
- A stream rewind/reset deletes every derived root in dependency order, creates a new replay
  generation, and retains only the new bootstrap checkpoint.

Legend: **cascade** means the row has no independent retention or erase policy; it follows the
named parent. **audit** means content-free lifecycle metadata is intentionally retained.

| Room entity / table | Ordinary retention | Reference/lifecycle pin | Exact-scope erase | Restore / stream reset |
|---|---|---|---|---|
| `LearningInboxEventEntity` / `learning_inbox_events` | 90 d; only consumed `KNOWN`, ordinary, non-source-lifecycle events | `STREAM_INIT`, unknown schema, source transitions, and events with active or dead-letter jobs | Direct `scope_kind + scope_id` delete | Whole-file quarantine; direct reset delete |
| `LearningStreamCheckpointEntity` / `learning_stream_checkpoints` | Operational control row; no TTL | Current stream/bootstrap/checkpoint authority | Not scope-owned | Whole-file quarantine; delete then create one new-generation bootstrap row |
| `LearningJobEntity` / `learning_jobs` | `DONE` 90 d (outbound-receipt TTL) | `PENDING/RETRY/RUNNING`; `DEAD_LETTER` remains auditable/retryable | Direct exact-scope delete after quiescence; cascades provider manifest/attempt | Whole-file quarantine; direct reset delete |
| `LearningEpisodeEntity` / `learning_episodes` | Final episode 90 d | Open episode, active job, lesson, reward window, Policy evidence, exposure, or utility assignment | Direct exact-scope delete after dependants | Whole-file quarantine; direct reset delete |
| `LearningTraceFeatureEntity` / `learning_trace_features` | 7/30/90 d from user retention preset | Valid lesson and active distillation/lineage job | Delete through exact-scope episode IDs | Whole-file quarantine; direct reset delete |
| `LearningEpisodeLessonEntity` / `learning_episode_lessons` | 180 d | Exact Policy evidence and active job | Direct exact-scope delete; source privacy invalidation first replaces every summary and artifact with fixed redaction markers | Whole-file quarantine; direct reset delete |
| `LearningRewardWindowEntity` / `learning_reward_windows` | Closed window 30/90/180 d from user preset | Open window and active distillation/lineage job | Direct exact-scope delete | Whole-file quarantine; direct reset delete |
| `LearningSourceValidityEntity` / `learning_source_validity` | Current `VALID` authority remains operational; invalid/tombstone/superseded audit floor 180 d when unreferenced | Exact Trace/Policy evidence reference | Delete `VALID` and expired rows; retain recent content-free audit tombstones | Whole-file quarantine; direct reset delete |
| `LearningPolicyEntity` / `learning_policies` | Unreviewed unused `CANDIDATE/SHADOW` older than 180 d transitions to `ARCHIVED`; no raw delete sweep | User/grant-reviewed, active/probation/suspended/stale/archive lifecycle is durable | Direct exact-scope delete after Curator redaction; cascades evidence/revisions/lineage/items | Whole-file quarantine; direct reset delete |
| `PolicyEvidenceEntity` / `policy_evidence` | **cascade** with Policy | Pins exact Episode/lesson; Episode FK is `RESTRICT` | Policy cascade | Whole-file quarantine; direct reset delete |
| `PolicyRevisionEntity` / `policy_revisions` | Superseded machine revision 180 d | Current head plus `USER`/`GRANT_BINDER` receipts; source privacy path redacts snapshots | Policy cascade | Whole-file quarantine; direct reset delete |
| `PolicyLineageEntity` / `policy_lineage` | **cascade** with either Policy endpoint | Policy lineage audit | Policy cascade | Whole-file quarantine; direct reset delete |
| `LearningProviderConfigCohortEntity` / `learning_provider_config_cohorts` | Delete as soon as unreferenced during bounded maintenance (no scattered day constant) | Manifest FK is `RESTRICT` | Scope jobs remove manifests; shared content-free cohort is removed by the next unreferenced sweep | Whole-file quarantine; reset prunes after job cascade |
| `LearningProviderJobManifestEntity` / `learning_provider_job_manifests` | **cascade** with Job/receipt lifetime | Pins exact provider cohort | Exact-scope Job cascade | Whole-file quarantine; Job reset cascade |
| `LearningProviderAttemptEntity` / `learning_provider_attempts` | **cascade** with Job manifest | Dispatch/terminal receipt remains with auditable job | Exact-scope Job cascade | Whole-file quarantine; Job reset cascade |
| `LearningRewardSignalEntity` / `learning_reward_signals` | 30/90/180 d when unreferenced | `PolicyRewardEvidenceEntity` pins it | Exact-scope Episode cascade | Whole-file quarantine; Episode reset cascade |
| `PolicyRewardEvidenceEntity` / `policy_reward_evidence` | **cascade** with Policy evidence | Pins exact reward signal (`RESTRICT`) | Policy-evidence cascade | Whole-file quarantine; Policy reset cascade |
| `LearningPolicyShadowObservationEntity` / `learning_policy_shadow_observations` | 180 d, bounded request-root page | None beyond its observation items | Direct exact-scope delete | Whole-file quarantine; direct reset delete |
| `LearningPolicyShadowObservationItemEntity` / `learning_policy_shadow_observation_items` | **cascade** with observation; also Policy cascade | Exact Policy fence only | Observation/Policy cascade | Whole-file quarantine; observation reset cascade |
| `LearningPolicyExposureEntity` / `learning_policy_exposures` | Settled terminal+outcome-linked exposure 180 d | Unsettled state machine and Episode FK (`RESTRICT`) | Direct bounded exact-scope pages | Whole-file quarantine; direct reset delete |
| `LearningPolicyExposureItemEntity` / `learning_policy_exposure_items` | **cascade** with exposure | Exposure request receipt | Exposure cascade | Whole-file quarantine; direct reset delete |
| `LearningObservedUtilityAssignmentEntity` / `learning_observed_utility_assignments` | Fixed source window end + 180 d | Episode FK (`RESTRICT`) until assignment expires | Direct bounded exact-scope pages | Whole-file quarantine; direct reset delete |
| `LearningObservedUtilityOutcomeEntity` / `learning_observed_utility_outcomes` | **cascade** with assignment | Immutable fixed-window closure | Assignment cascade | Whole-file quarantine; direct reset delete |
| `LearningObservedUtilityEvaluationReceiptEntity` / `learning_observed_utility_evaluation_receipts` | Evaluation time + 180 d | Append-only evaluation receipt until TTL | Direct bounded exact-scope pages | Whole-file quarantine; direct reset delete |
| `LearnedWorkflowCandidateEntity` / `learned_workflow_candidates` | Eligible proposal/rejection/stale row older than 180 d transitions to `ARCHIVED`; no ordinary raw delete | Reviewed/promoted lifecycle and source Policy FK (`RESTRICT`) | Direct assistant or authority-subject scope delete | Whole-file quarantine; direct reset delete |
| `LearnedWorkflowCandidateRevisionEntity` / `learned_workflow_candidate_revisions` | Superseded machine revision 180 d | Current and every `USER` revision remain audit | Candidate cascade | Whole-file quarantine; candidate reset cascade |
| `CuratorDeltaCandidateEntity` / `curator_delta_candidates` | Eligible unreviewed/conflict candidate older than 180 d transitions to `ARCHIVED`; no ordinary raw delete | Approved/applying/applied/rolled-back review lifecycle | Exact-scope CAS destroys candidate/plan/source wires, deactivates lineage, and appends content-free `SOURCE_REDACTED` audit revision | Whole-file quarantine; independent root is now directly deleted on stream reset |
| `CuratorDeltaRevisionEntity` / `curator_delta_revisions` | **audit**, cascade with Curator candidate | Content-free user/apply/rollback/privacy lifecycle | Redacted audit remains; candidate content does not | Whole-file quarantine; Curator candidate reset cascade |
| `CuratorDeltaLineageEntity` / `curator_delta_lineage` | **cascade** with candidate/Policy endpoints | Active exact apply-plan lineage; rollback/privacy deactivates before Policy deletion | Deactivate, then Policy/candidate cascade as applicable | Whole-file quarantine; Curator candidate reset cascade |

## Primary AppDatabase authority/transport rows (not LearningDB cache)

| Authority row | Retention / erase rule |
|---|---|
| `LearningOutboxEntity` | P0 does not prune. The enabled retention path requires the minimum contiguous checkpoint of every durable consumer, minimum age, and safety floor simultaneously; `STREAM_INIT` is never removed. Scope erase does not rewrite authoritative transport history. |
| `LearningConversationSourceAuthorityEntity` / `LearningMessageSourceAuthorityEntity` | Current source heads and minimal monotonic tombstones are authority, not derived cache. Source edit/delete always advances them and emits adjacent invalidation even after capture consent is withdrawn. Raw message content is absent; default strings redact stable IDs/digests. |
| `LearningPolicyGrantEntity` / `LearningPolicyGrantRevisionEntity` | Durable user grant/revocation authority and append-only audit. Clearing derived Learning rows does not forge or silently delete a user authorization decision; grant revocation has its own exact assistant/scope flow. |
| `RewardFeedbackAuthorityEntity` / `RewardFeedbackAuthorityRevisionEntity` | Durable explicit feedback authority. A user retraction advances to a content-free tombstone; its replacement/tombstone event propagates even after capture consent is withdrawn. Derived reward signals are erased in LearningDB independently. |

## Exact-scope zero-Policy invariant

Erase must not use the existence of a Policy row as its scope-discovery gate. Shadow observations,
utility receipts, workflow candidates, episodes/traces/lessons/rewards, jobs, inbox rows, FTS, and
ephemeral handles are addressed directly by the requested `LearningScope`. The disposable-Room
test `AuthorityScopeEraseWithoutPolicyRoomTest` fixes the regression case where an
`AUTHORITY_SUBJECT` scope contains an independent shadow root and exactly zero Policy rows.

## Privacy surface gates

- `LearningPrivacySurfaceContractTest` freezes the exact 29-entity inventory and requires a custom
  redacted `toString()` for every Learning entity plus main-DB source/grant/feedback/outbox rows.
- `LearningForbiddenCorpusContractTest` is the shared release corpus. Storage guard failures do not
  echo rejected input; Diagnostics canonicalization and redacted eval export reuse the same corpus.
- Curator and Workflow structured wires are bounded and decoded through strict canonical codecs;
  default string projections expose neither wires nor stable IDs.
- Provider attempts/manifests retain only provider/model/configuration identities, field-category
  identity, counts, budgets, timing, and cost; they contain no prompt or response body.

## Closure items resolved after this audit

1. Background prompt ownership now uses an `AtomicReference`; every completion, cancellation and
   exception path clears/closes it in `finally`, so the request payload is no longer retained by
   the background client after the attempt.
2. `LearningEraseReceipt` now reports the workflow, observed-utility and provider-cohort effects in
   addition to the LearningDB deletion counts, preserving an accountable content-free receipt.
3. Exact-scope erase and derived reset now prune unreferenced provider cohorts after dependent
   manifests/attempts are removed. Shared cohorts remain only while referenced by another scope.

These closures remain covered by JVM contracts and the disposable Room/GMD release gate; this
matrix no longer treats the superseded implementation gaps as open.
