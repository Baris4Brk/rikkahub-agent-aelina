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
