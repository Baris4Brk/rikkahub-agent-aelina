# P5 Offline Replay / A-B / Shadow Evaluation v1

Status: deterministic replay plus disposable-emulator production Room gate implemented

The reviewed runnable adapter set is `FrozenProductionComponentReplayV1`. Its bounded invented
fixture never opens a user database, provider, file, Tool, network service, or Android service.
The five ports invoke the production Dream fence/selector adapter, Policy retriever/FTS logic,
the sole Recall compiler, the Policy exposure state machine, and the exposure outcome-link state
machine respectively. `FrozenProductionEvalBaselineV1` binds their exact manifest and counters.

CI runs `:app:p5ProductionEvaluationGate`. The task has no assemble, bundle, connected-device or
managed-device dependency. It writes only
`app/build/reports/agent-learning/p5-production-eval-redacted.txt`. It fails on test/contract,
safety, manifest/environment, or exact-counter regressions. It is intentionally an ABSTAIN by
itself: deterministic component fixtures are regression evidence, not rollout evidence.

The current performance environment digest is captured from the executing OS/architecture and
JVM vendor/major/VM plus explicit build inputs; it is never copied from the frozen baseline.
Only the pinned `ubuntu-24.04`/Temurin-17 CI profile matches the reviewed v1 descriptor. A local or
managed-emulator mismatch stays fail-closed as `PERFORMANCE_NOT_ENFORCED`/`ABSTAIN`, while the
dedicated task remains useful and green for deterministic functional regression. CI sets strict
environment enforcement, so a supposedly pinned job fails if any observed/runtime input drifts.

The disposable managed-emulator instrumentation produces a redacted gate artifact and exercises
`P5ProductionRoomIntegrationEvaluationTest`. It supplies a content-free
`ProductionRoomIntegrationAttestation` to the same `ProductionLearningEvaluationCiEntry` call.
The Room smoke attestation is necessary but deliberately insufficient for approval. The current
instrumentation reopens the checked-in 20-row fixture journal to regression-test the codec and
durability invariants, but its explicit `CHECKED_IN_REGRESSION_FIXTURE` origin always produces
`CHECKED_IN_REGRESSION_FIXTURE_ONLY`/ABSTAIN. It does not mint rollout evidence.

Approval additionally requires a `ProductionFourArmRuntimeAttestation` bound to the exact manifest
and report. Its v3 strict path accepts one independently captured authority row for every unit/arm
execution (80 rows for the frozen matrix), pre-registered assignment, matched cohorts, complete
slices, and three distinct deterministic/human/LLM judge-source identities. The checked-in
outcome adapter is rejected by the independent capture API, and a matrix whose three verdict
columns are copied identically has no independent divergence evidence and is rejected. Missing data is ABSTAIN;
identity/durable violations are REJECT.
No `connectedAndroidTest`, ADB, or Honor AAK-AN00 path is an accepted attestation source.

The instrumentation writes the bounded artifact through
`PlatformTestStorageRegistry.openOutputFile`, with `useTestStorageService=true` and
`androidx.test.services:test-services:1.6.0`. AGP 9.2.1 therefore exports it on the host under
`app/build/outputs/managed_device_android_test_additional_output/debug/` followed by the managed
device name; app-private `filesDir` is not an artifact channel.

The Room scenario opens real `AppDatabase` and `LearningDatabase` classes using the production
Requery SQLite factory and a fixed non-user corpus. Its required checks cover:

- AppDatabase and LearningDatabase/facade open plus the exact stream checkpoint;
- App-first exact grant commit and real SHADOW -> PROBATION -> ACTIVE lifecycle projection;
- the production FTS5 `simple` tokenizer with `jieba_dict` plus an actual Chinese query;
- reviewed ACTIVE Room retrieval and the sole whole-item Recall compiler;
- exposure reserve, compile, inject, host-dispatch, progress, response and terminal milestones;
- committed terminal authority outcome linking;
- durable observed-utility outcome and append-only evaluation receipt;
- process-style close/reopen and exact Room-row reload.

`initializePolicyFtsRuntime` is the single LearningDB open callback used by production and this
test. It loads `SimpleDictManager`, checks `SELECT jieba_dict(?)`, and only then installs/backfills
the Policy FTS projection. A framework-SQLite or tokenizer mock does not satisfy the gate.

## Registered arms

The harness evaluates every frozen replay unit against the same four arms:

1. `A_NO_LEARNING`
2. `B_DREAMING_ONLY`
3. `C_DREAMING_REVIEWED_POLICY`
4. `D_FULL_REVIEWED_RUNTIME_NO_JS`

The D arm rejects any script action before report publication.

`FrozenFixtureReplayExecutor` is a synthetic golden for aggregation/bootstrap unit tests only. It
is not a default entry point and cannot publish a production rollout decision.

## Production-component replay

The deterministic component path is `ProductionLearningEvaluationCiEntry` ->
`ProductionFourArmFixtureRunner`. It accepts five independently implemented, identity-bound ports:

1. `DreamProjectionReplayPort` - calls the real Dream projection adapter;
2. `PolicyRetrievalReplayPort` - calls reviewed-Policy retrieval;
3. `RecallCompilerReplayPort` - calls the real Recall compiler with prior adapter outputs;
4. `PolicyExposureReplayPort` - calls exposure/dispatch accounting;
5. `PolicyOutcomeReplayPort` - calls outcome linking and returns its recorded trace observation.

The runner never branches on `ReplayFixtureScenario` to manufacture an outcome. Task, safety,
token, latency, funnel, judge, and deterministic work observations come from adapters. A missing,
rejected, exceptional, or dependency-blocked component creates an explicit abstention and unknown
outcome. `ProductionComponentReplayAdapters.unconfigured()` is the default and has no success path.

## Corpus, matching, assignment and holdout

`FrozenReplayCorpusV1` contains content-free fixture identifiers and frozen slice labels only. It
covers model identity, tool-schema identity, task class, scope kind and language. Each replay unit
is one matched cohort; paired differences include only observed pairs.

`OfflineEvalPlan` freezes the plan ID, assignment salt, holdout proportion, bootstrap count and
confidence level. SHA-256 domain-separated assignment selects a primary shadow arm and holdout
partition. Assignment output is sorted by unit ID, and the report includes corpus, plan and
assignment-manifest digests. Reordering input cannot change assignment or bootstrap output.

`FrozenProductionEvalManifest.freeze` additionally binds the exact corpus/plan/assignment digests,
runner version, all five adapter implementation digests, frozen rollout criteria, and frozen
relative performance contract. Any adapter implementation change changes the manifest digest and
invalidates an old performance baseline.

Adapter implementation identities are additionally bound to reviewed normalized source snapshots
for the production Dream adapter, Policy retriever, Recall compiler, exposure state machine, and
arm-blind outcome authority. A JVM contract test recomputes the snapshots from the checkout;
runtime code uses embedded digests and never reads repository files.

## Outcome knowledge and uncertainty

Binary outcomes are one of:

- `Observed(true|false)`
- `Unknown(reason)`
- `Censored(reason)`

Unknown and censored rows remain separate and are excluded from the observed-rate denominator.
Every rate includes observed, positive, unknown and censored counts. Deterministic percentile
bootstrap intervals use a plan-derived seed and canonicalized inputs.

Arm comparisons are `OBSERVED_ASSOCIATION_ONLY_NOT_CAUSAL`. Deterministic, blinded-human and LLM
verdicts have separate source identities in approval-eligible runtime evidence. LLM-judge
divergence is reported separately from deterministic and human authority. The checked-in
regression includes fixed divergence examples, but those examples cannot authorize rollout.

## Rollout decision

The production gate has three states: `APPROVE`, `REJECT`, and `ABSTAIN`.

- any scope leak, stale hit, harmful positive observation, or script action rejects;
- any production-component abstention abstains;
- fewer than 12 observed task outcomes per arm, 12 observed Policy outcomes per reviewed-Policy
  arm, or 12 paired outcomes per comparison explicitly abstains as `SAMPLE_SIZE_INSUFFICIENT`;
- full-runtime observed success below 80% rejects;
- the frozen association rule is `CONFIDENT_POSITIVE_GAIN` for D versus A: the 95% bootstrap
  lower bound of `successRateDifference` must be at least +1 basis point. The alternative
  `NON_INFERIORITY` rule is represented explicitly but is not selected by this contract;
- a missing, mismatched, or non-enforced performance baseline abstains;
- a Room-only smoke run abstains as `DURABLE_FOUR_ARM_RUNTIME_NOT_OBSERVED`;
- approval requires every preceding gate, frozen performance, and an exact durable four-arm
  runtime attestation bound to the same manifest/report digests. A zero-gain fixture therefore
  abstains even if every absolute D-arm outcome is successful.

Unknown and censored rows never increase observed sample size.

## Metrics, slices, and report boundary

Each arm records task success, corrections, tool calls/retries, token categories, recorded trace
latencies, Policy funnel counts/outcome, scope leaks, stale hits, harmful outcomes, and scripts.
Each model/tool-schema/task/scope/language value has a separate row for every arm.

`RedactedEvalReportRenderer` emits bounded aggregate counters, safe slice labels and frozen digests.
It never renders fixture payloads, prompts, model output, paths, URLs, secrets, or user identifiers.
The report is capped at 16,384 characters and marks truncation explicitly. A compact v2 slice
section is written before optional verbose aggregates and contains every model/tool-schema/task/
scope/language value for every arm plus `slice_coverage_complete=true`; insufficient budgets are
rejected instead of silently publishing a partial slice matrix.
`RedactedProductionEvalArtifactFactory` adds the manifest/report digests, rollout/performance
decisions, component-abstention count, Room digest, and durable-four-arm digest. Its bounded v3
artifact has independent redacted-report and envelope digests suitable for CI publication.
