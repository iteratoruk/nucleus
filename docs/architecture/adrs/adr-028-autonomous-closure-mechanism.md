# ADR-028: Autonomous closure mechanism

**Date:** 2026-05-03
**Status:** Accepted

## Context

The `PENDING_CLOSURE` → `CLOSED` transition is autonomous: the Account context
detects that all preconditions for an account are satisfied and triggers the
transition without further configurer instruction. This was resolved as OQ-2 of
the Account domain model. The two-step alternative — a separate "finalise
closure" instruction issued by the configurer — was rejected for inverting the
configurer/fulfilment relationship by placing the trigger of a Nucleus-owned
state change into the configurer's hands.

The autonomous mechanism leaves a residual concern that this ADR addresses:
the shape of the per-account precondition projection. Specifically, how the
Account context knows which preconditions apply to a given account, which event
types it subscribes to from each contributing context, how it updates the
projection on each event, and how it behaves when events arrive out of order
or are replayed.

Several of the contributing contexts (Account Servicing, Ledger, Payments) are
not yet defined; their event vocabularies will emerge with their domain models.
This ADR therefore commits to a framework that accommodates new contributing
contexts without supersession, rather than enumerating specific event types
that would require revision as those contexts are defined.

## Decision

The Account context maintains a per-account precondition projection. The
projection is keyed by account identifier and records, for each account in
`PENDING_CLOSURE`, the set of preconditions that must be satisfied before the
account may transition to `CLOSED` and the current satisfaction state of each.

The set of preconditions applicable to a given account is determined at the
moment of closure intent. At that point the Account context computes the set
from the account's resolved feature configuration and from the registered
closing-completion participants whose contribution is conditional on the
account's state. For example: an interest-bearing account requires the Account
Servicing context to confirm that the final accrual has been posted; a
payment-capable account requires the Payments context to confirm that
in-flight payments have settled; every account requires the internal
accounting feature to confirm aggregation finalisation. The exact set varies
by account; the determination logic is local to the Account context but
draws on the catalogue and the participant registry.

The projection is updated by events from the contributing contexts. Each
contributing context emits events asserting precondition satisfaction for
specific accounts. The Account context consumes these events and updates the
projection idempotently:

- An event asserting a satisfaction state already recorded is a no-op.
- An event asserting satisfaction of a precondition not in the projection's
  expected set for the account is logged as a diagnostic but does not modify
  the projection.
- A precondition recorded as satisfied at time T is not unsatisfied by an
  earlier-timestamped event arriving later; precondition satisfaction is
  monotonic.

When all asynchronous preconditions for an account in the projection are
reported as satisfied, the Account context initiates the
`PENDING_CLOSURE` → `CLOSED` transition. The transition runs synchronously
through the pre-close participant phase per the lifecycle participant
model (ADR-031): pre-close participants assert their predicates, with
the Ledger's zero-balance assertion being the canonical instance, and the
transition commits only if every pre-close participant asserts positively.
A pre-close participant that asserts negatively aborts the transition and
leaves the account in `PENDING_CLOSURE`; subsequent precondition events may
re-trigger, and the pre-close phase is re-invoked for a fresh assertion.
On commit, the account transitions to `CLOSED`, the projection entry is
removed, and `AccountClosed` is emitted.

The two layers — asynchronous precondition projection and synchronous
pre-close phase — together gate the transition. The projection establishes
that all known long-running work is complete. The pre-close phase verifies,
at the moment of the transition itself, that the predicates contributing
contexts care about hold true. The Ledger's zero-balance assertion is
particularly load-bearing: closing an account holding commercial bank money
would strand those funds, and the synchronous pre-close check is the
mechanism that makes that outcome structurally impossible.

The specific event types subscribed to from each contributing context are
not enumerated in this ADR. They emerge as the contributing contexts are
defined and are recorded in the Account context's event subscription
configuration. New contributing contexts may be added without superseding
this ADR; the framework accommodates their participation through
registration of event subscriptions and additions to the precondition
determination logic.

The projection is durable: it survives application restart and event
replay. A replayed event whose effect is already recorded is idempotent (no
state regression). Restarts re-establish the projection from its persistent
form, not by replay from the beginning of the event log.

## Consequences

**Positive:** The configurer issues one closure instruction at intent and is
informed of completion via an event; no polling or retry is required at the
configurer's boundary. The mechanism is uniform across all closure
scenarios — there is no per-scenario code path. New contributing contexts
can be accommodated without reshaping the framework. Idempotent and
monotonic projection update semantics give the system natural resilience
to event replay, restart, and out-of-order delivery — important properties
in an event-driven distributed system.

**Negative:** The Account context maintains state whose shape depends on
events from contexts not yet defined. The shape of that state may need
refinement as the contributing contexts are designed; this ADR accommodates
such refinement but does not anticipate every detail. Out-of-order and
replayed events require careful handling; a bug in the projection update
logic could cause incorrect transitions or missed transitions.

**Risks:** A contributing context that fails to emit a precondition-
satisfaction event when it should — through a bug, a configuration error,
or an event-bus failure — could leave an account in `PENDING_CLOSURE`
indefinitely. Mitigation: monitoring of long-`PENDING_CLOSURE` accounts;
operator-driven escalation through the Eddie path for accounts that are
stuck due to systemic failure rather than to legitimate unsatisfied
preconditions. A second-order risk is that the precondition determination
logic incorrectly identifies the set of preconditions for an account,
either omitting a real one (leading to premature `CLOSED`) or including a
spurious one (leading to indefinite `PENDING_CLOSURE`); this is mitigated
by reviewing the determination logic alongside changes to the catalogue and
to the participant registry.

## Alternatives Considered

Two-step explicit closure was considered and rejected per OQ-2. The
reasoning is recorded in the Account domain model document.

A polling model — in which the Account context queries each contributing
context for precondition satisfaction at intervals — was considered. It was
rejected: polling shifts complexity from the contributing context (which
knows when its preconditions are satisfied and can announce it) to the
Account context (which would have to know how to ask each one and would
introduce arbitrary latency between satisfaction and transition). The
event-driven inverse aligns with how Robin and Alex consume Nucleus state
more generally and with the broader system's preference for event-driven
integration.

A centralised orchestrator outside the Account context was considered. It
was rejected: the trigger of the `CLOSED` transition is properly the
Account context's responsibility, since the transition is a state change of
the Account aggregate. Placing it elsewhere would inappropriately
externalise an Account aggregate state change and would split the
responsibility for closure across two contexts.

A model in which preconditions are explicitly registered at closure-intent
time by the configurer (rather than determined by the Account context from
the account's state) was considered. It was rejected: the configurer does
not have full visibility of the preconditions Nucleus enforces internally
(for example, the internal accounting feature's finalisation), and forcing
the configurer to enumerate them would couple the configurer's
implementation to Nucleus's internal participant registry.