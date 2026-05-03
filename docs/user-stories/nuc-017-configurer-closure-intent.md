## NUC-017: Issue closure intent against an OPEN account as a configurer

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to instruct Nucleus to close an `OPEN` account once and have it carry the closure through to completion autonomously,
so that I can express closure intent at the moment my own value stream determines closure is appropriate, without having to coordinate the timing of the actual closure or poll for completion.

**Background:**
Closure is a two-stage process: closure intent (`OPEN` → `PENDING_CLOSURE`) is the configurer's instruction, and closure completion (`PENDING_CLOSURE` → `CLOSED`) is Nucleus's autonomous fulfilment as preconditions are reported as satisfied. This story covers the intent stage. A close instruction is direct and synchronous: the account transitions to `PENDING_CLOSURE` and `AccountClosureIntended` is emitted carrying the account identifier, the intent timestamp, and the configurer's attribution. The instruction does not carry a categorisation of why closure is being instructed — that judgement is upstream of Nucleus. The instruction is idempotent against the (operation, idempotency key) pair: a re-submission with the same key returns the original response without effect. A close instruction issued against an account that is not `OPEN` is rejected.

**Scenarios:**

### Scenario: A close instruction against an OPEN account is accepted and the account transitions to PENDING_CLOSURE

```gherkin
Given an account exists with status OPEN, account identifier "A-1"
When Cameron issues a close instruction against account "A-1" with idempotency key "IK-C-001"
Then the instruction is accepted synchronously
And the status of account "A-1" is PENDING_CLOSURE
And AccountClosureIntended is emitted carrying account identifier "A-1", the intent timestamp, and Cameron's configurer attribution
```

### Scenario: A re-submission of the close instruction with the same idempotency key returns the original response

```gherkin
Given Cameron has successfully issued a close instruction against account "A-1" with idempotency key "IK-C-001"
When Cameron re-submits a close instruction against account "A-1" with idempotency key "IK-C-001"
Then the instruction is accepted
And the status of account "A-1" is PENDING_CLOSURE
And no second AccountClosureIntended event is emitted
```

### Scenario: A close instruction against a PENDING_CLOSURE account with a new idempotency key is rejected

```gherkin
Given an account exists with status PENDING_CLOSURE, account identifier "A-1"
When Cameron issues a close instruction against account "A-1" with a new idempotency key
Then the instruction is rejected
And the rejection states that closure intent cannot be issued against a PENDING_CLOSURE account
And the status of account "A-1" is unchanged
And no AccountClosureIntended event is emitted
```

### Scenario: A close instruction against a CLOSED account is rejected

```gherkin
Given an account exists with status CLOSED, account identifier "A-1"
When Cameron issues a close instruction against account "A-1" with a new idempotency key
Then the instruction is rejected
And the rejection states that closure intent cannot be issued against a CLOSED account
And the status of account "A-1" is unchanged
```

**Out of Scope:**
- The autonomous completion of closure (the `PENDING_CLOSURE` → `CLOSED` transition) — covered in NUC-019 and NUC-020.
- Pre-close participant invocation at the intent stage — the architecture allows prepare-to-close participants to contribute work atomically with the intent transition, but the simple zero-participant case is the only case for which a story is written here. Subsequent stories may add participants as their owning contexts come online.
- Compound closure operations that bundle the close instruction with restrictions, parameter changes, or correcting entries — those are API augmentations whose introduction is deferred per the architecture document.
- Operator-initiated closure — covered in NUC-018, which uses the same closure-intent operation but is distinguished by operator attribution and a different value statement.

**Open Questions:**
None.