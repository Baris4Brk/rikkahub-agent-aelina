# ADR: ordinary chat uses observable provider-only pruning

- Date: 2026-08-12
- Status: Accepted
- Scope: ordinary provider requests in `GenerationHandler`

## Context

The hard context gate can sometimes make a request fit without changing the stored conversation:
it can remove historical reasoning from the provider copy, drop an oldest completed turn as one
atomic group, or lower the requested output allowance. Previously the gate produced a trace for
these changes, but `GenerationHandler` did not explicitly state whether ordinary chat accepted or
rejected that projection. A helper named `requireLosslessProviderContext` existed only in tests,
which made the production policy easy to misunderstand.

Provider-advertised window metadata remains diagnostic-only. The enforced window is the minimum
of the user policy, the application absolute cap, and any explicitly trusted provider/local
capability.

## Decision

Ordinary chat deliberately selects `OBSERVABLE_PRUNING` through
`ORDINARY_GENERATION_CONTEXT_PROJECTION_POLICY`.

Both provider-boundary passes apply this policy explicitly:

1. `initial`, before message transformers;
2. `final`, after volatile context and transformers have been applied.

Observable pruning has these invariants:

- it changes only the immutable provider projection; persisted `UIMessage` values are not edited;
- system messages, manual-compression summaries, and the active turn remain protected;
- historical reasoning may be removed from the provider copy;
- an old completed turn is removed as a whole, never as a partial user/assistant/tool exchange;
- output may be clamped only to the bounded value returned by the hard gate;
- every change remains visible in `ProviderContextGateTrace` and request diagnostics.

`STRICT_LOSSLESS` remains an explicit optional policy. It throws
`ProviderContextRequiresExplicitAdjustmentException` if any reasoning, completed turn, message, or
requested output would change. It does not weaken the hard gate.

If the protected fixed prefix or active turn cannot fit, `ProviderContextOverflowException` is
always thrown regardless of the selected projection policy. In `GenerationHandler` both context
passes happen before `DefaultProviderTurnRunner` and before its `beforeAttempt` memory-last-access
callback. Therefore this hard rejection performs zero provider calls and zero last-access writes.

## Consequences

- Long conversations can continue with a bounded, inspectable provider projection instead of
  failing merely because removable history is large.
- Diagnostics must be retained: silent pruning without its trace is a correctness regression.
- Changing ordinary chat to strict lossless is a product-policy change, not a refactor. It requires
  changing the single policy constant and keeping the two explicit stage applications.
- Unit tests at the preparer seam verify provider-only immutability, atomic turn removal, reasoning
  removal, strict rejection, and both protected-overflow kinds. A fuller `GenerationHandler`
  integration harness should additionally spy on the real provider and `MemoryRepository`; the
  current JVM suite cannot construct the complete Android/Koin handler graph cheaply.
