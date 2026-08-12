# Agent Learning Runtime privacy and outbound-data contract

Status: implementation candidate; all remote learning is disabled by default.

Date: 2026-08-12

## Data classes

| Class | Examples | Allowed persistence |
|---|---|---|
| L0 | Stable opaque IDs, enum codes, counters, revisions, digests | Narrow main outbox, Learning DB, bounded diagnostics |
| L1 | Redacted bounded feature/lesson summaries | Android app-private Learning DB with retention |
| L2 | Message text, tool input/output, file contents | Existing authority source only; temporary scoped reads |
| L3 | Credentials, secret plaintext, private CoT/reasoning | Never enters ALR |

“App-private” does not claim SQLCipher or encryption at rest. Adding encryption requires a separate
design for Keystore keys, rotation, migrations, backup/restore, corrupt-key recovery and tests.

## Durable-data rules

The main outbox, job rows, errors, diagnostics and exported health reports must not contain:

- prompts, messages, private reasoning or raw model responses;
- tool arguments or results;
- URIs, absolute paths or file bodies;
- credentials, tokens, headers or secret references that reveal values;
- raw assistant, authority-subject, conversation, message or source IDs in diagnostics.

Outbox and job schemas use typed bounded columns. Arbitrary `payload_json`, `correlation_json`, or
exception text are prohibited. Unknown event codes are preserved with their schema version and
decode state; their payload is not copied into an unbounded escape field.

Errors persist allowlisted codes only. Logs may contain a generation-scoped opaque trace and coarse
scope kind, never source text or stable cross-generation user identifiers.

## Temporary source reads

Background work resolves source text only through a scoped resolver with source ID, expected
revision/version token and tombstone state. It checks validity before read, before provider call and
before commit. Remote requests replace authority IDs with job-local aliases such as E1/E2.

Resolved text is bounded and held only in memory for the current attempt. Cancellation and normal
completion release references in `finally`. It is never copied into a job, error, cache identity,
diagnostic or outbox row.

## Remote reflection

`learningAllowRemoteReflection` defaults to false. No code may silently fall back to an arbitrary
remote provider. Before enabling it, UI must disclose:

- selected provider and model;
- which redacted field categories leave the device;
- expected request frequency, token use and cost;
- scope and how to revoke consent.

The claimed job freezes provider/model/config generation. Credentials resolve only at execution
time and are never persisted by ALR. A content-free receipt may store provider/model identity,
field categories, token/cost counts and time. Results retain the producer identity after settings
change and never masquerade as output from the new model.

## Prompt-injection rules

Source text and learned policy are always data. XML/JSON closing markers, placeholders, “ignore
previous instructions”, fake evidence IDs and tool requests cannot alter system rules, scope,
grants, tool schemas or permissions. Every model output is strict-parsed and locally validated
against an evidence allowlist before it becomes a candidate.

## Retention and deletion

Minimum retention is required before P1 stores summaries or lessons. TTLs are centralized and use
an injectable clock. Source privacy deletion immediately removes eligibility and the corresponding
FTS projection, clears permitted summaries, and retains only the smallest tombstone/digest needed
to prevent resurrection. Pending work cannot retain an in-memory snapshot after cancellation.

Irreversible erasure requires explicit local confirmation and is not exposed to model tools.

## Verification

- A forbidden corpus covers credentials, URLs, Windows/Unix paths, XML/JSON delimiters,
  placeholders, control characters, very long strings and Unicode edge cases.
- Database rows, diagnostics files, exports, logs captured by tests and provider request fixtures
  have zero forbidden-corpus matches.
- Remote-off tests prove no provider fallback or outbound attempt.
- Source deletion, revision races and cancellation prove no stale snapshot commit or resurrection.
