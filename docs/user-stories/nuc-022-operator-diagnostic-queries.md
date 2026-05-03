## NUC-022: Diagnose an account's history on operator authority

**Persona:** Eddie from Enterprise

**Story:**
As Eddie from Enterprise,
I want to retrieve the full lifecycle event history and node attachment history of any account in Nucleus — including `CLOSED` accounts within their retention period — alongside its current state,
so that an operator handling a customer complaint, a regulatory enquiry, or a remediation case can see exactly what has happened to the account, when, and on whose authority, without having to consult another system or piece together state from multiple narrow queries.

**Background:**
Eddie's query scope is broader than Cameron's. The full state — including `CLOSED` accounts, including the complete history of attachments, including every emitted lifecycle event with its attribution — must be visible. Operator-driven queries are low-volume, unpredictable in timing, and high-stakes: an operator on a phone call with a customer cannot wait, cannot accept eventually-consistent approximations, and cannot interpret data whose provenance is unclear. The query surface must therefore expose attribution (`createdBy` / `lastModifiedBy`, the `X-Client-ID` of every state change), every state transition with its timestamp, and every attachment period with its start and end timestamps.

**Scenarios:**

### Scenario: Eddie retrieves the lifecycle event history of an OPEN account

```gherkin
Given an account exists with account identifier "A-1" that was opened by Cameron and subsequently transferred from "LIAB_INAS_2026" to "LIAB_INAS_2027"
When Eddie queries the lifecycle event history of account "A-1"
Then the response includes the AccountOpened event with its timestamp and Cameron's configurer attribution
And the response includes the AccountTransferred event with its timestamp, origin classification code "LIAB_INAS_2026", destination classification code "LIAB_INAS_2027", and the attribution of the actor who issued the transfer
And the events are ordered by their occurrence timestamp
```

### Scenario: Eddie retrieves the node attachment history of an account

```gherkin
Given an account exists with account identifier "A-1" that was opened attached to "LIAB_INAS_2026" and subsequently transferred to "LIAB_INAS_2027"
When Eddie queries the node attachment history of account "A-1"
Then the response includes the prior attachment to "LIAB_INAS_2026" with its attachment timestamp and detachment timestamp
And the response includes the current attachment to "LIAB_INAS_2027" with its attachment timestamp and no detachment timestamp
And the attachments are ordered by attachment timestamp
```

### Scenario: Eddie retrieves the current state and history of a CLOSED account

```gherkin
Given an account exists with status CLOSED, account identifier "A-1"
And the account was previously OPEN and PENDING_CLOSURE
When Eddie queries the current state of account "A-1"
Then the response carries status CLOSED
And the response carries the closure timestamp
And the lifecycle event history for account "A-1" includes AccountOpened, AccountClosureIntended, and AccountClosed events with their respective timestamps and attributions
```

### Scenario: Eddie retrieves the resolved feature configuration applicable to an account at a specific historical datetime

```gherkin
Given an account exists with account identifier "A-1" attached to the parameter node for classification code "LIAB_INAS_2026"
And the parameter value of liabilityInterest.interestRate at "LIAB_INAS_2026" was "0.0300000" at effective datetime 2026-01-01T00:00:00Z and "0.0350000" at effective datetime 2026-04-01T00:00:00Z
When Eddie queries the resolved feature configuration for account "A-1" as at resolution datetime 2026-02-15T00:00:00Z
Then the response carries liabilityInterest.interestRate with value "0.0300000"
```

### Scenario: A diagnostic query without an operator identity is rejected

```gherkin
When Eddie issues a diagnostic query against account "A-1" without supplying an operator identity via X-Client-ID
Then the query is rejected
And the rejection states that operator attribution is required for this operation
```

**Out of Scope:**
- Querying ledger entries, balances, or payment history — those are the concerns of the Ledger and Payments contexts and are not exposed through the Account context's query surface.
- Initiation of state-changing remediation actions (correcting entries, restriction application, exceptional closure) — those are write operations covered by separate stories in their respective contexts (and, for exceptional closure, in NUC-018).
- The precise endpoint shapes that compose Eddie's diagnostic surface — the architecture allows for either a single combined diagnostic endpoint or a set of narrower endpoints; the scenarios above can be implemented against either.
- Cross-account or cross-stakeholder queries (e.g. "all accounts for stakeholder X") — these depend on the materialised stakeholder-account index whose mechanism is anticipated to be the internal accounting feature. They are out of scope until that feature is scoped.
- The retention period boundary for `CLOSED` account queries — the architecture asserts that closed accounts are queryable within their retention period without prescribing the period. Whether the query surface returns a not-found response or a redacted response for accounts that have aged past retention is out of scope here.

**Open Questions:**

None. The two questions previously surfaced here are resolved as follows.

**Storage of the lifecycle event history.** The diagnostic surface reads from a Postgres-backed event log held within the Account context, not from the Kafka event topic. The principle that arbitrates this: Kafka retention should be a matter of topic management driven by consumer fan-out and replay needs, not by end-user requirements for historical visibility. Any data or log that might subsequently need to be retrieved — by Eddie, by Cameron, by Robin or Alex — must be persisted in Postgres so that the retention horizon for retrieval is decoupled from the retention horizon for streaming. If, during implementation, this leads to requirements to clear redundant data from Postgres on a schedule (for example to honour data retention policy), that finding should be recorded against the implementation and taken into an architecture review for a decision; a scheduled-cleanup mechanism is then the subject of a successor story rather than a silent implementation choice.

**Attribution on every change.** Every state change recorded against an account is audited with the identity of the user who created or last modified it, supplied via `X-Client-ID` and stored as `createdBy` / `lastModifiedBy` on the affected record and on the corresponding event. The diagnostic surface exposes this attribution directly on every entry it returns. Whether the surface additionally labels the actor as "operator" or "configurer" — that is, whether it interprets the `X-Client-ID` rather than merely surfacing it — is a presentation question that need not be answered at this story's level. The attribution itself is non-negotiable and present on every change, by virtue of the existing `AbstractMutableJpaEntity` auditing infrastructure; any further classification is a downstream concern.