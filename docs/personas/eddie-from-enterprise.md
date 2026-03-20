# Persona: Eddie from Enterprise

## Role

Eddie represents the Enterprise value stream: the internal operational client responsible
for providing tooling to human operators — customer support agents, payments agents, and
back-office remediation teams — who need to view account state, act on behalf of customers,
and perform corrections and repairs that cannot be handled by the automated configurer
value streams.

## Type

Human operator proxy — synchronous actor and remediation initiator.

## Responsibilities

Eddie mediates between Nucleus and the humans who operate the bank. Where Sasha, Liam, and
Maya are automated systems interacting with Nucleus programmatically at scale, Eddie's
interactions are initiated by individual operators responding to specific customer or
operational situations. A customer service agent handling a complaint about a missing
interest payment, a payments agent investigating a stuck outbound payment, a back-office
team applying a regulatory restriction to an account — all of these are Eddie.

Eddie is accountable for providing those operators with accurate, timely account state from
Nucleus and for ensuring that the actions they take are correctly reflected in the Nucleus
record. Eddie is also the primary remediation actor when Alex identifies a reconciliation
break: where a correcting entry, a payment recall, or a manual ledger adjustment is required,
Eddie is the persona through which that action is initiated in Nucleus.

Eddie does not own products or configure behaviour. Eddie does not define what an account
should do. Eddie responds to what an account has done — or failed to do — and acts on
behalf of the humans whose job it is to resolve those situations.

## Goals

- Query the full current and historical state of any account in Nucleus — status, balance,
  ledger entries, payment history, restrictions, audit trail — and present it to an operator
  in sufficient detail to diagnose a customer or operational issue without needing to consult
  another system.
- Apply restrictions and flags to accounts on behalf of operators acting under regulatory
  instruction, customer request, or internal risk escalation, and confirm the restriction
  is in effect before the operator concludes the action.
- Lift restrictions and flags when the triggering condition has been resolved, with a full
  audit record of who lifted the restriction, when, and on what authority.
- Initiate correcting entries and adjustments in Nucleus when Alex identifies a
  reconciliation break or when an operator identifies an error in the account record that
  requires remediation. Every correcting entry must carry attribution — which operator, on
  whose authority, referencing which original entry.
- Initiate payment recalls, investigations, and manual payment instructions on behalf of
  payments agents responding to customer disputes or scheme-level exceptions.
- Close accounts in exceptional circumstances — for example, following a bereavement, a
  fraud determination, or a regulatory direction — where the normal closure path through
  the configurer value stream is not appropriate or not available.
- Provide a complete, immutable audit trail of every action taken through Enterprise tooling,
  sufficient to support a regulatory examination or an internal investigation.

## Constraints

- Every state change initiated by Eddie must be attributable to a named operator and, where
  policy requires it, to an authorising second operator. Nucleus must record the `X-Client-ID`
  header on all Eddie-initiated requests, and Eddie must supply it accurately. Unattributed
  state changes are a compliance failure, not merely an audit gap.
- Eddie operates on behalf of humans, which means interaction patterns are unpredictable,
  low-volume, and often high-stakes. Eddie is not a bulk processing client. A single operator
  action may affect one account; it must be correct. The error handling Nucleus returns to
  Eddie must be precise and actionable — an operator cannot be expected to interpret a generic
  error code.
- Eddie's query scope is broad: any account, any value stream, any status. Eddie must be able
  to retrieve account state from Nucleus regardless of whether the account was opened by Sasha,
  Liam, or Maya, and regardless of the account's current status including closed accounts
  within their retention period.
- Certain Eddie-initiated actions require that Nucleus enforce preconditions before accepting
  the instruction. A correcting entry cannot be posted to a closed account. A restriction
  cannot be lifted if a second active restriction of a higher precedence is in force. Nucleus
  must validate these preconditions and return structured errors that Eddie can surface to the
  operator as meaningful guidance.
- Eddie's actions frequently have downstream consequences for Alex and Robin. A correcting
  entry posted by Eddie produces ledger events that Alex will reconcile. A restriction applied
  by Eddie may trigger a regulatory report that Robin must produce. Eddie does not manage these
  downstream effects directly, but must ensure that Nucleus emits the appropriate events so
  that Alex and Robin can respond without out-of-band notification.
- Remediation actions are exceptional by nature. They should not be possible through the same
  automated API surface as routine configurer operations. Where Nucleus exposes endpoints
  specifically for remediation — correcting entries, manual payment instructions, exceptional
  closure — those endpoints should require explicit operator context and should carry a
  distinct audit event type that distinguishes remediation from routine processing.

## Integration Pattern

**Synchronous (REST — outbound from Eddie):** The primary integration. Eddie's interactions
are operator-driven and require synchronous confirmation: an operator applying a restriction
needs to know it is in effect before ending the call with the customer. Eddie initiates all
state changes synchronously and expects structured, actionable responses — both on success
and on failure.

**Synchronous (REST — inbound to Eddie, outbound from Nucleus perspective):** Account state
and history queries. Eddie queries Nucleus for current and historical state on demand,
driven by operator need. Query patterns are low-volume, unpredictable in timing, and
require complete, accurate responses rather than eventually consistent approximations.

**Asynchronous (Kafka — inbound to Eddie, optional):** Eddie may subscribe to lifecycle
events for accounts under active operator attention — for example, to notify a payments
agent when a payment they are investigating is settled or rejected. This is a secondary
integration; Eddie's primary mode is synchronous and operator-initiated.

The `X-Client-ID` header carries the operator identity on every Eddie-initiated request.
Eddie is responsible for populating this accurately. Nucleus uses it as the `createdBy`
or `lastModifiedBy` audit field on all affected records.

Eddie is a **synchronous remediation actor**. Unlike the configurer personas, Eddie does
not have a product domain or a defined processing schedule. Eddie acts when a human decides
to act, and Nucleus must respond accordingly.

## Interests By Domain Area

**Account state and history:** Primary stakeholder. The ability to retrieve complete account
state — including closed accounts, restricted accounts, and accounts in error states — is
Eddie's most fundamental requirement. Any account in Nucleus must be fully visible to Eddie
regardless of its status or value stream origin.

**Restrictions and flags:** Primary stakeholder. Eddie is the principal actor for restriction
and flag operations in Nucleus. The restriction domain must be designed with Eddie's use
cases as the primary driver: structured restriction types, precedence rules, lift preconditions,
and audit attribution are all requirements that originate with Eddie.

**Ledger entries and correcting entries:** Primary stakeholder for remediation. Eddie posts
correcting entries on behalf of operators and Alex. The correcting entry mechanism — how
Nucleus represents a reversal or adjustment, how it links to the original entry, and what
events it emits — must be designed to support Eddie's attribution and audit requirements.

**Payments:** Primary stakeholder for exceptional payment actions. Eddie handles payment
recalls, manual payment instructions, and scheme investigation responses on behalf of
payments agents. The payments domain must expose operational endpoints that Eddie can use
without those endpoints being accessible to the automated configurer personas.

**Account close (exceptional):** Secondary stakeholder. Eddie can close accounts in
exceptional circumstances. The exceptional closure path must be distinct from the normal
configurer-initiated closure, carry additional attribution requirements, and be appropriately
restricted by Nucleus to prevent misuse.

**Balances:** Secondary stakeholder. Eddie queries balances in the context of diagnosing
account issues rather than for reporting or reconciliation. Real-time accuracy matters more
to Eddie than historical depth, though historical balance queries are occasionally necessary
for dispute resolution.

**Account servicing:** Low direct interest. Eddie does not trigger or configure servicing
operations. Eddie may need to query whether a scheduled servicing event has run — for
example, to confirm to a customer that interest has been applied — but this is a read
operation rather than an operational one.

**Parameter value hierarchy:** Low direct interest. Eddie does not configure product
parameters. However, Eddie may need to query which parameter configuration applies to a
given account in order to explain its behaviour to a customer or to diagnose an unexpected
servicing outcome. Read access to the effective parameter configuration for an account is
therefore a requirement, even if Eddie has no write access to the hierarchy itself.
