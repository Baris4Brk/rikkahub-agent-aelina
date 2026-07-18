# P0-4 Bounded deep research

Goal: coordinate two to five restricted child runs without introducing a persistent DAG runtime.

Status: IMPLEMENTED

Delivered:

- `research_start`, `research_status`, and `research_cancel` operate on an owner-scoped in-memory
  run containing two to five independent child tasks.
- Every child receives only `search_web`, `scrape_web`, and `web_fetch`, a research-only prompt,
  bounded timeout/trips, and coordinator-only parent completion.
- Results support success, partial success, failure, cancellation, URL de-duplication, and compact
  per-child reports (4 KiB on the tool surface; 8 KiB internally).
- Emergency Stop atomically pauses new research, cancels active children, and prevents a concurrent
  dispatch or late completion from reviving a cancelled run.
- Cancelling `research_start` while a later child is being dispatched compensates every child that
  was already accepted in a `NonCancellable` cleanup, marks the research run cancelled, suppresses
  coordinator completion, and then rethrows the original cancellation.
- Persistent DAGs, retries, claim ledgers, and process recovery remain intentionally P1 scope.

Verification: all seven `ResearchCoordinatorTest` behaviours passed, including concurrent Emergency
Stop and cancellation-during-dispatch coverage.
