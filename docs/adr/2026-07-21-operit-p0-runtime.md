# Operit-Informed P0 Runtime ADR

Status: implemented incrementally in the P0 workspace; final serial verification remains required.

## Context

Operit was reviewed at commit `723c313d` as an architectural reference for tool execution,
managed surfaces, and extension points. Its GPL/LGPL-adjacent implementation details are not a
drop-in dependency for RikkaHub's Android application and security model.

## Decisions

- Tool execution is owned by `ToolRuntime`. Each invocation resolves effects, resource keys,
  approval requirements, and cancellation capability from the actual arguments. Unknown model
  tools fail closed.
- Existing Startable adapters are preferred over a new generic shell launcher. Workspace,
  Termux, SSH, Shizuku, and local-process cancellation remain bounded by their own adapters.
- Read-only parallelism is limited to consecutive, independently classified calls. Any write,
  unknown operation, shell, communication operation, or conflicting Browser/Display/file/runtime
  resource creates an ordering barrier.
- `ContextBroker` is opt-in, freezes once per run, and contributes volatile system context only.
  It never writes screen, OCR, notification, or foreground-app content to messages, memory, or
  diagnostics. Remote, keyguard, cron, workflow, and sub-agent surfaces fail closed for screen
  collection.
- Display automation is lease-based. A session is bound to assistant, conversation, run, origin,
  and capabilities; a bare non-primary display identifier is never accepted and no failure falls
  back to Display 0.
- Managed execution uses the existing Workspace process manager plus bounded Termux and SSH
  adapters. The ledger records runtime identity and state but never passwords, private keys, or
  command payloads.
- Plugin Runtime Lite uses a separate process, short-lived WebView, per-plugin virtual HTTPS
  origin, a restricted Host RPC, and reviewed manifests. Third-party plugin tools are unknown and
  globally serial until every-call approval. Repeated plugin failures quarantine the plugin.

## Operit Borrowing Boundary

RikkaHub borrows only the general ideas of a registry, declared manifests, lifecycle-aware
sessions, and hook boundaries. It does not copy Operit's source code, static global session maps,
open JavaScript bridges, unrestricted file/network settings, `app_process` launch path, JAR
handoff, or binder broadcast scheme. In particular, RikkaHub does not accept SSL errors, expose
raw tool inputs/results to plugins, or make a plugin's supplied "safe" claim authoritative.

## Diagnostics and Release Gate

Runtime diagnostics expose only aggregate counts for Context Broker source/omission outcomes,
Display session lifecycle, managed-execution termination uncertainty, plugin review/quarantine,
and security-descriptor coverage. They intentionally contain no observed device text or command
content.

The release gate is local JVM tests, Kotlin compilation, Android-test-source compilation, APK
assembly, manifest inspection, and signature/hash inspection. No connected Android tests,
instrumentation, test APK, data reset, or ADB installation is part of normal validation for the
Honor primary phone.
