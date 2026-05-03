## NUC-018: Issue closure intent against an OPEN account on operator authority

**Persona:** Eddie from Enterprise

**Story:**
As Eddie from Enterprise,
I want to issue closure intent against an `OPEN` account on operator authority, with the operator's identity carried as attribution on the resulting state change and event,
so that an operator handling an exceptional situation — a bereavement, a fraud determination, a regulatory direction — can close the account through Nucleus when the configurer's normal flow is not appropriate or not available, with a complete audit trail of who initiated the closure.

**Background:**
Closure intent flows through the same operation surface for operator-initiated and configurer-initiated cases. The operation's domain semantics are identical to those covered in NUC-017: the account transitions to `PENDING_CLOSURE` and `AccountClosureIntended` is emitted. The distinguishing requirement of the operator path is attribution: every state change initiated by Eddie must be attributable to the named operator, supplied via `X-Client-ID`. Nucleus records the operator identity on the affected aggregate and on the emitted event. The operator-path acceptance criteria are limited to the attribution requirement and to confirming that the same intent semantics apply — they do not re-establish the rejection cases for non-`OPEN` accounts (which are covered by NUC-017's rejection scenarios; the same rules apply regardless of whether Cameron or Eddie is the actor).

**Scenarios:**

### Scenario: An operator-initiated close instruction is accepted with operator attribution recorded on the event

```gherkin
Given an account exists with status OPEN, account identifier "A-1"
And the operator "OP-77" is acting on operator authority
When Eddie issues a close instruction against account "A-1" supplying "OP-77" as the operator identity via X-Client-ID
Then the instruction is accepted synchronously
And the status of account "A-1" is PENDING_CLOSURE
And AccountClosureIntended is emitted carrying account identifier "A-1", the intent timestamp, and operator attribution "OP-77"
```

### Scenario: An operator-initiated close instruction without an operator identity is rejected

```gherkin
Given an account exists with status OPEN, account identifier "A-1"
When Eddie issues a close instruction against account "A-1" without supplying an operator identity via X-Client-ID
Then the instruction is rejected
And the rejection states that operator attribution is required for this operation
And the status of account "A-1" is unchanged
And no AccountClosureIntended event is emitted
```

**Out of Scope:**
- Re-statement of the lifecycle rejection rules (rejection on `PENDING_CLOSURE`, rejection on `CLOSED`, idempotency on re-submission with the same key) — these rules apply identically to operator-initiated closure and are established in NUC-017.
- Compound operator closure operations that bundle the close instruction with restrictions, correcting entries, or parameter changes — those are API augmentations deferred per the architecture document.
- The mechanism by which Eddie's tooling enforces a second-operator authorisation policy where required — that is upstream of Nucleus.
- Distinct downstream event types for operator-initiated closures — the architecture commits to the same `AccountClosureIntended` event with operator attribution; no separate event type is introduced.

**Open Questions:**

The architecture's "Compound closure operations" section anticipates API augmentations exposed for recognised combinations of operator actions (e.g. a "regulatory closure" endpoint) but defers their introduction. Whether the basic operator-initiated close instruction in this story should be exposed via a separate endpoint from the configurer instruction — to enforce attribution at the route level and to carry a distinct audit-event type per Eddie's constraint that remediation should be distinguishable from routine processing — is not explicitly settled in the Account context's domain model. Left open.

**Guidance for the tdd-implementor.** As with the analogous question on NUC-016, whether wrapping APIs (a dedicated operator-closure route, a regulatory-closure compound endpoint, or any other higher-level surface) are required is not a concern of the account API directly at this time. The Account context exposes the closure-intent operation with attribution carried via `X-Client-ID`; that is sufficient to satisfy the domain semantics for both configurer- and operator-initiated cases. If a wrapping API becomes necessary — for route-level attribution enforcement, for a distinct audit-event type, or to compose a recognised remediation action — it can be introduced separately as its own story without revisiting the underlying account API contract.