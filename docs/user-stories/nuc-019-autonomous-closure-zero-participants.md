## NUC-019: Autonomously complete closure when no preconditions remain outstanding

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want Nucleus to carry an account from `PENDING_CLOSURE` through to `CLOSED` autonomously once all preconditions are satisfied,
so that I can issue one close instruction at intent and consume `AccountClosed` from the event stream when the closure is complete, without polling Nucleus or issuing a separate "finalise closure" instruction whose timing I would have to determine.

**Background:**
The `PENDING_CLOSURE` → `CLOSED` transition is autonomous (ADR-028) and is gated by two layers: an asynchronous precondition projection updated by events from contributing contexts, and a synchronous pre-close participant phase (ADR-031) in which each pre-close participant asserts a predicate as a final check. The transition commits only if every pre-close participant asserts positively. Both layers are required structurally even when no contributing contexts have yet been wired in. With zero asynchronous precondition events expected and zero pre-close participants registered, the projection is satisfied at the moment of intent and the pre-close phase commits with no assertions to evaluate; the account transitions to `CLOSED` and `AccountClosed` is emitted. The participant invocation point must exist in the lifecycle composite even when empty so that participants can be added without re-architecting the lifecycle.

**Scenarios:**

### Scenario: A PENDING_CLOSURE account with no outstanding preconditions transitions autonomously to CLOSED

```gherkin
Given an account exists with status PENDING_CLOSURE, account identifier "A-1"
And no pre-close participants are registered
And the precondition projection for account "A-1" reports all known asynchronous preconditions as satisfied
When Nucleus initiates the close attempt for account "A-1"
Then the status of account "A-1" is CLOSED
And AccountClosed is emitted carrying account identifier "A-1", the closure timestamp, and the configurer attribution carried at intent
And the closure timestamp is set on account "A-1" and is immutable thereafter
```

### Scenario: The participant invocation point is exercised in the lifecycle composite even when no participants are registered

```gherkin
Given an account exists with status PENDING_CLOSURE, account identifier "A-1"
And no pre-close participants are registered
When Nucleus runs the close attempt for account "A-1"
Then the pre-close participant phase is entered with zero participants to invoke
And the transition commits without any predicate assertion having been evaluated
```

### Scenario: A CLOSED account is structurally immutable

```gherkin
Given an account exists with status CLOSED, account identifier "A-1"
When any further state-changing operation is attempted on account "A-1"
Then the operation is rejected
And the status of account "A-1" remains CLOSED
And the account's record continues to be available for query
```

**Out of Scope:**
- The shape and reconciliation semantics of the precondition projection (which event types it subscribes to, how out-of-order or replayed events are handled) — that mechanism-level detail is the residual concern of ADR-028 and is not material to the scenarios above.
- The introduction of any specific asynchronous precondition (settlement clearance, accrual finalisation, balance zeroing) — those depend on the contributing contexts and are out of scope here. NUC-020 covers the case where a pre-close participant is registered and asserts negatively.
- Re-opening or re-instating a closed account — closure is terminal and there is no domain operation that reverses it.
- The retention period of a closed account's record — that is governed by the bank's data retention policy, upstream of Nucleus.

**Open Questions:**

How the close attempt is triggered when zero asynchronous preconditions are expected — whether the projection signals immediate satisfaction at the moment of intent, or whether the close attempt is dispatched directly inside the intent transaction with the pre-close phase running in-line — is not material to the implementation proceeding. Implementation may select either mechanism.

**Guidance for the tdd-implementor.** Where there are no asynchronous preconditions to await, the preferred direction is to proceed directly to the pre-close phase inside the intent transaction rather than to gate progress on the absence of events. Non-presence is difficult to evaluate after-the-fact: a projection that "reports satisfaction" because no events were ever expected is structurally indistinguishable from one that is waiting for events that have not yet arrived, and the latter is the dangerous case. Direct progression where no contributors are registered avoids that ambiguity and is more efficient. Notes recording the chosen mechanism — and the conditions under which it would be revisited as real asynchronous preconditions are introduced — should be captured during the implementation session so that ADR-028's residual mechanism-level concerns can be settled with the implementation context in hand.