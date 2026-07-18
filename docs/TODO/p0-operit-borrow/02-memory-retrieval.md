# P0-2 Memory Top-K

Goal: preserve the existing `__global__` versus Assistant-exclusive scope while replacing full
prompt injection with bounded FTS retrieval.

Status: IMPLEMENTED; DEVICE MIGRATION PENDING FINAL PASS

Delivered:

- Database v30 adds an FTS5 projection, synchronization triggers, metadata columns, and v29->v30
  migration without replacing the existing memory table or public CRUD surface.
- `MemoryRetriever` owns scope selection, FTS failure degradation, ranking, duplicate removal,
  Top-K limiting, and a 6,000-character prompt budget.
- Global mode still reads only `__global__`; Assistant mode still reads only that Assistant's
  exclusive scope. The two scopes are not merged implicitly.
- Prompt injection uses the current user query and degrades to an empty memory block if FTS is
  unavailable, rather than loading every stored memory.

Verification: `MemoryRetrieverTest` and `MemoryPromptTest` passed. Room schema `30.json` and
`Migration_29_30_Test` are present; the instrumentation migration test is reserved for the backed-up
device installation step.
