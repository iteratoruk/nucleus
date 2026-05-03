# Domain Model: Account

## Bounded Context

**Account** is responsible for the identity, lifecycle, and external relationships of every
account that Nucleus manages. It owns the act of bringing an account into existence, the
transitions between the states an account may be in across its lifetime, and the relationships
that bind an account to a stakeholder, to a parameter node in the configuration hierarchy, and
to an accounting hierarchy under which its ledger positions will be aggregated.

This context does not own ledger entries, balances, payments, or any of the servicing
behaviour that operates on accounts after they are open. Those are the concerns of the
Account Servicing, Ledger, and Payments contexts respectively. What this context owns is the
account itself: the entity whose identity persists through all of those operations and whose
status governs whether they may occur.

This context is the structural prerequisite for all financial activity in Nucleus. No ledger
entry can exist without an account. No balance can be computed without an account. No payment
can be received or initiated without an account. The Account context establishes the entity
against which all subsequent operations are addressed.

What this context explicitly does not own: the mechanism by which a balance is computed from
ledger entries (Account Servicing); the financial preconditions for closure such as a zero
outstanding balance on a lending account (a precondition reported by another context, acted
on here); the identity of the stakeholder represented by the stakeholder ID (an external
customer management system); the meaning of the parameter values resolved against a node the
account is attached to (Parameter Configuration and the consuming context for each
property); and the production or consumption of the events that govern processing boundaries
such as `BUSINESS_DAY_CLOSE`.

---

## Ubiquitous Language

**Account.** The entity that represents a single product instance held against a single
stakeholder. Has a globally unique identity that persists from opening through closure and
beyond. An account is an entity, not a value object: two accounts with identical attributes
are nonetheless distinct entities if their identifiers differ.

**Account identifier.** A UUID assigned by Nucleus at the moment of account opening. The
client of the opening operation does not supply this identifier and cannot influence its
generation. Once assigned, immutable. Used as the account's identity in every Nucleus context
and on every event the system emits about that account.

**Stakeholder identifier.** An opaque string supplied by the configurer at account opening,
identifying the party against whom the account is held in the upstream customer management
system. Nucleus assigns no semantics to its content beyond treating it as a value: an
identifier whose internal structure, format, and meaning are the upstream system's concern.
The stakeholder identifier is not personal data in itself — it is a reference to personal
data held externally — but it must not be reverse-mapped to identifying information within
Nucleus, and Nucleus must hold no other attribute of the stakeholder.

**Account ledger side.** The asset/liability classification of the account, intrinsic and
immutable. Determined at opening from the first segment of the supplied classification code
(see Parameter Value Hierarchy domain model). An account on the asset side and an account on
the liability side are not the same kind of thing; the distinction is structural and governs
the direction of every ledger entry the account ever bears.

**Account status.** The lifecycle state of the account at a point in time. Closed
enumeration: `OPEN`, `PENDING_CLOSURE`, `CLOSED`. The status governs which operations are
permitted on the account and is observable to all consumers (configurers, reporting,
reconciliation). See Account Lifecycle below.

**Account opening.** The act of bringing a new account into existence. A composite operation:
it creates the Account aggregate, attaches it to a parameter node (creating any nodes that
do not exist), optionally writes feature configuration at the classification node and at the
account level, and records the opening as an immutable fact. Atomic from the configurer's
perspective: either every part of the operation persists or none does.

**Account closure.** The act of ending an account's lifecycle. Distinct from closure intent:
closure is the terminal transition, occurring only when all preconditions for closure are
satisfied. A closed account's record is retained and remains queryable; it is not deleted.

**Accounting code.** A hierarchical identifier under which Nucleus aggregates an account's
ledger positions. Shares the structural format of a classification code (dot-free,
underscore-delimited, 4-character uppercase alphanumeric segments) but is a distinct concept:
it determines the structure of ledger position aggregation, not the resolution of feature
configuration. Resolved as a parameter value via the Parameter Value Hierarchy. Every account
must have an accounting code resolvable at opening; an account whose accounting code is not
resolvable cannot be opened. Recorded as ADR-025 candidate; see also Accounting Hierarchy
below.

**Accounting hierarchy.** The hierarchical structure implied by an accounting code. Given an
accounting code `LIAB_RETL_SAVE`, Nucleus maintains and reports ledger positions at every
ancestor of that code (`LIAB`, `LIAB_RETL`, `LIAB_RETL_SAVE`). The accounting hierarchy is
distinct from the parameter value hierarchy and from the product classification hierarchy,
though all three share the same syntactic format. An account may have a classification code
and an accounting code that diverge from the third segment downward; the choice is the
configurer's, expressed through parameter configuration.

**Stakeholder accounts.** The set of accounts whose stakeholder identifier is identical to a
given value. A set-membership view, not an aggregate: there is no Stakeholder entity in the
Account context. The view (Single Customer View enumeration, regulatory cohort reporting)
is derived from the account population. Stakeholder-level *financial* aggregates — total
balance, P&L position — are likewise derived. The model anticipates that both are
materialised continuously rather than computed on demand: financial aggregates as positions
on per-stakeholder aggregation accounts maintained by the internal accounting feature, set
membership presumptively as a materialised index maintained alongside those aggregation
accounts. The specific mechanism per view is an implementation matter; on-demand
computation is inadvisable on performance, audit, and reconciliation grounds and must
justify itself where adopted (see Account Function). Recorded as ADR-022.

**Account-level parameter values.** Parameter values stored at the most specific level of the
resolution hierarchy — at the level of a single account. Take precedence over all
classification-code-level values during resolution. May be supplied at account opening or
written subsequently. Subject to the same feature catalogue validation as classification-node
parameter values. Writeable only on `OPEN` accounts.

**Node transfer.** The movement of an account from one parameter node to another after
opening. Permitted only on `OPEN` accounts; rejected on `PENDING_CLOSURE` and `CLOSED`.
Cannot move an account from one ledger side to another: the first segment of the
destination node's classification code must equal the first segment of the origin node's.
A transfer that would change the ledger side is rejected. The destination node must
exist; transfer does not auto-create nodes (in deliberate contrast to opening, which
does). The account's accounting code may change as a consequence of the transfer (the
accounting code resolves at the new node); this is acceptable and is the configurer's
responsibility to anticipate. See Account Node Attachment aggregate.

**Lifecycle participant.** A context external to the Account aggregate that contributes
work to an atomic account-lifecycle operation — opening or closing — and that must
succeed for the whole operation to commit. Participants are registered by their owning
contexts; the anticipated contributors are the Account Feature Catalogue (features that
need to set up servicing schedules, provision related internal accounts, or perform
feature-specific finalisation), Account Servicing (final accruals, suspension of
schedules), and Payments (registration of external addressability, return of in-flight
payments at closure). Each participant performs its work within the same transaction as
the Account aggregate's state change; any participant's failure rejects the whole
operation. The simple case of zero participants reduces a lifecycle operation to
validation, the core state transition, and event emission. See Lifecycle Participants
under Account Function.

---

## Aggregates

### Account

**Identity:** The account identifier (UUID). Two records are the same Account if and only if
their identifiers are identical. The identifier is assigned at opening and immutable
thereafter.

**Invariants:**

1. The account identifier is generated by Nucleus at opening and never changes. No client
   may supply or override it.

2. The stakeholder identifier is set at opening and may be updated subsequently while
   the account is `OPEN`. Updates on `PENDING_CLOSURE` or `CLOSED` accounts are
   rejected, consistent with the general rule that closure intent freezes account
   attributes. A stakeholder change does not retroactively reattribute prior events or
   ledger entries — those carry the stakeholder identifier in force at the time of
   emission and remain authoritative as the historical record. It does change the
   result of subsequent stakeholder-account queries: from the moment of the change,
   the account belongs to the new stakeholder and the prior stakeholder's
   account-set view no longer includes it. The configurer is responsible for
   understanding the consequences for any aggregate view it derives from the account
   set; Nucleus does not migrate aggregate balances or other derived stakeholder-level
   state, because it does not maintain such state in the first place.

3. The account ledger side is set at opening from the first segment of the opening
   classification code and is immutable. A node transfer cannot change the ledger side
   (this invariant is established in the Parameter Value Hierarchy domain model and
   inherited here without modification).

4. The status transitions form a single linear path: `OPEN` → `PENDING_CLOSURE` → `CLOSED`.
   No other transitions are valid. In particular, there is no return path from
   `PENDING_CLOSURE` to `OPEN`, and no direct transition from `OPEN` to `CLOSED`. Once
   `CLOSED`, the status is terminal.

5. Account-level parameter values may be written only while the account is `OPEN`. Writes
   to a `PENDING_CLOSURE` or `CLOSED` account are rejected.

6. A node transfer may be initiated only while the account is `OPEN`. Transfers on
   `PENDING_CLOSURE` or `CLOSED` accounts are rejected.

7. Once `CLOSED`, no further state changes of any kind are permitted on the account
   record. The record is retained for query and audit but is structurally immutable.
   The `PENDING_CLOSURE` → `CLOSED` transition is gated by pre-close participants
   per the lifecycle participant model (ADR-031): each pre-close participant runs a
   synchronous predicate check immediately before the transition, and the transition
   commits only if every pre-close participant asserts positively. The Ledger's
   pre-close participant — asserting that every balance address of the account
   holds zero — is the canonical instance, preventing closure from stranding
   commercial bank money. The Account module orchestrates these participants but
   does not itself enforce the zero-balance predicate; that responsibility is
   exported to Ledger, where balance state lives.

8. At opening, the resolved feature configuration for the account — the result of
   resolving every catalogue key for the account, with account-level values taking
   precedence over classification-node-resolved values, and with catalogue-declared
   defaults applied where present — must be valid against the Account Feature
   Catalogue. Validity is the catalogue's concern in full and subsumes completeness:
   type correctness, ledger-side applicability, openness compliance, and conditional
   inter-property requirements (e.g. "if `liabilityInterest.enabled` resolves to true,
   `liabilityInterest.interestRate` must resolve to a value"). Any failure rejects the
   opening with a structured per-property error.

9. At opening, an accounting code must be resolvable for the account from the parameter
   value hierarchy. An account whose accounting code does not resolve to a value (i.e.
   resolution returns no value or returns explicit absence) cannot be opened. The
   resolved accounting code's first segment must equal the account's ledger side; if it
   does not, the opening is rejected.

**Entities within this aggregate:** None beyond the Account itself. Status, stakeholder
identifier, ledger side, opening timestamp, and closure timestamp are properties of the
single Account entity.

**Value objects:**

- **Account Identifier.** A UUID. Generated by Nucleus, treated as opaque by all consumers.

- **Stakeholder Identifier.** An opaque string. Treated as opaque by Nucleus. Has no
  declared structure beyond a maximum length constraint enforced at the schema level.

- **Account Status.** A closed enumeration: `OPEN`, `PENDING_CLOSURE`, `CLOSED`.

- **Opening Timestamp.** The UTC datetime at which the account was opened. Determined
  by Nucleus from the wall-clock time at successful completion of the opening operation,
  not supplied by the configurer. Used as the basis for derived internal properties such
  as the maturity date (see ADR-019).

- **Closure Timestamp.** The UTC datetime at which the account transitioned to `CLOSED`.
  Null while the account is `OPEN` or `PENDING_CLOSURE`. Set once and immutable
  thereafter.

**Domain events produced:**

- `AccountOpened` — a new account has been brought into existence. Carries: account
  identifier, stakeholder identifier, ledger side, classification code at opening,
  accounting code at opening, opening timestamp, configurer attribution
  (`createdBy`).

- `AccountClosureIntended` — an account has transitioned from `OPEN` to
  `PENDING_CLOSURE`. Carries: account identifier, intent timestamp, configurer
  attribution.

- `AccountClosed` — an account has transitioned from `PENDING_CLOSURE` to `CLOSED`.
  Carries: account identifier, closure timestamp, configurer attribution.

- `AccountStakeholderChanged` — the stakeholder identifier on an `OPEN` account has
  been updated. Carries: account identifier, prior stakeholder identifier, new
  stakeholder identifier, change timestamp, configurer attribution. A submission whose
  new identifier equals the current identifier is a no-op and emits no event.

The Account context does not produce `AccountTransferred` directly; that event belongs
to the Account Node Attachment aggregate (see below). Account-level parameter writes
flow through the existing account-features API and produce `ParameterValueSet` events
in the Parameter Configuration context, not new Account-context events.

**Domain events consumed:** None within the Account aggregate's own decision logic.
Closure preconditions reported by other contexts are consumed by a coordinator outside
the aggregate (see Account Lifecycle below); the Account aggregate itself reacts only to
direct configurer or operator instructions.

---

### Account Node Attachment

This aggregate governs the relationship between an account and the parameter node it is
currently attached to, and the history of that relationship across transfers. It is
distinct from the Account aggregate itself and from the Parameter Node aggregate (which
belongs to Parameter Configuration).

The Account Node Attachment aggregate is reassigned to the Account bounded context by
this document. The Parameter Value Hierarchy domain model previously placed it
provisionally in Parameter Configuration with OQ-5 deferring the question. The
aggregate's identity is the account, its invariants are about account state and lifecycle
(no transfer after closure intent, ledger side preservation), and its principal
collaborators are the Account aggregate and the Account context's lifecycle events. By
domain coherence, it belongs in the Account context. Recorded as ADR-027 candidate.

**Identity:** The account identifier. The relationship between an account and its
attachments is one-to-many over time; at any single moment, exactly one attachment is
current.

**Invariants:**

1. An account's ledger side, derived from the first segment of its current attachment's
   classification code, does not change across transfers. The first segment of the
   destination node's classification code must equal the first segment of the origin
   node's classification code.

2. The destination node of a transfer must exist at the time of transfer. Transfers do
   not auto-create nodes. (Compare account opening, which does auto-create nodes; this
   is the deliberate point of difference.)

3. A transfer may be performed only while the account is `OPEN`. An account in
   `PENDING_CLOSURE` or `CLOSED` cannot be transferred. This invariant is co-located
   with the Account aggregate's invariant 6.

4. The attachment history is append-only. Prior attachments are not modified or removed
   on transfer; the prior attachment's detachment timestamp is set, and a new current
   attachment is created.

**Entities within this aggregate:**

- **Node Attachment.** A record of the account's attachment to a specific parameter
  node from a specific point in time. Carries: account identifier, classification code,
  attachment timestamp, detachment timestamp (null while current). Ordered by
  attachment timestamp.

**Value objects:**

- **Attachment Period.** The half-open interval `[attachment timestamp, detachment
  timestamp)`, open at the right end while the attachment is current.

**Domain events produced:**

- `AccountTransferred` — an account has been moved from one parameter node to
  another. Carries: account identifier, origin classification code, destination
  classification code, transfer timestamp, configurer attribution. All configurer
  personas with interest in the account must consume this event. The Account Servicing
  context must also consume it, as the resolved configuration may change.

**Domain events consumed:**

- `AccountOpened` (from the Account aggregate) — triggers the creation of the initial
  Node Attachment with attachment timestamp equal to the opening timestamp.

- `AccountClosed` (from the Account aggregate) — seals the current Node Attachment by
  setting its detachment timestamp to the closure timestamp, and prevents any further
  transfers.

---

## Account Lifecycle

The lifecycle of an account is a closed graph of three states with two transitions, both
forward, neither reversible.

### Opening: into `OPEN`

An account enters the `OPEN` state at the moment of opening. There is no prior state; the
account did not exist before. The opening operation is composite: it creates the Account
aggregate, creates the initial Node Attachment, optionally writes parameter values at the
classification node and at the account level, and emits the `AccountOpened` event. All of
these are observable to external consumers as a single transition: the account did not
exist; now it does, and it is `OPEN`.

The opening operation is the only point at which a configurer may supply both
classification-node configuration and account-level configuration in a single submission.
Subsequent classification-node configuration changes flow through the account-features API
(`PUT /account-features/{classificationCode}`); subsequent account-level configuration
changes flow through an equivalent account-scoped endpoint (whose precise contract is out
of scope for this session but follows the same feature representation).

### Closure intent: `OPEN` → `PENDING_CLOSURE`

A configurer or, on operator authority, Eddie issues a close instruction against an
`OPEN` account. The Account aggregate transitions to `PENDING_CLOSURE` and emits
`AccountClosureIntended` carrying the account identifier, intent timestamp, and
configurer attribution. The instruction does not carry a categorisation of why closure
is being instructed — that judgement is upstream of Nucleus and not represented in the
Account context's domain model.

The semantics of `PENDING_CLOSURE` are uniform: the account is preparing for closure;
in-flight Nucleus-owned servicing required to bring the account to a clean closed state
must complete; activity that the configurer or operator wishes to suspend during this
period is suspended through other Nucleus mechanisms — applying restrictions that
prevent debits or credits, instructing parameter changes that disable accruals, and so
on. Each of these is its own well-defined operation against the account; the closure
intent itself does not implicitly suspend or permit anything beyond the prohibition on
node transfer and on subsequent attribute changes that the status invariants establish.

A `PENDING_CLOSURE` account remains observable to all consumers in the same way an
`OPEN` account is. Configurers, Robin, Alex, and Eddie all see the transition.

### Closure: `PENDING_CLOSURE` → `CLOSED`

The account transitions to `CLOSED` only when both layers of closure precondition
are satisfied: the asynchronous precondition projection per ADR-028 (long-running
work reported via events from contributing contexts), and the synchronous
pre-close phase per ADR-031 (pre-close participants asserting predicates as a
final synchronous check at the moment of the transition).

The Ledger's pre-close participant is the canonical pre-close predicate. Closure
makes the account record immutable; an account holding commercial bank money —
non-zero balance against any address through which positions on the account are
tracked — would have those funds stranded by closure. Ledger asserts, at the
pre-close moment, that every balance address of the account holds zero. The
transition commits only on that assertion. The substantive responsibility for
the predicate sits with Ledger because Ledger is the system of record for
balance state; Account orchestrates the participant invocation rather than
reaching into Ledger state itself.

Other pre-close participants may assert further predicates: that all servicing
schedules have been finalised (Account Servicing), that no in-flight payments
remain (Payments), that aggregation contributions have been finalised (the
internal accounting feature). Each pre-close participant is owned by the
context whose state it asserts; Account orchestrates the invocation. A
pre-close participant that asserts negatively aborts the transition; the
account remains in `PENDING_CLOSURE` and subsequent precondition events may
re-trigger the close attempt.

The transition is autonomous: the Account context subscribes to events from other
contexts that report precondition satisfaction, and transitions to `CLOSED` when all
preconditions for the account are met, emitting `AccountClosed`. The configurer
learns of the closure via the event stream. This is consistent with the broader
Nucleus principle that the configurer instructs intent and Nucleus owns fulfilment:
the configurer issues one close instruction and Nucleus carries through.

The principal alternative — a two-step model in which the configurer issues a
separate "finalise closure" instruction that Nucleus evaluates against preconditions
at the moment of the call — was considered and rejected. It would place the burden
of timing on the configurer (forcing it to poll or retry until preconditions held)
and would invert the configurer/fulfilment relationship by moving the trigger of a
state change Nucleus owns into the configurer's hands. The autonomous model is more
complex to implement (it requires Nucleus to maintain a precondition projection
keyed by account, updated by events from contexts owning each precondition class),
but the complexity sits in the right place: in the context whose state change is
being triggered. Recorded as ADR-028 candidate.

### Terminal: `CLOSED`

A closed account is structurally immutable. Its record persists for query and audit. The
Node Attachment sealing on `AccountClosed` ensures the attachment history is also frozen.
A closed account contributes to historical aggregate views (Single Customer View at a
prior date, regulatory snapshots) but cannot be modified. Reopening a closed account is
not a domain operation; if a stakeholder requires a new account on the same product, a
new account is opened with a new identifier.

---

## Account Opening

The opening operation is a composite and atomic transaction. The configurer submits:

- A classification code (mandatory; determines node attachment, ledger side, and
  resolution lineage).
- A stakeholder identifier (mandatory).
- An optional set of feature configuration values to be written at the classification node
  (the same representation used by `PUT /account-features/{classificationCode}`).
- An optional set of feature configuration values to be written at the account level
  (account-scoped overrides).
- An idempotency key (per the Idempotency context).

Nucleus performs the operation in the following order, atomic as a whole:

1. Idempotency check, per the Idempotency context. If a prior operation matches, the
   stored response is returned immediately and steps 2–8 are skipped.
2. Structural validation of the classification code per the Parameter Value Hierarchy
   model.
3. Determination of the ledger side from the classification code's first segment.
4. Auto-creation of the classification node and any missing ancestors, if absent. Empty
   nodes created here are valid nodes per the Parameter Value Hierarchy invariants.
5. Application of submitted classification-node feature configuration. Validation per the
   Account Feature Catalogue domain model — feature applicability, type, openness, the
   exhaustive per-property error model.
6. Application of submitted account-level feature configuration. Validation as for
   classification-node values.
7. Resolution of the feature configuration for the account at the opening timestamp,
   producing the resolved map (account-level overrides over classification-node-resolved
   values, with catalogue-declared defaults applied where present). Validation of the
   resolved map against the Account Feature Catalogue: type correctness, ledger-side
   applicability, openness compliance, conditional inter-property completeness rules,
   and the specific requirement that the accounting code resolves to a value whose
   first segment equals the account's ledger side. Any failure rejects the opening
   with a structured per-property error.
8. Invocation of opening participants. Other contexts — Account Feature Catalogue,
   Account Servicing, Payments — may register participants that contribute work to
   the opening operation. Each participant performs its work within the same atomic
   transaction; any participant's failure rejects the whole opening, including any
   work already performed by earlier participants. The internal accounting feature
   is the first anticipated participant: it ensures an aggregation account exists for
   the opening account's stakeholder identifier and provisions one if necessary. A
   participant's contribution may itself include further account openings (the
   aggregation account provisioning is the standing example), which proceed through
   this same sequence recursively; every account opened within the recursive graph
   commits or rejects as one transaction. The simple case of zero participants
   reduces this step to a no-op. See Lifecycle Participants under Account Function.
9. Creation of the Account aggregate (UUID assigned), creation of the initial Node
   Attachment, computation and storage of any derived internal properties (per ADR-019),
   recording of the idempotent operation, emission of `AccountOpened`.

Any failure in steps 2–8 causes the entire operation to be rejected, including any
nested account openings issued by participants. No empty node is left behind, no
parameter value is partially applied, and no account record is created. Idempotency
recording occurs only on success — a failed submission is not idempotent and may be
retried with corrections.

The principle that opening can auto-create nodes is the deliberate point of difference
from node transfer, which cannot. The reasoning: opening is the moment a new account
classification first manifests in the system; if it has not been pre-registered, the
account itself carries the registration. Transfer, by contrast, presupposes an existing
target whose configuration has been deliberately established; auto-creating a transfer
target would mean transferring an account into an empty unconfigured node, which is a
configuration error rather than a normal flow.

---

## Account Closure

Closure is a two-stage process: closure intent (`OPEN` → `PENDING_CLOSURE`) followed by
closure completion (`PENDING_CLOSURE` → `CLOSED`). The two stages may be temporally close
or distant depending on the structural preconditions for the account's particular product
and ledger state.

The closure intent operation is direct and synchronous: the configurer issues a close
instruction against an `OPEN` account. Nucleus validates that the account is `OPEN`
(rejecting the instruction otherwise) and invokes any registered prepare-to-close
participants per ADR-031 — work contributed by other contexts (Account Servicing
scheduling final accruals, Payments suspending outbound initiation, the internal
accounting feature marking aggregation positions for finalisation) that must
complete atomically with the status transition. Failure of any prepare-to-close
participant rejects the intent. On success, the account transitions to
`PENDING_CLOSURE` and `AccountClosureIntended` is emitted. The instruction is
idempotent against the (operation, idempotency key) pair per the Idempotency
context. The simple case of zero prepare-to-close participants reduces the
operation to validation, the status transition, and the event emission.

Between intent and completion, asynchronous work proceeds in the contributing
contexts: payments settle, accruals finalise, in-flight operations resolve. Each
context emits events as its work progresses, and the Account context maintains a
per-account precondition projection updated by those events (per ADR-028). When
all known asynchronous preconditions for an account are reported as satisfied,
the Account context initiates the `PENDING_CLOSURE` → `CLOSED` transition.

The transition runs synchronously through the pre-close phase per ADR-031:
pre-close participants assert their predicates as a final check, and the
transition commits only if every pre-close participant asserts positively. The
canonical pre-close participant is provided by the Ledger context, which asserts
that every balance address of the account holds zero — the predicate that
prevents closure from stranding commercial bank money. The substantive predicate
is owned by Ledger; Account orchestrates the participant invocation. A pre-close
participant that asserts negatively aborts the transition; the account remains in
`PENDING_CLOSURE` and subsequent precondition events may re-trigger the close
attempt. `AccountClosed` is emitted on commit. The shape of the precondition
projection and the specific event types subscribed to are the residual concern
of ADR-028.

A closed account's record is retained. The retention period is governed by the bank's
data retention policy, which is upstream of Nucleus and does not constrain the domain
model directly. UK GDPR confers Casey rights of access to their own data within the
retention period; the Account record is part of what must be returnable in a subject
access response. See Casey persona.

### Compound closure operations

Some closure scenarios — closure following regulatory direction, closure following a
fraud determination, closure as part of bereavement processing — typically require
additional Nucleus operations beyond the close instruction itself. These may include
applying restrictions that prevent further debits or credits, posting correcting
entries, instructing parameter changes that suspend accruals, or recording specific
audit events. Each of these is a well-defined Nucleus operation with its own contract.

Where a configurer's value stream needs a recognised combination of these operations
performed atomically and idempotently — to avoid chattiness and to eliminate the
possibility of a partial sequence leaving the account in an inconsistent state — Nucleus
may expose strongly-typed compound endpoints that orchestrate the constituent operations
behind a single client call. A "regulatory closure" endpoint, for example, might apply a
specific restriction, suspend interest accrual, and then issue the close intent, all
under a single idempotency key.

Compound endpoints are API augmentations. They do not introduce new domain concepts and
they do not give the Account aggregate any awareness of why a configurer chose to invoke
them. The aggregate continues to see only its own well-defined operations: closure
intent, restriction state changes (when these are introduced as a domain concept),
attachment changes, and so on. The categorisation by which a configurer or operator
chooses one compound over another is upstream of Nucleus and is not represented in the
Account context's domain model. Auditing of that categorisation, where required, is the
configurer's or operator's concern, recorded in their own systems alongside the
operator attribution that Nucleus already exposes via `X-Client-ID`.

This framing is what allows the Account context to remain coherent in the face of
business categorisation evolution: new closure scenarios can be added by composing
existing operations into new compound endpoints, without requiring any change to the
Account aggregate or its events.

---

## Accounting Hierarchy

Every account has an accounting code. The accounting code is a hierarchical identifier that
governs the structure of ledger position aggregation: given an accounting code
`LIAB_RETL_SAVE`, Nucleus maintains positions at every prefix (`LIAB`, `LIAB_RETL`,
`LIAB_RETL_SAVE`). When a ledger entry is posted to an account, Nucleus updates the
position at every level of the account's accounting hierarchy. The accounting hierarchy is
how Alex's general-ledger reconciliation is structured — positions at `LIAB`, `LIAB_RETL`,
and so on map to general ledger accounts at the corresponding levels of the bank's chart
of accounts.

The accounting code is structurally identical to a classification code (same segment
format, same validation rules) but is a distinct concept. Three hierarchies share this
syntactic format and must not be conflated:

- **The classification (product) hierarchy** — governs feature resolution.
- **The parameter value hierarchy** — the realisation of the classification hierarchy as a
  resolvable tree of nodes and parameter values.
- **The accounting hierarchy** — governs ledger position aggregation.

For a given account, its classification code and its accounting code may be identical, may
share a common prefix, or may diverge entirely. The choice belongs to the configurer and
is expressed by the value the configurer assigns to the accounting code parameter at the
relevant node.

### The accounting code as a feature property

The accounting code is modelled as a feature property in the Account Feature Catalogue,
resolved by the standard parameter value resolution function. The catalogue declares the
property; configurers write values via the existing account-features API; the Account
context resolves the value at opening and at any subsequent point that requires it. The
property's catalogue definition and applicability are recorded as ADR-025 candidate.

This modelling choice has a consequence: the accounting code inherits hierarchically along
the classification tree like any other feature property. A configurer may set an
accounting code at `LIAB` that is inherited by all liability accounts unless overridden at
a more specific node. This is correct domain behaviour: the structure of ledger position
aggregation typically follows the structure of the product portfolio.

### Ledger-side consistency

The first segment of an account's resolved accounting code must equal the account's ledger
side. An asset account cannot have an accounting code beginning with `LIAB`, and vice
versa. This invariant is enforced at two points:

1. At write time, when an accounting code parameter value is set at a node, Nucleus
   validates that the value's first segment equals the node's ledger side. A mismatch
   causes the write to be rejected.

2. At account opening, when the resolved accounting code is checked against the account's
   ledger side. A mismatch causes opening to be rejected. This second check is structural
   defence: the first check is the principal guard, but resolution may walk to an
   ancestor whose written accounting code somehow violates the constraint, and the
   account-level check ensures no opening proceeds with an inconsistent configuration.

### Accounting code change under active accounts

The accounting code is subject to a constraint not shared by other feature properties: it
cannot be superseded at a node if any non-`CLOSED` account is attached at that node or any
descendant node. This is the principle:

- An account's ledger positions, once accumulated, are recorded under the account's
  accounting code at the time of accumulation.
- Changing the resolved accounting code for an account that has accumulated positions
  does not retroactively migrate those positions; it would orphan them under the previous
  code and start a new set under the new code.
- Migrating positions between accounting codes is not a routine operation. It requires
  out-of-band coordination with the Nucleus installation owners and a controlled freeze
  of the affected accounts.

The constraint applies to non-`CLOSED` accounts (`OPEN` and `PENDING_CLOSURE`):

- `OPEN` accounts may continue to accumulate positions; changing their accounting code
  resolution is the principal harm to be prevented.
- `PENDING_CLOSURE` accounts may still accumulate final positions during closure; the
  same harm applies.
- `CLOSED` accounts have no further activity and their positions are historical facts
  attributable to the accounting code that was resolved at the time of accumulation.
  They do not constrain future supersession.

The first write of an accounting code at a node is always permitted (no supersession
occurs). Subsequent writes are permitted only if no non-`CLOSED` accounts are attached at
the node or any descendant. Out-of-band migration is the only mechanism by which an
accounting code may be changed for active accounts; the API rejects such changes.

This constraint is structurally distinct from the openness categories defined in the
Parameter Value Hierarchy model. The openness categories govern the validity of the
*effective datetime* of a write; this constraint governs whether the *write itself* is
permitted, regardless of effective datetime. A future-effective-dated change to the
accounting code would still take effect for active accounts when its effective datetime
arrived, so it does not escape the harm by being prospective. The constraint must be
expressed and enforced as a structural invariant on supersession, not as an openness
category. Recorded as ADR-026 candidate.

---

## Stakeholder Identity

A stakeholder is not an entity in the Account context. The stakeholder identifier is a
value stored on each account: an opaque reference to the party responsible for the
account. That party is most often an external customer in the upstream customer
management system, but may also be an internal Nucleus subsystem or an internal
operator team that owns accounts to fulfil its own structural requirements (see
Account Function below). The Account context does not maintain a Stakeholder
aggregate, does not track which stakeholders exist, does not govern a stakeholder
lifecycle, and does not enforce any invariant across the set of accounts sharing a
stakeholder identifier.

This is a deliberate choice (ADR-022). The reasons:

- The stakeholder lifecycle is owned by the upstream customer management system. A
  Stakeholder aggregate inside Nucleus would either duplicate that lifecycle (creating
  two sources of truth) or under-specify it (creating gaps where Nucleus believes a
  stakeholder exists but the upstream system has no corresponding record, or vice
  versa).

- Nucleus has no PII. Without name, address, date of birth, or any other identifying
  attribute, a Stakeholder aggregate would be a near-empty entity whose only attribute
  was its own identifier — a structural hollow that would tempt the addition of
  attributes that should not be added.

- The behaviours that suggest a stakeholder might be needed — Single Customer View
  enumeration, aggregate balance across a stakeholder's accounts, regulatory cohort
  reporting — are not lifecycle responsibilities and do not require a Stakeholder
  aggregate to deliver them. Each is addressed without one, by either query or
  materialisation as the nature of the view dictates (see below).

The Account context's stakeholder-level views — the set of accounts whose
stakeholder identifier equals X (Single Customer View enumeration, regulatory
cohort reporting) and the financial aggregates over those accounts (total balance,
aggregate P&L position) — are derived from the account population. The mechanism
of derivation is an implementation matter; the choice between continuous
materialisation and on-demand computation is not prescribed at the domain level
but is governed by three considerations that strongly favour materialisation:

- *Performance.* Summing or scanning across an arbitrarily large account set at
  request time scales poorly with stakeholder portfolio size. A read against a
  materialised aggregate (an aggregation account, a maintained index) scales as a
  single read regardless of portfolio size.
- *Audit.* Every contribution to a materialised aggregate is a posted record with
  attribution and effective dating. A query-time projection has no audit trail;
  the value exists only at the moment of computation and disappears once the
  response is returned.
- *Reconciliation.* Alex reconciles materialised positions in Nucleus against
  positions in the bank's general ledger. The general ledger holds aggregate
  positions; reconciliation requires Nucleus to hold them too, as posted positions
  and not as query-time projections.

The model therefore anticipates that stakeholder-level financial aggregates are
held as positions on per-stakeholder aggregation accounts maintained continuously
by the internal accounting feature, and read as ordinary balance queries against
those accounts. Set-membership views are presumed to benefit from the same
treatment — for example, a materialised index of accounts per stakeholder
maintained alongside the aggregation accounts — unless implementation needs
disclose a reason for a different mechanism. The Account context is agnostic in
principle (no Stakeholder aggregate is required either way) but the model is
opinionated about the direction: materialisation is the default; on-demand
computation must justify itself per view.

### Stakeholder identifier mutability

Because the stakeholder identifier is an opaque tag and Nucleus has no domain knowledge
about the stakeholder, Nucleus has no basis on which to declare that a change of
identifier amounts to a change of account. The judgement that two stakeholder
identifiers refer to "the same" or "different" parties is the upstream customer
management system's, not Nucleus's. Forcing a configurer to close and reopen an account
in response to an upstream identifier change would import that judgement into Nucleus
and would conflict with the ADR-022's premise that the stakeholder is a value
reference, not a domain entity.

The stakeholder identifier on an `OPEN` account may therefore be updated. The change is
direct and synchronous, idempotent against the (operation, idempotency key) pair, and
emits `AccountStakeholderChanged`. The configurer is responsible for the upstream
consequences:

- Stakeholder-account queries return the current stakeholder set; the account moves
  from the prior stakeholder's set to the new stakeholder's set at the moment of the
  change.
- Aggregate views that the configurer or any downstream consumer derives from the
  account set must be recomputed against the post-change state. Nucleus does not
  migrate any derived state, because it maintains none — the derivation is the
  consumer's.
- Historical events and ledger entries preserve the stakeholder identifier in force
  at the time of their emission. The event stream remains an accurate record of who
  was associated with the account when each event occurred. Replaying events from a
  prior point yields the prior attribution.

Updates are rejected on `PENDING_CLOSURE` and `CLOSED` accounts, consistent with the
general principle that closure intent freezes account attributes.

### Stakeholder accounts across ledger sides

A single stakeholder identifier may be associated with accounts on either ledger side.
Casey may have a savings account (`LIAB`-side) and a mortgage (`LIAB`-side, but a
different product family) and a business loan (`ASST`-side, in a different organisational
context — e.g. through Liam's value stream). Nucleus places no constraint on the
combinations of accounts that may share a stakeholder identifier. Whether a "portfolio"
view across ledger sides is meaningful is a reporting concern, not a domain concern; if
Robin or Alex requires such a view, they compute it from the account set.

### Account uniqueness within a stakeholder

A stakeholder may have multiple accounts on the same classification code. Two ISA
accounts on `LIAB_SAVE_INAS_2026_Q1Q2` for the same stakeholder are distinct accounts
with distinct identifiers and distinct lifecycles. Nucleus places no uniqueness invariant
on the pair (stakeholder identifier, classification code).

The reason is that uniqueness at this level is a product policy, not a structural
property. Some products permit multiple instances per stakeholder; others do not; the
configurer's value stream is the right place to enforce that policy, not the core. A
configurer that wishes to prevent duplicates for a particular product class queries
Nucleus for the existing accounts and rejects the second opening at its own boundary.

---

## Account Function

The Account context takes an opinionated stance on the role distinction familiar from
many banking systems — internal accounts versus customer accounts: it does not draw it.
An account is the entity against which ledger entries are recorded; that is the whole
of the Account aggregate's responsibility. Whether a given account represents a
customer's savings product, an internal payments holding account, an aggregation
account for profit-and-loss recording, or a fraud review holding account is not a
distinction the Account context tracks or surfaces.

Every account in Nucleus has the same structural shape. Every account has a UUID, a
stakeholder identifier, a ledger side, a status, an attachment to a parameter node,
and a resolvable accounting code. Every account supports the same operations and
exposes the same lifecycle. The practical consequence for the ledger is that ledger
entries against any account — internal or customer — are retrievable in full from
the same query mechanism. No category of account is treated as a calculated shadow
whose entries are derived rather than stored. Whether to materialise ledger entries
for specific categories of internal accounts is formally a Ledger context
implementation question, but the requirement that entries are uniformly queryable
strongly recommends materialisation in every case. The Account context remains
agnostic either way. Recorded as ADR-030 candidate.

### Internal stakeholders

The stakeholder identifier may refer to an external customer, to an internal Nucleus
subsystem, or to an internal operator team. Nucleus assigns no semantics to the
identifier in any case; the rules that apply to a customer's stakeholder identifier
(opaque, indexable, byte-exact equality, mutable on `OPEN` accounts) apply identically
to an internal subsystem's. Internal stakeholders that the broader system anticipates,
each owning specific account categories whose definition belongs to the contexts
named:

- **The Payments context** owns *payment-operations-holding* accounts that route
  inbound and outbound payments through the system, and *payment-exception-handling*
  accounts that hold unroutable or under-review payments pending resolution.
- **An internal accounting account feature** owns *profit-and-loss* accounts and
  per-customer *aggregation accounts* that record P&L entries driven by accounts'
  accounting codes.
- **A Payments Agent operator team** owns *credit-recovery* accounts (used to send
  and receive payments to and from third parties as part of credit-recovery
  operations) and *write-off* accounts.
- **A Fraud Team** owns *fraud-holding* accounts to which payments are routed when
  particular restrictions are placed on customer accounts.

The Payments Agent and Fraud Team are operator teams analogous to Eddie's customer
support and back-office operators, but distinct in the accounts they own and the
operations they perform. Persona documents for both are not yet authored; their
definition is a prerequisite for any story whose acceptance criteria are anchored to
either team's actions. *Note to story author:* before writing stories that involve
credit recovery, write-off, or fraud holding accounts, define the corresponding
persona.

### Internal account provisioning

Some internal accounts must exist for their owning subsystem to function. The
*payment-operations-holding* account must exist before any payment can be routed; the
internal accounting feature's infrastructure accounts must exist before any P&L entry
can be recorded. The owning subsystem ensures these accounts' existence — typically
at application startup, idempotently — by invoking the standard account opening
operation. The opening's idempotency guarantee makes re-invocation on each restart
safe: an account opened on the first start is recognised on subsequent starts and
the stored response returned without further effect, per the Idempotency context.

A subset of internal accounts must be provisioned in response to customer activity
rather than at startup. The principal example is the per-customer aggregation account
owned by the internal accounting feature, which must exist for any stakeholder against
whom a product account has been opened. The accounting feature is registered as an
opening participant; on a customer account opening, it checks whether an aggregation
account for that stakeholder exists and provisions one if not. Both accounts succeed
or fail as a unit. The provisioning of the aggregation account is itself an account
opening that proceeds through the standard sequence — it may, in turn, invoke any
participants that apply to it. The recursive graph is committed atomically.

The internal accounting feature is implemented through the same infrastructure as any
externally-visible account feature: it lives in the catalogue, its parameter values
resolve via the parameter value hierarchy, and its servicing operates through the
standard servicing machinery. It differs in being always enabled on every account
and in being invisible to the external account-features API. The mechanism by which
the catalogue distinguishes externally-visible features from internal-only features
is a concern of the Account Feature Catalogue domain model and not of this document.

### Lifecycle participants

Account opening and account closure are atomic compositions across context
boundaries. The Account aggregate's own work — validation, state transition,
event emission — is the spine; participants registered by other contexts contribute
work or assert predicates that must complete alongside that spine for the
operation to commit. There are three participant phases, each with its own
semantics:

- **Opening participants** run synchronously within the OPEN transition's
  composite. They contribute work — provisioning related internal accounts,
  scheduling first accruals, registering external addressability — that must
  commit atomically with the new account.
- **Prepare-to-close participants** run synchronously within the
  `OPEN` → `PENDING_CLOSURE` transition's composite. They contribute work
  required to set the account on a closing trajectory: scheduling final
  accruals, suspending payment initiation, marking aggregation positions for
  finalisation. Async work then proceeds in the background, reported via
  events to the precondition projection.
- **Pre-close participants** run synchronously within the
  `PENDING_CLOSURE` → `CLOSED` transition's composite, after the asynchronous
  precondition projection has reported all known preconditions satisfied.
  Pre-close participants assert predicates as a final synchronous check; the
  transition commits only if every pre-close participant asserts positively.
  A pre-close participant that asserts negatively aborts the transition;
  subsequent precondition events may re-trigger, and the pre-close phase is
  re-invoked for a fresh assertion.

The contributing contexts anticipated for the foreseeable future are four:

- **Account Feature Catalogue.** Opening participation for features that need
  to set up servicing schedules or provision related internal accounts (the
  internal accounting feature is the standing example); prepare-to-close
  participation for features that need feature-specific closing setup;
  pre-close participation for features whose finalisation is asserted at the
  moment of close.
- **Account Servicing.** Opening participation to schedule the first accrual;
  prepare-to-close participation to suspend or finalise servicing schedules;
  pre-close participation may also assert that all servicing schedules have
  been finalised.
- **Payments.** Opening participation to register external addressability for
  accounts that require it; prepare-to-close participation to suspend outbound
  payment initiation; pre-close participation may assert that no in-flight
  payments remain.
- **Ledger.** Pre-close participation to assert that every balance address of
  the account holds zero — the canonical pre-close predicate that prevents
  closure from stranding commercial bank money. The substantive predicate is
  owned by Ledger because Ledger is the system of record for balance state;
  Account orchestrates the assertion via the participant model rather than
  reaching into Ledger state itself.

A participant runs within the lifecycle operation's transaction. It may issue
further account-context operations — most commonly further account openings —
which are themselves composite and may invoke their own participants, recursively.
Every operation in the recursive graph commits or rejects as one transaction. A
participant that fails (work-contribution participant) or asserts negatively
(pre-close predicate participant) rejects or aborts the operation accordingly.

The simple case — zero participants — is a valid lifecycle operation. With no
participants registered, opening reduces to validation, derived-property
computation, aggregate creation, and event emission; closure intent reduces to
validation, status transition, and event emission; closure completion reduces to
status transition and event emission. The Account context is fully functional in
this configuration.

The current set of registered participants is small (the internal accounting feature
is the only concrete instance, and only at opening). The implementation of the
participant infrastructure may therefore be deferred — a tdd-implementor working on
an early opening story is not obliged to construct a general participant model
ahead of need. The constraint is forward-compatibility: the simpler implementation
must not foreclose the introduction of additional participants. In particular, the
opening and closing transactions must be structured so that further atomic
contributions can be inserted without re-architecting the lifecycle composite. See
ADR-031 candidate.

### Aggregation account identity — open consideration

The relationship between the aggregation account's Nucleus account UUID and the
external customer identifier supplied by the configurer at customer-account-opening
time is a design point to be resolved during implementation. Two interpretations of
the desired behaviour are credible from the architectural framing:

- The aggregation account has a Nucleus-generated UUID like every other account; the
  customer's external identifier is its stakeholder identifier; the accounting
  feature finds the right aggregation account by querying for accounts owned by the
  accounting feature whose stakeholder identifier matches the customer's. This
  preserves the UUID identity invariant in full and treats the aggregation account
  as structurally identical to any other internal account.
- The aggregation account's primary identifier *is* the customer's external
  identifier, relaxing the UUID invariant for this category of internal account.
  This admits direct addressability of the aggregation account by the same key the
  configurer already holds, but introduces an exception to the otherwise universal
  UUID rule and couples the Account aggregate's identity model to upstream
  identifier formats.

The first interpretation is consistent with the rest of this document and is
preferred unless implementation needs disclose a concrete reason for the second. Either
way, the consideration influences the calibration of stakeholder identifier formats
agreed under OQ-3, since stakeholder identifiers are the lookup key for the first
interpretation and would be the account identifier itself under the second.

### Addressability

External addressability — by sort code and account number (BBAN), or by IBAN — is a
property of the Payments context, not of the Account context. The Account context
exposes the account UUID as the sole identity it owns; it does not record sort
codes, account numbers, IBANs, or any other external addressing scheme. When an
account is required to be externally addressable, the Payments context registers an
address against the account's UUID and maintains the mapping. When a payment arrives
at an external address, the Payments context resolves the mapping to the Nucleus
UUID and posts the corresponding ledger entries against that account.

Some accounts in the system will be externally addressable: a customer's
payment-capable product account, a credit-recovery account that must receive
third-party payments. Others are deliberately not: payment-operations-holding,
payment-exception-handling, profit-and-loss, aggregation, write-off, fraud. The
Payments context governs which accounts are addressable; the Account context does
not record the distinction and does not behave differently in either case. There is
no `externallyAddressable` attribute on the Account aggregate.

This separation reflects the principle that the account, as a ledger target, has a
single structural shape regardless of how it participates in external interaction
patterns. Coupling addressability to the Account aggregate would entangle it with
the Payments scheme's evolution — present BBAN/IBAN, future scheme identifiers, the
introduction of new addressable account categories — and would contaminate the
aggregate with concerns that have nothing to do with whether ledger entries can be
posted to it.

---

## Context Relationships

**Parameter Configuration context (downstream supplier of resolved configuration):** The
Account context queries the Parameter Configuration context's resolution function at
opening, at transfer, and on demand throughout the account's lifecycle. It supplies the
resolution datetime and the account's current node; it receives resolved values for the
keys it requests. The integration pattern is customer/supplier; the Account context is
the downstream customer.

**Parameter Configuration context (upstream consumer of node-attachment writes):** The
Account context drives Node Attachment creation through `AccountOpened` and
`AccountClosed` events that the Parameter Configuration context (currently) reacts to in
its consumer relationship. With the reassignment of Account Node Attachment to the
Account context (ADR-027 candidate), this relationship becomes internal to the Account
context;
the Parameter Configuration context no longer participates in attachment lifecycle.

**Account Feature Catalogue context (upstream supplier of validation and translation):**
The Account context relies on the Feature Catalogue's validation and translation logic
when accepting feature configuration submitted with an opening request. The catalogue
defines what features are valid for the ledger side, validates property types and
openness, and translates the external feature representation into parameter writes. The
integration is customer/supplier; the Account context is the downstream customer.

**Account Servicing context (downstream consumer of lifecycle events and resolved
configuration):** The Account Servicing context consumes `AccountOpened`,
`AccountTransferred`, `AccountClosureIntended`, and `AccountClosed` to drive servicing
behaviour: schedule first accruals, recalculate after transfers, suspend processing on
closure intent, finalise on closure. The Account Servicing context also queries resolved
configuration for accounts via the Parameter Configuration resolution function.

**Account Servicing context (upstream reporter of closure preconditions):** The Account
Servicing context — and any other context responsible for state that gates closure
(Payments, for example, reports settlement clearance) — emits events reporting
precondition satisfaction. The Account context maintains a per-account projection
of these events and autonomously triggers the `PENDING_CLOSURE` → `CLOSED` transition
when all preconditions for a given account are met. The shape of the projection and
the event types subscribed to are the residual concern of ADR-028.

**Ledger context (downstream consumer of opening and accounting code at opening time):**
The Ledger context, which manages ledger positions, requires the account's accounting
code at opening and at any subsequent point at which the resolved accounting code might
change (in practice: at node transfer, where the new node's resolved accounting code is
the new structure for any subsequent positions). The integration is via the
`AccountOpened` and `AccountTransferred` events, which carry the resolved accounting
code at the relevant moment.

**Idempotency context (cross-cutting):** Account opening and closure intent are
idempotent operations and consume the Idempotency service per ADR-014 and ADR-016.
Closure finalisation is autonomous (per the resolution of OQ-2) and is not directly
client-initiated; idempotency does not apply to it.

**Configurer personas (Cameron, Sasha, Liam, Maya — upstream initiators):** Configurers
issue opening, transfer, closure-intent, and account-level configuration instructions
against the Account context. The Account context responds synchronously, returning the
account identifier on opening and the current status on subsequent operations. The
integration is REST per the configurer personas' integration patterns.

**Eddie (upstream initiator for exceptional cases):** Eddie initiates closures that fall
outside the configurer's normal flow. These flow through the same closure-intent
operation as configurer-initiated closures, distinguished by operator attribution via
`X-Client-ID` and, where the operational scenario requires it, by additional Nucleus
operations (restrictions, correcting entries, parameter changes) performed before or
alongside the close instruction. See "Compound closure operations" below.

**Robin and Alex (downstream observers):** Both consume Account lifecycle events.
Robin's interest is cohort and stock reporting (population entries and exits at scale);
Alex's interest is reconciliation (the population must agree with the general ledger's
view of accounts under each accounting hierarchy node). Both must be able to consume the
event stream without per-event variation by ledger side, product family, or the
internal/customer character of the account.

**Internal subsystem stakeholders (Payments context, internal accounting feature —
upstream initiators of internal account openings):** Internal subsystems open and
own accounts on the same operation surface as external configurers. Application
startup is typical for accounts that must exist for the subsystem to function;
provisioning during a customer-account-opening composite is typical for the
accounting feature's per-stakeholder aggregation accounts. See Account Function.

**Payments Agent and Fraud Team (operator personas — to be defined):** Operator
teams that own credit-recovery, write-off, and fraud-holding accounts. Persona
documents are not yet authored. Stories anchored to either team's actions cannot be
written until the corresponding personas are defined.

---

## Open Questions

**OQ-1: Configuration completeness at account opening. RESOLVED.**

Completeness is a facet of validity, not a separate concern, and the Account Feature
Catalogue is the authoritative source for both. At opening, Nucleus resolves the feature
configuration for the account-to-be — account-level overrides over
classification-node-resolved values, with catalogue-declared defaults applied where
present — and validates the resolved map against the catalogue. Validity in this
evaluation includes type correctness, ledger-side applicability, openness compliance,
and conditional inter-property requirements: a feature definition may declare that, for
example, `liabilityInterest.interestRate` must resolve to a value when
`liabilityInterest.enabled` resolves to true. A feature property may also declare a
default that the catalogue applies in lieu of explicit configuration; declaration of
such a default is the catalogue's mechanism for stating that absence has a defined
meaning at this property and is not itself a defect.

Conditional inter-property requirements and catalogue-declared defaults are forward-
looking additions to the Account Feature Catalogue model. The current catalogue document
does not yet express either. Their introduction will require an extension to the
catalogue's property definition contract and a corresponding extension to the validation
sequence in the account-features API. Recorded as ADR-029 candidate, whose scope is
that catalogue extension and the resulting opening-time validation behaviour.

**OQ-2: Mechanism of the `PENDING_CLOSURE` → `CLOSED` transition. RESOLVED.**

The transition is autonomous: the Account context maintains a per-account precondition
projection updated by events from the contexts that own each precondition class
(Account Servicing for accruals and servicing schedules, Ledger for outstanding
balance, Payments for in-flight settlement, the internal accounting feature for
aggregation finalisation), and triggers the transition when all preconditions for a
given account are reported as satisfied. The configurer issues one close instruction
at intent and is informed of completion via the `AccountClosed` event; no second
instruction is required.

The two-step explicit alternative — a separate "finalise closure" instruction
evaluated against preconditions at call time — was rejected. It would invert the
configurer/fulfilment relationship by placing the trigger of a Nucleus-owned state
change into the configurer's hands and would force the configurer to poll or retry
until preconditions held.

The autonomous model leaves a residual design concern, deferred to the ADR-028
candidate: the shape of the precondition projection (which event types the Account
context subscribes to, how the full set of preconditions applicable to a given
account is determined, how the projection is reconciled if events arrive out of
order or are replayed). These are mechanism-level decisions that an ADR is the
appropriate place to record; the principle — autonomous closure — is settled.

**OQ-3: Stakeholder identifier format and length. RESOLVED in principle.**

The constraint that matters is indexability, not storage. The principal query pattern
that motivates carrying the stakeholder identifier on the account in the first place
— "all accounts for stakeholder X" — must execute efficiently against an indexed
lookup. Storage is comparatively trivial: at expected volumes (millions of accounts)
the stakeholder identifier's per-account contribution is negligible against the ledger
entry and balance datasets that will dominate persistent footprint. Format choice is
therefore subordinate to the query plan it must support, not to the bytes it consumes.

The principle that follows: the stakeholder identifier is accepted as an opaque,
byte-comparable string of bounded length. Equality is byte-exact — no normalisation, no
case folding, no whitespace trimming. Two identifiers that differ in any byte are
different identifiers. The configurer is responsible for submitting the same identifier
consistently for the same stakeholder; Nucleus does not cluster or deduplicate
identifiers that "look similar." This is consistent with the ADR-022's
treatment of the stakeholder as an opaque value reference whose semantics are upstream.

The calibration that remains — the exact maximum length and the exact character set
permitted — is an integration detail to be agreed with the upstream customer
management system before account opening is implemented. The bound must be generous
enough to accommodate reasonable upstream schemes (UUIDs, composite identifiers,
typical opaque customer references) and tight enough to keep index pages efficient and
the stakeholder-accounts query plan within working set. The calibration must also
consider the internal accounting feature's use of the stakeholder identifier as the
lookup key for per-customer aggregation accounts (see Account Function), and — should
the second interpretation in that section's "Aggregation account identity" be adopted
during implementation — its potential use as the aggregation account's primary
identifier itself. The character set should exclude values that vary across encoding
boundaries (control characters, mixed Unicode
normalisation forms) so that notionally identical identifiers do not compare unequal
across integration points.

Not an ADR candidate: the principle (indexability over expressiveness, byte-exact
equality) is straightforward; the calibration is integration tuning. If a future
requirement pushes against the principle — e.g. a request to accept arbitrary-length
or structurally-comparable identifiers — that would warrant supersession via an ADR.

---

## ADR Candidates Summary

| Candidate | Decision to be recorded |
|---|---|
| ~~ADR-022~~ | ~~Account aggregate identity and stakeholder reference.~~ Accepted. See ADR-022. |
| ~~ADR-023~~ | ~~Account status lifecycle.~~ Accepted. See ADR-023. |
| ~~ADR-025~~ | ~~Accounting code as a feature property.~~ Accepted. See ADR-025. |
| ~~ADR-026~~ | ~~Accounting code immutability under non-`CLOSED` accounts.~~ Accepted. See ADR-026. |
| ~~ADR-027~~ | ~~Account Node Attachment aggregate is in the Account context.~~ Accepted. See ADR-027. |
| ~~ADR-028~~ | ~~Autonomous closure mechanism.~~ Accepted. See ADR-028. |
| ~~ADR-029~~ | ~~Account opening configuration completeness as a facet of catalogue validity.~~ Accepted. See ADR-029. |
| ~~ADR-030~~ | ~~Account function agnosticism.~~ Accepted. See ADR-030. |
| ~~ADR-031~~ | ~~Lifecycle participant model.~~ Accepted. See ADR-031. |