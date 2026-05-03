# ADR-023: Account status lifecycle

**Date:** 2026-05-03
**Status:** Accepted

## Context

The Account aggregate must define when an account is operable, when it is
preparing for closure, and when it is final. The lifecycle has several
requirements that constrain the model.

Closure of an account makes its record immutable. If an account holds commercial
bank money — any non-zero balance against any of the addresses through which
positions on the account are tracked — closure would strand those funds. The
system as a whole must therefore guarantee that closure does not commit while
any such balance remains. The architectural question is where that guarantee
is enforced.

The Account module is the precondition for every context that records financial
state — Ledger, Payments, Account Servicing all depend on accounts existing
before they can do their work. Making the Account module reach back into Ledger
state to verify a zero-balance predicate would invert that relationship and
introduce a structural cycle in the bounded-context dependency graph. The
substantive predicate — verifying that all balances are zero — properly belongs
to the Ledger context, which is the system of record for balance state. The
Account module's role is orchestration: it structures the closure transition
such that the Ledger (and any other context with a similar pre-close
responsibility) can plug in as a participant whose synchronous assertion gates
the transition.

Closure preconditions therefore come in two layers, both of which the closure
mechanism accommodates:

- **Asynchronous preconditions** — work that contributing contexts complete
  over time (payments settle, accruals finalise, in-flight operations resolve)
  and report via events. The autonomous closure mechanism (ADR-028) consumes
  these events into a per-account precondition projection and recognises when
  all known asynchronous preconditions are satisfied.
- **Synchronous pre-close predicates** — final checks performed atomically with
  the transition itself. Pre-close participants registered by other contexts
  assert their predicates at the moment of close (per the lifecycle participant
  model, ADR-031); the transition commits only if all assert positively. The
  Ledger's zero-balance assertion is the canonical pre-close predicate.

Closure intent must therefore be distinguishable from closure itself, since
the two are separated in time by both the asynchronous work and the synchronous
final gate. A two-stage status model is the natural fit.

A closed account is final. Its record is retained for query and audit, but no
state change is permitted. Reopening is not a domain operation; if the
stakeholder needs a new account, a new account is opened with a new identifier.

The lifecycle must be exhaustive at the level of operations it governs: writes,
transfers, configuration changes, closure intent itself. Whether each operation
is permitted on an account at a given moment is determined by the account's
status alone; no separate flags or modes coexist.

The lifecycle applies uniformly across all account categories, customer and
internal alike (per the ADR-030 candidate). Internal accounts go through the
same lifecycle as customer accounts; the closed enumeration covers both.

## Decision

An account is in one of three states: `OPEN`, `PENDING_CLOSURE`, `CLOSED`. The
states form a closed enumeration; no other values are valid. The transitions are
linear and forward-only:

```
OPEN ──────► PENDING_CLOSURE ──────► CLOSED
```

There is no return path from `PENDING_CLOSURE` to `OPEN`. There is no direct
transition from `OPEN` to `CLOSED`. Once `CLOSED`, the status is terminal.

The transitions govern which operations are permitted on the account:

- Account-level parameter values may be written only while the account is
  `OPEN`. Writes on `PENDING_CLOSURE` or `CLOSED` accounts are rejected.
- Node transfers may be performed only while the account is `OPEN`. Transfers
  on `PENDING_CLOSURE` or `CLOSED` accounts are rejected.
- The stakeholder identifier may be updated only while the account is `OPEN`.
- `CLOSED` is structurally terminal: no state change of any kind is permitted
  on a `CLOSED` account.

The `PENDING_CLOSURE` → `CLOSED` transition is gated by two layers of
precondition. The asynchronous layer is the precondition projection
maintained per ADR-028: contributing contexts emit events reporting progress
on long-running work, and the projection records when all known asynchronous
preconditions for the account are satisfied. The synchronous layer is the
pre-close phase defined by the lifecycle participant model (ADR-031):
pre-close participants registered by other contexts run as a final check at
the moment of the transition, each asserting its predicate, and the
transition commits only if all assert positively. The canonical pre-close
participant is provided by the Ledger context, which asserts that every
balance address of the account holds zero — the predicate that prevents
closure from stranding commercial bank money. The Account module does not
itself enforce this predicate or reach into balance state; it orchestrates
the participants whose assertions deliver the guarantee.

A `CLOSED` account is structurally immutable but is not deleted. Its record is
retained, including its full attachment history and audit trail, for query and
regulatory purposes. The retention period is governed by the bank's data
retention policy, which is upstream of Nucleus and does not constrain the
domain model directly. UK GDPR confers Casey rights of access during that
period; the account record is part of what must be returnable in a subject
access response.

Reopening a closed account is not a domain operation. If a stakeholder requires
a new account on the same product, a new account is opened with a new
identifier and a fresh lifecycle.

## Consequences

**Positive:** The closed enumeration is small, easy to reason about, and
exhaustively covers the account's lifecycle. The forward-only transitions mean
that an account's history is intrinsically append-only; no reverse-chronological
state changes are possible at the status level. Permitted operations are
determined unambiguously by status alone, so there is no need to consult
auxiliary flags or compose orthogonal state. Downstream consumers that reason
about status — Robin's cohort reporting, Alex's population reconciliation,
configurers that gate customer journeys on closure — see a uniform, simple
model. The closure transition is structured such that the system as a whole
guarantees that closure cannot strand commercial bank money: the Account
module orchestrates pre-close participants, and the Ledger's pre-close
participant asserts zero balance as a synchronous final check before the
transition commits. The substantive responsibility sits with the context
that owns the relevant state, while the orchestration responsibility sits
with the Account module.

**Negative:** There is no "suspended" or "dormant" status that could pause an
account without committing to closure intent. Such functionality, if needed,
must be handled through restrictions or parameter changes rather than through
status. The terminal nature of `CLOSED` means that a closure committed in error
is recoverable only by opening a new account, not by reverting the prior one.

**Risks:** A configurer that issues closure intent prematurely cannot recall
it. The only path forward is for the autonomous closure mechanism to complete,
after which a new account would have to be opened. This is mitigated by
authorising closure intent at the configurer's boundary before the instruction
is issued, and by the idempotency of the closure intent operation (a duplicate
instruction does not progress the account further). A separate risk is that
the close transition depends on pre-close participants from other contexts
(Ledger most importantly) being registered and behaving correctly. A
participant that fails to assert positively when its predicate is satisfied
would leave the account in `PENDING_CLOSURE` indefinitely; a participant that
asserts positively when its predicate is not satisfied would permit a closure
that ought to have been blocked. The participant model (ADR-031) makes the
contract clear, and the Ledger's pre-close participant in particular is
structurally placed to enforce the zero-balance predicate without Account
needing to reach into Ledger state. The dependency direction is preserved:
participants register against the Account context's lifecycle hooks rather
than Account querying participants' contexts.

## Alternatives Considered

A status enumeration with additional values such as `SUSPENDED`, `FROZEN`, or
`DORMANT` was considered. It was rejected: those are functions of restrictions
and parameter changes, not of account status. Conflating them with status would
couple the lifecycle to operational concerns that are properly the concern of
the Restrictions context (out of scope for this session) and the Account
Feature Catalogue. A frozen account is an account with a restriction; a dormant
account is an account whose servicing parameters have been updated to reflect
dormancy. Neither needs a status value of its own.

A status with a return path from `PENDING_CLOSURE` to `OPEN` was considered. It
was rejected: closure intent is a deliberate, attributable instruction;
permitting reversal would dilute its meaning and create a window in which
downstream contexts would have to reconcile uncommitted state changes. A
payment that was rejected because the account was `PENDING_CLOSURE` would have
to be reissued once `OPEN` was restored — an unnecessarily complicated model.
Configurers that reconsider closure must wait for closure to complete and open
a new account.

A direct `OPEN` → `CLOSED` transition with synchronous precondition validation
at the moment of the close instruction was considered. It was rejected: closure
preconditions are owned by other contexts whose state may not be queryable
synchronously, and forcing a synchronous evaluation would either introduce
cross-context queries on the close path or accept eventually-consistent state
that is later contradicted. The two-stage model accepts the temporal separation
honestly and lets each stage do exactly what it can do.

Holding the zero-balance check inside the Account module was considered (an
earlier draft of this ADR proposed it). It was reconsidered and rejected: the
Account module is the precondition for the contexts that record financial
state, and a synchronous Account-to-Ledger query inverts the architectural
relationship and risks a structural cycle. The Account module orchestrates
the lifecycle and consumes precondition events; it should not itself reach
into balance state. The Ledger context is the natural home for the
zero-balance predicate, and the pre-close participant model (ADR-031) is the
mechanism by which it is invoked at the moment of the transition. This
properly assigns the substantive responsibility to the system of record while
keeping the orchestration responsibility with the Account module. The
participant invocation preserves the dependency direction (Ledger registers
against Account; Account does not query Ledger).