## NUC-016: Update the stakeholder identifier on an OPEN account

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to update the stakeholder identifier on an `OPEN` account,
so that I can reflect upstream identifier changes — a customer record merge in the customer management system, the resolution of a duplicate identifier — without having to close and reopen the account, which would import the upstream judgement of "same or different party" into Nucleus where it does not belong.

**Background:**
The stakeholder identifier on an account is an opaque value reference whose semantics are owned by the upstream customer management system. Nucleus assigns no meaning to the identifier and has no basis on which to declare that a change of identifier amounts to a change of account. The identifier may therefore be updated directly on `OPEN` accounts. Updates are rejected on `PENDING_CLOSURE` and `CLOSED`, consistent with the principle that closure intent freezes account attributes. A successful update emits `AccountStakeholderChanged` carrying the prior and new identifiers and the change timestamp. A submission whose new identifier equals the current identifier is a no-op and emits no event. Historical events and ledger entries preserve the stakeholder identifier in force at the time of their emission and are not retroactively reattributed.

**Scenarios:**

### Scenario: A stakeholder identifier change on an OPEN account is accepted and the change is emitted

```gherkin
Given an account exists with status OPEN, account identifier "A-1", and stakeholder identifier "STK-1001"
When Cameron updates the stakeholder identifier on account "A-1" to "STK-2002"
Then the update is accepted
And the current stakeholder identifier on account "A-1" is "STK-2002"
And AccountStakeholderChanged is emitted carrying account identifier "A-1", prior stakeholder identifier "STK-1001", new stakeholder identifier "STK-2002", and the change timestamp
```

### Scenario: A stakeholder identifier submission identical to the current value is a no-op

```gherkin
Given an account exists with status OPEN, account identifier "A-1", and stakeholder identifier "STK-1001"
When Cameron submits a stakeholder identifier update on account "A-1" to "STK-1001"
Then the submission is accepted
And the current stakeholder identifier on account "A-1" is "STK-1001"
And no AccountStakeholderChanged event is emitted
```

### Scenario: A stakeholder identifier change on a PENDING_CLOSURE account is rejected

```gherkin
Given an account exists with status PENDING_CLOSURE, account identifier "A-1", and stakeholder identifier "STK-1001"
When Cameron attempts to update the stakeholder identifier on account "A-1" to "STK-2002"
Then the update is rejected
And the rejection states that stakeholder identifier changes are not permitted on a PENDING_CLOSURE account
And the current stakeholder identifier on account "A-1" remains "STK-1001"
And no AccountStakeholderChanged event is emitted
```

### Scenario: A stakeholder identifier change on a CLOSED account is rejected

```gherkin
Given an account exists with status CLOSED, account identifier "A-1", and stakeholder identifier "STK-1001"
When Cameron attempts to update the stakeholder identifier on account "A-1" to "STK-2002"
Then the update is rejected
And the rejection states that stakeholder identifier changes are not permitted on a CLOSED account
And the current stakeholder identifier on account "A-1" remains "STK-1001"
```

### Scenario: Historical events preserve the stakeholder identifier in force at the time of their emission

```gherkin
Given an account exists with account identifier "A-1" that was opened with stakeholder identifier "STK-1001"
And the AccountOpened event for account "A-1" carried stakeholder identifier "STK-1001"
When Cameron updates the stakeholder identifier on account "A-1" to "STK-2002"
Then the AccountOpened event for account "A-1" still carries stakeholder identifier "STK-1001"
And the event stream for account "A-1" contains an AccountStakeholderChanged record reflecting the move from "STK-1001" to "STK-2002"
```

**Out of Scope:**
- The migration of any aggregate views the configurer or any downstream consumer derives from the stakeholder-account set — those views are the consumer's responsibility to recompute against the post-change state. Nucleus does not migrate any derived state because it maintains none.
- The semantics of "same stakeholder" in the upstream customer management system — Nucleus does not assess this question and does not cluster identifiers that look similar. Byte-exact equality determines current set membership.
- Bulk reattribution of accounts following an upstream merge of two stakeholder identifiers — each account's update is a separate operation initiated by the configurer.
- Idempotency of the stakeholder identifier update operation — covered by the general idempotency mechanism (operation ID, idempotency key) where applicable; the no-op-on-identical-value scenario above is not idempotency, it is the absence of a meaningful change to record.

**Open Questions:**

Whether the stakeholder identifier update is exposed as a dedicated endpoint or is reachable as a property of a more general account-update surface is an API shape question that does not affect the domain semantics. Left open.

**Guidance for the tdd-implementor.** The Account context's domain model has established that the stakeholder identifier is inherently mutable on `OPEN` accounts. Any API surface exposed by the accounts module should simply allow that mutation; the accounts-module API is not the place to reason about whether the change is significant in upstream terms, since that judgement belongs upstream of Nucleus. If higher-level APIs are required elsewhere — for example a guarded operator surface, or a configurer-side workflow that wraps the change in additional checks — those can be introduced on an as-needs basis without revisiting the accounts module's contract.