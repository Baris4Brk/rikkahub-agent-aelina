# P1 shadow retrieval gate v1

Frozen on 2026-08-12 for the P1-only, provider-isolated shadow path.

- Corpus: `app/src/test/resources/learning_eval/zh_policy_shadow_v1.jsonl` (10 Chinese cases).
- Recall thresholds: Recall@1 >= 0.80, Recall@3 >= 0.95, Recall@5 = 1.00.
- Safety thresholds: cross-scope hits = 0; stale-source hits = 0; schema-invalid hits = 0.
- Determinism: identical request/candidate state must reproduce identical ordering.
- Boundaries: at most 8,192 raw query chars, 64 normalized terms, 128 combined candidates,
  20 selected candidates, and 500 ms configured latency budget.

This gate measures local exact/FTS shadow retrieval only. It does not send candidates to
`GenerationHandler`, does not alter provider-bound bytes, and does not measure utility. Energy and
causal utility remain `UNMEASURED` in P1.

## Production lifecycle and durable observation contract

- `p1-shadow-retrieval-gate-v1` is also the exact build-time admission identity for the production
  `CANDIDATE -> SHADOW` transition. No confidence, utility, alpha, or runtime score threshold is
  consulted. Candidate creation has already enforced its versioned distinct-Episode minimum;
  admission re-reads all evidence and requires exact agreement with persisted support/polarity.
- Stage D derives `policy-shadow-request-v1:<sha256>` only from the frozen scope, consuming
  Assistant, lineage, branch anchor ID/revision, logical run, and task signature. It never persists or hashes
  the query, Policy text, prompt, model output, provider response, or outcome.
- Retrieval result revalidation, artifact/content/lifecycle fences, any `CANDIDATE -> SHADOW` CAS,
  its append-only Policy revision, the request receipt, and all selected item rows commit in one
  `LearningDatabase` transaction. The request primary key makes crash/replay idempotent.
- `learning_policy_shadow_observations` and
  `learning_policy_shadow_observation_items` are P1-only. They record would-recall counts/rank and
  exact Policy identities, never injection, dispatch, or answer effect. P2 actual pipeline
  retrieval/injection/dispatch remains in `learning_policy_exposures` and is displayed separately.
- Both tables follow the Policy-exposure retention cutoff, exact-scope erase, cold stream reset,
  and foreign-key deletion lifecycle. `observedUtilityDelta` and `utilityUncertainty` remain null
  and Policy `usageCount` remains zero throughout Stage D.
