# P5-002 Vector/Hybrid readiness gate

Status: **NOT READY / DISABLED** (2026-08-13)

This is an optional upgrade gate, not an implementation claim. Production continues to use the
exact/FTS path and `SettingsLearningFeatureFlagSource` keeps `vector=false`.

The executable gate is `VectorRetrievalGate`. It requires all of the following before it can return
`ShadowEligible`:

1. a replayable exact/FTS baseline digest and a distinct failure-slice report digest;
2. exact embedding artifact, tokenizer, and preprocessing identities;
3. a versioned index schema plus rebuild contract;
4. timeout, cancellation, and `LearningResourceGovernor` contracts;
5. a tested feature-off fallback to exact/FTS; and
6. a dedicated-device latency/memory/thermal/battery baseline digest.

The repository currently has no approved embedding artifact and no dedicated-device energy
baseline. Those absent facts keep the gate disabled. Managed-emulator results cannot be presented
as battery evidence, and the user's Honor AAK-AN00 must not run instrumentation, test APKs, or ADB
validation. Even after all evidence exists, the result authorizes only a three-way shadow comparison
(exact/FTS, vector, hybrid); it does not authorize Policy injection, RRF/MMR, a cross-encoder, or a
rollout expansion.
