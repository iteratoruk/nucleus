# ADR-027: Account Node Attachment aggregate is in the Account context

**Date:** 2026-05-03
**Status:** Accepted

## Context

The Account Node Attachment aggregate governs the relationship between an
account and the parameter node it is currently attached to, and the history of
that relationship across transfers. Its identity is the account; its invariants
are about account state — no transfer after closure intent, ledger-side
preservation across transfers, append-only attachment history; it consumes
account lifecycle events (`AccountOpened`, `AccountClosed`); and it produces an
event (`AccountTransferred`) that all configurer personas with interest in the
account must consume.

The Parameter Value Hierarchy domain model placed the aggregate provisionally in
the Parameter Configuration bounded context, with OQ-5 of that document
deferring the question to the architecture session that defined the Account
context in code. The deferral was explicit: "this placement should be revisited
in the architecture session that defines the Account context in code — before
any account lifecycle work begins. If the conclusion is that Account Node
Attachment belongs in `accounts`, this document and any implemented code must be
updated together."

This is that session. The Account bounded context's domain model is now
defined, and the placement question can be resolved on the basis of domain
coherence rather than provisional convenience.

## Decision

The Account Node Attachment aggregate belongs to the Account bounded context.
It is co-located with the Account aggregate by domain coherence: the unit of
consistency it expresses is "this account is currently attached to this node,"
whose primary actor is the account.

Three considerations support the placement. The aggregate's identity is the
account identifier, not anything in the parameter configuration domain. Its
invariants are about account state — `OPEN` is required for transfer,
`CLOSED` seals the attachment, ledger side is preserved through transfers —
none of which are concerns the Parameter Configuration context has any other
reason to know about. And its principal collaborators are the Account
aggregate (which generates the lifecycle events that drive Node Attachment
state) and the Account context's downstream consumers (configurers and the
Account Servicing context, both of which consume `AccountTransferred`).

The Parameter Configuration context retains ownership of parameter nodes
themselves and of the parameter values attached to those nodes. It does not
participate in attachment lifecycle. Account opening, transfer, and closure
are operations of the Account context, which produces `AccountOpened`,
`AccountTransferred`, and `AccountClosed`. The Parameter Configuration
context does not subscribe to these events for the purpose of attachment
management; the Account Node Attachment aggregate, internal to the Account
context, consumes them directly.

This decision supersedes Parameter Value Hierarchy OQ-5. The PVH domain
model document and any implementation that placed the aggregate in
`iterator.nucleus.parameters` (per the package convention established by
ADR-012) must be updated to reflect the reassignment.

## Consequences

**Positive:** The aggregate is co-located with the account whose state it
governs. The transfer operation's invariants (ledger-side preservation,
status precondition, append-only history) are naturally expressed alongside
the Account aggregate's own invariants. The Parameter Configuration context's
responsibilities narrow appropriately to parameter nodes and values
themselves, with no responsibility for the lifecycle of the relationships
that consume those nodes. The Account context owns the full picture of an
account's history, including which nodes it has been attached to over time —
useful for audit, for the Eddie persona's investigation of customer
situations, and for Robin's cohort attribution.

**Negative:** The reassignment requires updates to the Parameter Value
Hierarchy domain model document and to any in-flight implementation that
placed the aggregate in `iterator.nucleus.parameters`. The dependency graph
between the two bounded contexts shifts accordingly: where the Parameter
Configuration context previously held a write-side concern that consumed
account lifecycle events, it now holds only the resolution-side service that
the Account context queries.

**Risks:** Pre-existing code or documentation that assumes the aggregate's
prior placement may continue to do so until updated. Mitigation: the
reassignment is recorded explicitly in this ADR and in the Account domain
model document; the PVH domain model should be updated to reference this
ADR as the resolution of OQ-5; and any package-structure tests
(`BoundedContextDependencyTest` per ADR-016) should be updated alongside
the implementation move.

## Alternatives Considered

Retention of the aggregate in the Parameter Configuration context was
considered. It was rejected: the aggregate's identity is the account, its
invariants are about account state, and its principal collaborators are
within the Account context. Retaining it in Parameter Configuration would
couple that context to account-state semantics it has no other reason to
know about, and would force it to consume account lifecycle events whose
relevance is purely to the attachment relationship.

A standalone bounded context for "account-node relationships" was considered.
It was rejected: the relationship is not an independent domain concept worth
its own context. It is a property of an account's life that is expressed
through attachment; its lifecycle is the account's lifecycle; its invariants
are the account's invariants. Hosting it in its own context would create a
context whose entire purpose is to mediate between two other contexts, with
no independent domain content of its own.

Keeping the aggregate provisional and resolving the placement only when
implementation forces the question was considered. It was rejected: the
deferral was explicit and the trigger condition (defining the Account
context in code) is now met. Continuing to defer would allow implementation
work to proceed with the wrong placement and would compound the cost of
correction later.