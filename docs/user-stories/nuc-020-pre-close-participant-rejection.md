## NUC-020: Abort closure when a pre-close participant asserts a negative predicate

**Persona:** Casey the Customer

**Story:**
As Casey the Customer,
I want Nucleus to abort the closure of my account when a final synchronous predicate check identifies a condition under which closure would harm me — most importantly, that funds remain on the account that closure would strand,
so that my money is not lost or made unrecoverable as a result of an erroneous or premature close instruction issued on my behalf.

**Background:**
The `PENDING_CLOSURE` → `CLOSED` transition runs synchronously through a pre-close participant phase (ADR-031) in which each registered pre-close participant asserts a predicate. The transition commits only if every pre-close participant asserts positively. The canonical pre-close predicate is the Ledger context's zero-balance assertion: closure makes the account record immutable, and an account holding commercial bank money would have those funds stranded by closure. Because the Ledger context is not yet built, the scenarios in this story exercise the pre-close phase via a stub participant whose assertion can be controlled by the test. The behaviour scenarios assert is that of any pre-close participant: a negative assertion aborts the transition, the account remains in `PENDING_CLOSURE`, an `AccountUncloseable` event is emitted carrying the identity of the participant that asserted negatively and the reason it gave, and subsequent precondition events may re-trigger the close attempt.

The persona for this story is Casey because the value being protected — funds not being stranded by closure — is a customer outcome, even though the actor of the close attempt is Nucleus itself acting on a configurer's intent. Cameron remains the configurer who issued the close instruction; Casey is the stakeholder whose interest the predicate protects. Eddie is the principal downstream consumer of `AccountUncloseable`: an operator handling a customer query about a close that has not completed needs to see why and on whose predicate it has stalled.

**Scenarios:**

### Scenario: Closure is aborted when a pre-close participant asserts negatively, and AccountUncloseable is emitted

```gherkin
Given an account exists with status PENDING_CLOSURE, account identifier "A-1"
And a pre-close participant identified as "ledger.zero-balance" is registered
And the participant is configured to assert negatively with reason "non-zero balance on address X" when invoked
When Nucleus initiates the close attempt for account "A-1"
Then the close attempt aborts
And the status of account "A-1" remains PENDING_CLOSURE
And no AccountClosed event is emitted
And the closure timestamp on account "A-1" is not set
And AccountUncloseable is emitted carrying account identifier "A-1", participant "ledger.zero-balance", reason "non-zero balance on address X", and the timestamp of the aborted attempt
```

### Scenario: A subsequent close attempt commits when the participant asserts positively

```gherkin
Given an account exists with status PENDING_CLOSURE, account identifier "A-1"
And a pre-close participant is registered
And the participant has previously asserted negatively, leaving account "A-1" in PENDING_CLOSURE
And the participant is now configured to assert positively when next invoked
When Nucleus re-initiates the close attempt for account "A-1"
Then the close attempt commits
And the status of account "A-1" is CLOSED
And AccountClosed is emitted carrying account identifier "A-1" and the closure timestamp
```

### Scenario: A negative assertion by any pre-close participant aborts closure and the emitted event identifies that participant

```gherkin
Given an account exists with status PENDING_CLOSURE, account identifier "A-1"
And two pre-close participants are registered, the first identified as "P-1" and the second as "P-2"
And the first participant is configured to assert positively
And the second participant is configured to assert negatively with reason "R-2"
When Nucleus initiates the close attempt for account "A-1"
Then the close attempt aborts
And the status of account "A-1" remains PENDING_CLOSURE
And no AccountClosed event is emitted
And AccountUncloseable is emitted carrying account identifier "A-1", participant "P-2", and reason "R-2"
```

**Out of Scope:**
- The substantive predicate of the Ledger zero-balance assertion — Ledger is not yet built. This story uses a stub participant to exercise the pre-close phase shape; the canonical predicate will be implemented when Ledger comes online.
- The autonomous re-trigger mechanism that determines when a fresh close attempt should be made after a negative assertion — that is a question for the precondition projection's design (residual of ADR-028) and is out of scope here. The second scenario above describes the outcome of a re-attempt without prescribing how it is initiated.
- Operator override of a pre-close participant's negative assertion — the architecture does not contemplate this and the scenario set does not include it. Restrictions, correcting entries, and other compound operations may be applied via separate Nucleus operations to make the predicate assertable, but the predicate itself is not bypassable.
- Whether a positively-resolved subsequent close attempt should also emit a paired "uncloseable cleared" signal — the absence of further `AccountUncloseable` events combined with the eventual `AccountClosed` event is sufficient. No additional signal is required.
- The behaviour when multiple pre-close participants assert negatively in the same attempt — the scenarios in this story do not exercise that case. Whether the emitted event collects all negative assertions or short-circuits on the first is an implementation question to be settled when more than one participant is registered in production.

**Open Questions:**

None. A negative pre-close assertion emits an `AccountUncloseable` event carrying the account identifier, the identity of the participant that asserted negatively, the reason it gave, and the timestamp of the aborted attempt. This event is a new entry in the Account context's domain event set; the architecture document `docs/architecture/account.md` should be amended to record it alongside `AccountOpened`, `AccountClosureIntended`, `AccountClosed`, and `AccountStakeholderChanged` when this story is implemented.