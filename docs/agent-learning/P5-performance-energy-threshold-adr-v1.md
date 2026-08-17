# P5 Performance and Energy Threshold Process ADR v1

Status: Frozen relative-threshold contract; baseline measurement evidence DRAFT/UNVERIFIED

## Decision

CI gates deterministic operation units and logical allocation units. It does not gate changes on
wall-clock milliseconds, `nanoTime`, sampled heap size, emulator battery values, or other noisy
magic constants.

`DeterministicPerformanceTrendGate` compares current counters with one identity-bound baseline.
Thresholds are relative ratios in basis points. A missing baseline, corpus mismatch, baseline-ID
mismatch, manifest mismatch, or `DRAFT` threshold produces `NOT_ENFORCED`, never a fabricated pass.

The v1 relative-threshold contract is frozen at:

- deterministic operation ratio: at most `11_000` basis points (10% over baseline);
- logical allocation ratio: at most `11_500` basis points (15% over baseline).

The baseline ID is `p5-production-components-baseline-v1`. A
`ProductionEvalPerformanceBaseline` also binds the complete production evaluation manifest digest:
corpus, plan, assignments, all adapter implementations, rollout criteria, and thresholds. A
manifest mismatch is `NOT_ENFORCED/MANIFEST_IDENTITY_MISMATCH` and rollout `ABSTAIN`, not pass.

The environment identity is not a caller-selected label. `ProductionEvalRuntimeEnvironment`
observes `os.name`, `os.arch`, `java.vendor`, `java.specification.version`, and `java.vm.name` from
the process, normalizes them to bounded families, and binds those facts with explicit CI/build
inputs (profile, Gradle, AGP, Kotlin, JVM target, compile SDK, and gate task). Missing build inputs
are encoded as `unbound`. Consequently, supplying only the expected profile name cannot reproduce
the declared environment digest.

The declared target environment is Linux/x86_64, Eclipse Adoptium 17/OpenJDK 64-bit Server VM, GitHub
Actions `ubuntu-24.04`, Gradle 9.4.1, AGP 9.2.1, Kotlin 2.4.0, JVM target 17, and compile SDK 37.
`.github/workflows/p5-production-evaluation.yml` pins and supplies those inputs and requests strict
matching. In that strict job, drift fails the CI test rather than silently publishing an
unenforced result. An ordinary local run supplies its actually observed descriptor; a different
or unbound environment remains green as a regression run but reports
`NOT_ENFORCED/ENVIRONMENT_IDENTITY_MISMATCH` and rollout `ABSTAIN/PERFORMANCE_NOT_ENFORCED`.

The checked-in v1 descriptor records candidate counters of `9_480` operation units and `2_280`
logical allocation units, but no independent matched-environment serial-run artifacts are retained.
The former three-entry list repeated those same constants and was not three measurements. Baseline
measurement evidence is therefore `DRAFT/UNVERIFIED`, with zero verified independent runs. Until
three real, independent, auditable runs are captured in the exact frozen environment and reviewed,
the performance gate remains `NOT_ENFORCED` and rollout remains
`ABSTAIN/PERFORMANCE_NOT_ENFORCED`. This descriptor makes no wall-time, heap-byte, battery, or
thermal claim.

Each adapter identity also includes an embedded normalized-UTF-8 SHA-256 binding for the production
source implementation it exercises. `ProductionEvalRuntimeEnvironmentTest` recomputes those
snapshots from the checkout; production/runtime code never reads repository paths. A bound source
change therefore fails the contract until its digest and reviewed manifest are explicitly
re-baselined instead of relying on a hand-written implementation label alone.

Operation/allocation units used by the production CI gate are the exact sum returned by actual
component adapters; harness aggregation work is excluded from this comparison and remains a
separate report counter. Logical allocations are a reproducible object/row proxy, not claimed JVM
heap bytes. Recorded trace latency may appear in reports but is not a live CI gate.

## Threshold freezing and re-baselining

1. Freeze runner image, OS/architecture, JDK vendor/major/VM, build inputs,
   corpus/plan/manifest digests, and background load.
2. Run the identical production-component adapters independently and retain each auditable
   operation/allocation artifact; repeated constants are not run evidence.
3. Review variance, outliers, and counter semantics; fix nondeterminism before selection.
4. Record the reviewed baseline identity and distribution artifact.
5. Never infer or widen the frozen 10%/15% ceilings from a failing change.
6. Re-baseline only when environment or manifest identity changes, retaining prior rationale.

`ProductionLearningEvaluationCiEntry.evaluate(adapters, baseline, currentEnvironmentDigest)` is the
callable CI boundary. `ProductionLearningEvaluationCiTest` obtains that digest only from
`ProductionEvalRuntimeEnvironment.capture()`, not from the frozen baseline. It runs the fixed
four-arm matrix, consumes counters returned by adapters, requires the observed digest to equal the
baseline environment, evaluates the manifest-bound baseline, and returns a redacted digest-bound
artifact. Default adapters, a missing baseline, a missing/unbound build identity, or a nonmatching
runtime yield `ABSTAIN`; they never pass.

## Managed emulator boundary

A managed disposable emulator may measure Room/FTS/migration/process-recovery behavior. Emulator
results do not support a real-device battery or thermal claim.

## Energy boundary

Energy remains `UNMEASURED` without a dedicated, wipeable Pixel 6-or-newer class device with
reliable ODPM and a frozen same-device protocol. The JVM production-component gate has no energy
adapter and always emits `dedicated_odpm_device_used=false`.

The user's Honor AAK-AN00 is a production primary phone. It is excluded from ADB validation,
connected Android tests, instrumentation, UIAutomator, test APKs, energy experiments and thermal
experiments. Its absence never becomes a zero-energy result; artifacts state `energy=UNMEASURED`
and `primary_honor_device_testing_prohibited=true`.

Managed-emulator and Android runtimes do not impersonate the reviewed JVM performance
environment. Their observed descriptors therefore leave this performance gate `NOT_ENFORCED`;
their Room/FTS/migration/recovery attestations remain separate evidence.

## Checked-in execution entries

- Draft/unverified descriptor:
  `docs/agent-learning/baselines/p5-production-components-baseline-v1.json`.
- Candidate identity/counter implementation binding: `FrozenProductionEvalBaselineV1`; it is not
  verified run evidence.
- JVM CI gate: `:app:p5ProductionEvaluationGate` (redacted aggregate only; no APK task).
- Disposable Room source compile: `:app:compileP5ManagedDeviceVerificationSources`.
- Optional disposable emulator: `p5DisposablePixel6Api35DebugAndroidTest`, API 35 `aosp-atd`.
  It is configured but is not downloaded or run by either CI gate above.
