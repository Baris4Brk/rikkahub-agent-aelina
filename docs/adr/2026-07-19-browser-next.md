# Browser Next P0 ADR

Status: implemented incrementally on top of the existing dirty v181 workspace.

## Fixed decisions

- Local AI browser calls with a conversation owner use a per-conversation off-screen WebView and
  never start `BrowserActivity`.
- The foreground browser owns persistent user tabs. The visible tab is independent from the
  off-screen AI page; the tool route is selected by a coroutine-propagated conversation owner.
- Browser UI state is immutable (`BrowserTabsState`); WebViews remain inside `BrowserTabManager`.
- Room 34 adds only `browser_bookmarks` and `browser_history`; no cookies are exported.
- History is foreground main-frame HTTP(S) only, sanitized for OAuth/token/signature query values,
  deduplicated within 30 seconds and trimmed to 2,000 rows.
- Desktop UA is stored per tab. SSL continuation is foreground-only and one-shot. Foreground JS
  alert/confirm/prompt uses an explicit Compose dialog; silent pages cancel dialogs rather than
  blocking an AI tool indefinitely.
- A WebView profile is selected before settings are configured. Local shared profile falls back to
  the platform default when Multi Profile is unavailable; remote/ephemeral calls fail closed.

## Implemented seams

- `BrowserSessionCoordinator` and `InMemoryBrowserSessionCoordinator`: JVM-verifiable page/lease
  ownership, selected-vs-controlled tabs, close ordering and completed-page hibernation policy.
- `BrowserToolInvocationScope` + `BrowserControllerHandle`: legacy browser tools route to the
  owning headless session without exposing WebView instances to callers.
- `BrowserTabManager` + `BrowserNextView`: persistent foreground tabs, library UI, desktop mode,
  SSL and JavaScript dialog handling.
- `BrowserLibraryDao` + `MIGRATION_33_34`: Room-backed bookmarks/history.
- `BrowserTaskService`: low-importance notification with view/stop actions for local silent work.

## Deliberately deferred

Semantic snapshot/ref, downloads/file chooser, site permissions, isolated process fallback,
relation to the Memory V2 module, and visual/remote browser drivers remain P1/P2. No connected
Android tests, instrumentation, ADB install, data restore or device mutation is part of this change.
