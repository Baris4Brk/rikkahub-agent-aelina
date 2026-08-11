# P0-2 Memory Top-K

Goal: preserve the existing `__global__` versus Assistant-exclusive scope while replacing full
prompt injection with bounded FTS retrieval.

Status: IMPLEMENTED; OFFLINE MIGRATION VERIFIED

Delivered:

- Database v30 adds an FTS5 projection, synchronization triggers, metadata columns, and v29->v30
  migration without replacing the existing memory table or public CRUD surface.
- `MemoryRetriever` owns scope selection, FTS failure degradation, ranking, duplicate removal,
  Top-K limiting, and a 6,000-character prompt budget.
- Global mode still reads only `__global__`; Assistant mode still reads only that Assistant's
  exclusive scope. The two scopes are not merged implicitly.
- Prompt injection uses the current user query and degrades to an empty memory block if FTS is
  unavailable, rather than loading every stored memory.
- Production Room startup and migration tests share `createAppSQLiteOpenHelperFactory`, so both
  use the bundled SQLite FTS5 runtime and the `simple` tokenizer extension. This avoids testing
  against the device-dependent Android framework SQLite implementation.

Verification:

- `MemoryRetrieverTest` and `MemoryPromptTest` passed in the final 1,574-test JVM regression.
- Room schema `30.json` is checked in.
- A sanitized offline migration fixture migrated to `user_version=30`; the resulting
  `MemoryEntity` columns, FTS5 table, `simple` tokenizer, three synchronization triggers, and Room
  identity were inspected from the post-install snapshot.
- Isolated migration verification ran only `Migration_29_30_Test` and passed, proving v29 data
  preservation, metadata defaults, and FTS projection backfill.
