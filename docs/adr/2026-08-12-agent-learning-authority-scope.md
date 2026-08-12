# Agent Learning Runtime authority and scope

Status: accepted for the unpublished v46 implementation; production effects disabled by default.

Date: 2026-08-12

## Context

RikkaHub already has several state owners. Dreaming-X owns durable personal memory and identity.
Execution and command persistence own run outcomes. ToolExperience owns reviewed, exact-tool
experience for the current second-user authority. Workflow and SkillManager own executable
artifacts. The Agent Learning Runtime (ALR) must learn task advice without duplicating or silently
overriding those authorities.

PRE-000 froze the Dreaming-X working tree on 2026-08-12 at 08:52:23 Asia/Shanghai. The concrete
content hashes and runtime facts are recorded in
`docs/agent-learning/PRE-000-baseline-manifest-2026-08-12.md`. Room v46 is unpublished, so the
Learning schema is merged into the existing `45 -> 46` migration without consuming v47. The exact
Dream-only v46 identity is retained as a narrow same-version import source; arbitrary v46
identities must not be stamped as current.

## Decision

### Single owners

| State | Authority | ALR may | ALR must not |
|---|---|---|---|
| Conversation/message | Existing conversation persistence | Reference stable IDs and revisions | Rewrite or copy full history |
| Command outcome | Authoritative command transaction | Consume terminal outbox events | Infer success from hooks |
| Tool outcome | Execution state/event transaction | Consume terminal events and schema identity | Override execution state |
| Personal identity/memory | Dreaming-X memory/revision/link stores | Read a future bounded public projection | Create a second profile or memory graph |
| Exact second-user tool experience | ToolExperience | Reference ID, revision and schema | Copy its body or infer absent failures as success |
| Task advice | ALR policy store | Own candidate/shadow/reviewed policy lifecycle | Promote advice to system or standing memory |
| Executable workflow | WorkflowRepository/WorkflowEngine | Promote an exact reviewed artifact through a saga | Create a second workflow runtime |
| Tool authority | ToolRunPreflight/ToolExecutionGate | Reuse the complete production gate | Expand or bypass capabilities |

No model output may write `MemoryEntity`, `MemoryLink`, `ToolExperience`, `WorkflowRepository`,
`SkillManager`, or a provider prompt directly.

### Learning scopes

The first release has exactly two scope kinds:

- `ASSISTANT(assistantUuid)`
- `AUTHORITY_SUBJECT(authoritySubjectId)`

There is no ALR `GLOBAL` scope. `useGlobalMemory` is a memory presentation choice and never widens
an ALR policy. `TaskSignature` is a retrieval feature, not an authorization boundary.

For a request from assistant A under authority subject S:

| Policy scope | Additional requirements | Eligible |
|---|---|---|
| `ASSISTANT(A)` | A has learning enabled and an exact grant | Yes |
| `ASSISTANT(B)` | None | Never |
| `AUTHORITY_SUBJECT(S)` | Request is S; A explicitly opted in; grant binds consuming A | Yes |
| `AUTHORITY_SUBJECT(T)` | T differs from S | Never |
| Missing, unknown or global | None | Fail closed |

Evidence belonging to assistant and authority-subject scopes is not aggregated into one policy.
Sharing creates a new candidate, artifact hash and grant; it never mutates a scope in place.
Authoritative SQL filters scope before `LIMIT`, followed by a defense-in-depth check during compile.

### Trust boundary

An approved policy is still volatile, untrusted contextual advice. Approval does not:

- turn it into a system/standing instruction;
- grant tools, files, network or Android capabilities;
- make it global;
- permit it to edit personal memory.

If the user wants a durable identity/preference instruction, it must go through the Dreaming-X
`USER_REVIEWED` authority path.

### Source validity

Persistent policy evidence requires a recoverable source revision/version token or tombstone.
Sources with no such token may produce a transient `UNKNOWN` observation only. `updatedAt`, current
content hashes and process-local IDs are not substitutes for an authoritative revision.

Message/conversation deletion, branch replacement, assistant deletion, ToolExperience revision,
and future feedback/schema changes need explicit authority-transaction outbox writers before their
event types are enabled. Hooks may wake consumers but are never durability boundaries.

### Failure behavior

ALR storage/model/retrieval failures fall back to the no-learning request. A failure in the main
authority transaction is reported as a real persistence failure; it is not hidden as an ALR
fail-open event. Missing scope, revision, grant, lineage, source, schema or authority always disables
the derived effect.

## Consequences

- ALR cannot use memory-global mode as a shortcut for policy sharing.
- Policy review UI must say “approve as contextual advice”.
- Workflow promotion needs a second, explicit enable action and still executes through the existing
  preflight/gate chain.
- Cross-scope tests are release blockers, not quality metrics.

## Verification

- Pure tests cover A/B assistants, same subject across different assistants, opt-in, revoked grant,
  missing scope and mixed evidence.
- Architecture tests forbid ALR dependencies on memory mutation, active Skill installation, real
  Workflow execution from a candidate, and unrestricted tool execution.
- Feature-off tests compare provider-bound requests and tool surfaces against the baseline.
