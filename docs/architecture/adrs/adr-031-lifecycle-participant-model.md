# ADR-031: Lifecycle participant model

**Date:** 2026-05-03
**Status:** Accepted

## Context

Account opening and closing are operations that may need to involve work from
multiple contexts to complete correctly. The internal accounting feature must
provision a per-customer aggregation account at the first customer-account
opening for each stakeholder, atomically with the customer account itself.
Account Servicing must schedule first accruals at opening and finalise them at
closure. Payments must register external addressability at opening for accounts
that need it and return in-flight payments at closure. Account features may
need to set up servicing schedules, register internal collaborators, or perform
feature-specific finalisation. Each of these is a contribution from a context
other than Account, and each must commit or roll back atomically with the
Account aggregate's own state change.

The naive design — fixed integration points hard-coded into Account opening
and closing — would couple the Account context to every context that
contributes work, would force every change to the contributing set into
Account-context surgery, and would not accommodate participants whose
contribution depends on the account's specific configuration (since the
hard-coded points would have to anticipate every variant). The better design
is a participant model: an open extension point at each lifecycle composite
to which contexts register work, with the Account context invoking the
registered participants at the appropriate moment within the lifecycle
transaction.

The current set of contributing contexts is small. The internal accounting
feature is the only concrete instance, and only at opening. A general
participant infrastructure would be over-engineered for present needs. But
the present design must not foreclose the introduction of additional
participants as the contributing contexts (Account Servicing, Payments,
further account features) are defined and stories anchored to them are
implemented.

## Decision

Account opening and closing are atomic compositions across context
boundaries. The Account aggregate's own work — validation, state transition,
event emission — forms the spine of each composite; participants registered
by other contexts contribute work or assert predicates that must complete
alongside the spine for the operation to commit.

There are three participant phases, each with its own semantics:

- **Opening participants.** Run synchronously within the OPEN transition's
  composite. Contribute work — provisioning related internal accounts,
  scheduling first accruals, registering external addressability — that
  must commit atomically with the new account.
- **Prepare-to-close participants.** Run synchronously within the
  `OPEN` → `PENDING_CLOSURE` transition's composite. Contribute work
  required to set the account on a closing trajectory — scheduling final
  accruals, suspending payment initiation, marking aggregation positions
  for finalisation. Async work then proceeds in the background, reported
  via events to the precondition projection per the autonomous closure
  mechanism (ADR-028).
- **Pre-close participants.** Run synchronously within the
  `PENDING_CLOSURE` → `CLOSED` transition's composite, after the
  asynchronous precondition projection has reported all known preconditions
  satisfied and the close transition has been autonomously triggered.
  Pre-close participants assert predicates as a final synchronous check; the
  transition commits only if every pre-close participant asserts positively.
  A pre-close participant that asserts negatively aborts the transition and
  leaves the account in `PENDING_CLOSURE`; subsequent precondition events
  may re-trigger the transition, which will re-invoke pre-close participants
  for a fresh assertion.

The contributing contexts anticipated for the foreseeable future are four:

- **Account Feature Catalogue.** Opening participants for features that
  need to set up servicing schedules or provision related internal accounts
  (the internal accounting feature is the standing example);
  prepare-to-close participants for features that need feature-specific
  closing setup; pre-close participants for features whose finalisation is
  asserted as a predicate at the moment of close.
- **Account Servicing.** Opening participation to schedule the first
  accrual; prepare-to-close participation to suspend or schedule final
  accruals; pre-close participation may also assert that all servicing
  schedules have been finalised.
- **Payments.** Opening participation to register external addressability
  for accounts that require it; prepare-to-close participation to suspend
  outbound payment initiation; pre-close participation may assert that no
  in-flight payments remain.
- **Ledger.** Pre-close participation to assert that every balance address
  of the account holds zero — the canonical pre-close predicate that
  prevents closure from stranding commercial bank money. The substantive
  predicate is owned by Ledger because Ledger is the system of record for
  balance state; Account orchestrates the assertion via the participant
  model rather than reaching into Ledger state itself. See ADR-023.

A participant runs within the lifecycle operation's transaction. It may
issue further account-context operations — most commonly further account
openings, the internal accounting feature's aggregation account
provisioning being the standing example — which are themselves composite
and may invoke their own participants, recursively. Every operation in the
recursive graph commits or rejects as one transaction. A participant that
fails rejects the whole graph, including work performed by earlier
participants and by the lifecycle operation's own steps that preceded the
failure.

The simple case — zero participants — is a valid lifecycle operation. With
no participants registered, opening reduces to validation, derived-property
computation, aggregate creation, and event emission; closure intent reduces
to validation, status transition, and event emission; closure completion
reduces to status transition and event emission. The Account context is
fully functional in this configuration.

The implementation of a general participant infrastructure may be deferred.
A tdd-implementor working on an early opening or closing story is not
obliged to construct a participant registry, an invocation framework, or a
recursion mechanism ahead of need. The constraint is forward-compatibility:
the simpler implementation must structure the opening and closing
transactions so that further atomic contributions can be inserted without
re-architecting the lifecycle composite. In particular, the boundary
between the Account aggregate's own work and the participant invocation
point must be expressible at the implementation level, even if the
invocation point initially does nothing. A no-op participant invocation is
acceptable; a structure that has no place to insert participant invocation
later is not.

## Consequences

**Positive:** The lifecycle operations are extensible without surgery to
the Account context. New contributing contexts (a future operator team's
contribution to closure, a new account feature's setup work at opening,
Payments registration of external addressability) are accommodated by
registration. The atomicity guarantee is preserved across participant
contributions: there is no partial commit possible in any composite. The
implementation latitude permits early stories to ship without infrastructure
that is not yet justified by need; a participant model that admits zero
participants is not an over-engineered participant model.

**Negative:** The participant model adds a degree of indirection at the
lifecycle boundary that simpler models would not have. Future participants
may discover that their requirements push against the model's assumptions
— for example, requiring a participant to fail without rolling back the
whole graph, or requiring asynchronous participation that does not fit a
single transaction — at which point the model must be extended or
revisited. The forward-compatibility constraint requires discipline at
implementation time even when the immediate need does not warrant the full
mechanism.

**Risks:** A tdd-implementor who optimises for the immediate need (zero
participants) too aggressively may produce code that is hard to extend
when the first real participant is introduced. The boundary between
Account-aggregate work and participant invocation must be visible in the
implementation from the outset, even if the participant invocation is
initially a no-op; otherwise the eventual extension becomes a reshaping of
the lifecycle operation rather than an addition to it. Mitigation: review
the lifecycle implementations specifically against the criterion that a
participant invocation point exists, even when empty.

## Alternatives Considered

Hard-coded integration points — a fixed sequence of context-specific calls
baked into Account opening and closing — was considered. It was rejected:
it would couple the Account context to every contributing context, would
propagate every change to the contributing set into Account-context
surgery, and would not accommodate participants whose contribution depends
on the account's specific configuration.

A pluggable hook system without atomicity guarantees — participants run
independently, with no coordinated commit/rollback — was considered. It
was rejected: the participants' work is not optional and not independent
of the lifecycle transition. Partial-commit failure modes (the customer
account opens but the aggregation account provisioning fails, or vice
versa) would leave the system in inconsistent states that are difficult to
diagnose and to recover from.

A two-phase commit protocol with explicit prepare/commit/abort phases at
each participant was considered. It was rejected for the present model:
the participants are all in-process and operate on the same transactional
store, so a database-level atomic transaction is sufficient; introducing
2PC ceremony for in-process atomicity would over-engineer the simple case.
If asynchronous or out-of-process participants emerge in the future, the
model would need to be revisited at that point.

Building the full participant infrastructure ahead of the first real
participant was considered. It was rejected: with only one concrete
participant currently identified, the infrastructure would be designed
against assumptions about participants that haven't been made. The
implementation latitude in this ADR — defer the infrastructure but
preserve the extension point — strikes the balance between premature
generality and forward-compatibility.