# Domain Model: Parameter Value Hierarchy

## Bounded Context

**Parameter Configuration** is responsible for the storage, temporal resolution, and
lifecycle management of product configuration within Nucleus. It owns the classification
code tree, the parameter values attached to each node in that tree, and the resolution
logic that determines which parameter value governs a given account at a given point in
time.

This context does not own accounts. It does not own what the parameter values mean to the
domain that uses them — whether a given value governs an interest rate, a maturity term,
or a fee schedule is the concern of the Account Servicing context. Parameter Configuration
owns that a value exists, what it is, when it became effective, and how to find the most
applicable one for a given account and date.

This context does not own the feature catalogue API contract. The `PUT /account-features`
and `PATCH /account-features` endpoints surface a strongly-typed external view of
configuration. The mapping from that external view to the internal parameter key-value
representation is a translation responsibility that sits at the boundary of this context.
The catalogue itself — the set of named, validated account features that Nucleus supports
— is defined externally to this model and versioned independently.

What this context explicitly does not own: the determination of whether a configuration is
semantically valid for a product domain (e.g. whether an interest rate is commercially
reasonable), business rules that govern when a parameter value may change, and any
downstream processing triggered by a parameter value becoming effective.

---

## Ubiquitous Language

**Classification code.** A dot-free, underscore-delimited string of 4-character uppercase
alphanumeric segments. Each segment corresponds to a level in the parameter node tree.
The classification code is the primary key of a parameter node and the link between an
account and its configuration. It is a key, not a domain object: Nucleus does not infer
meaning from the individual segments, with one deliberate exception — the ledger side.

**Ledger side.** The first segment of a classification code. The ledger side is the one
element of a classification code from which Nucleus explicitly infers semantics: it
identifies whether the configuration pertains to the asset or liability side of the
ledger, and the account-features API uses it to determine which catalogue features are
valid for a given submission. This is an intrinsic property of a node and, by extension,
of any account attached to it. No other element of the classification code is interpreted
by Nucleus. The ledger side is a closed two-value enumeration: `ASST` (asset) and `LIAB`
(liability). See ADR-011.

**Parameter node.** A node in the classification code tree. Has an identity (its
classification code), a parent (implicit from the code structure), and zero or more
parameter values. A node with no explicit values is a valid node — it inherits all values
from ancestors.

**Root node.** A parameter node whose classification code consists of a single segment
(e.g. `ASST`, `LIAB`). The root node for a ledger side is the first explicitly configured
ancestor below the global node.

**Global node.** A tacit, internal node that sits above all root nodes. Its values are
maintained by Nucleus and serve as system-wide fallbacks. No external client may write to
the global node.

**Parameter value.** A key-value pair attached to a parameter node. Every parameter value
carries an effective datetime — the datetime from which it governs resolution. A parameter value
is identified by the triple (node, key, effective datetime).

**Effective datetime.** The UTC timestamp (to second precision) from which a parameter
value is applicable for resolution purposes. Not the datetime on which the value was
written to Nucleus. These two datetimes are distinct and must not be conflated.

**Resolution datetime.** The UTC timestamp supplied to the resolution function as the "as at" point in time.
In scheduled processing contexts, this is the business date being processed, not the
wall-clock time at execution. See Resolution Semantics below.

**Applicable value.** The parameter value returned by the resolution function for a given
(account, key, resolution datetime) triple. Determined by walking up the node tree from the
account's own node toward the global node, returning the first value found for the key
whose effective datetime is on or before the resolution datetime.

**Explicit absence.** A deliberately set state at a node indicating that the key has no
value at this level, and that the resolution walk must terminate here rather than
continuing to the parent. Distinct from "not yet configured," which permits the walk to
continue. A node that has never been written for a given key is not configured; a node that
has been explicitly set to absent for a key stops inheritance.

**Account node.** The tacit, most specific level of the hierarchy. An account's own
parameter values, set at opening time or subsequently, sit at this level and take
precedence over all classification-code-level values during resolution.

**Processing boundary.** A named category of scheduled processing. When all processing
of a given class completes successfully for a given business date, the context that owns
that processing emits a boundary completion event for the boundary name and business date.
Parameter Configuration consumes these events and maintains a boundary projection: a map
from boundary name to the most recent closure timestamp for that boundary. An account
feature property governed by a named boundary may not be set with an effective datetime
whose business date is on or before the most recent closure timestamp for that boundary.
Named processing boundaries in current use: `BUSINESS_DAY_CLOSE` (closed by end-of-day
processing, signalled by the `PeriodClosed` event defined in ADR-002), with `WEEK_CLOSE`,
`MONTH_CLOSE`, `QUARTER_CLOSE`, `YEAR_CLOSE`, and `TAX_YEAR_CLOSE` reserved for future
processing contexts.

**Openness category.** A property of an account feature property definition that governs
which effective datetimes are permitted at write time. Three categories exist:

- `GLOBAL`: no constraint. Backdating to any effective datetime is always permitted,
  regardless of the closure state of any processing boundary. `GLOBAL` is the absence
  of a processing scope constraint, not a boundary name. It is the default for
  properties with no explicit openness declaration.
- Named boundary category (e.g. `BUSINESS_DAY_CLOSE`): backdating is permitted only
  within the open window. The submitted effective datetime's business date must be
  strictly after the most recent closure timestamp for the named boundary.
- `PROSPECTIVE_ONLY`: the submitted effective datetime must be strictly after the
  current wall-clock time at write time. Past effective datetimes are unconditionally
  rejected. Applies to properties whose retrospective change would corrupt derived
  internal properties already stored for open accounts.

The constraint is enforced per property by the account-features API layer; see
ADR-017, ADR-018, ADR-020.

**Derived internal property.** A value that Nucleus calculates from one or more account
feature property values at a defined lifecycle event (typically account opening) and
stores as an immutable fact in the Account context. A derived internal property is not
a parameter value in the hierarchy. The existence of a derived internal property is
the criterion for classifying its originating feature property as `PROSPECTIVE_ONLY`:
if a retroactive effective datetime change to the feature property would corrupt the
stored derived value, the property must be `PROSPECTIVE_ONLY`. See ADR-019.

**Write audit trail.** The record of all values that have ever been submitted for a given
(node, key, effective datetime) triple, in submission order. The write audit trail is distinct
from the parameter value history: the history is the set of values at different effective
dates; the audit trail is the record of changes to any given triple over time. The active
value for a triple is always the most recently submitted value.

**Node transfer.** The act of moving an account from one parameter node to another after
opening. The account's ledger side does not change on transfer. The prior node attachment
is recorded in the node transfer history.

**Business date.** The date for which a scheduled processing job is executing. Distinct
from the wall-clock time at execution. The business date governs parameter value resolution
in all scheduled processing contexts.

**Closed period.** A processing period for which Nucleus has completed all scheduled
processing and whose financial record is considered final. A parameter value whose
effective datetime falls within a closed period cannot be set or superseded. A period that has
not been closed is an open period, regardless of whether its business date precedes the
current wall-clock date. The definition of what constitutes period close — and which
context owns and signals it — is an open governance question; see ADR-002.

---

## Aggregates

### Parameter Node

**Identity:** The classification code. Two nodes are the same aggregate if and only if
their classification codes are identical. The classification code is immutable once the
node is created.

**Invariants:**

1. The ledger side of a node (the first segment of its classification code) is immutable.
   It may not be changed after the node is created.

2. At most one active parameter value may exist for a given (key, effective datetime) pair at
   a node at any point in time. A write for an existing (key, effective datetime) pair is a
   supersession of the prior value; the prior value is not destroyed but is moved to the
   write audit trail and no longer governs resolution.

3. A node whose intermediate ancestors do not yet exist may be created — Nucleus creates
   the intermediate nodes as empty nodes automatically. An empty node is a valid node.

4. Only Nucleus may write to the global node. No external client may submit values for the
   global node.

5. A parameter value may not be set or superseded if the submission violates the openness
   constraint declared for its parameter key in the feature catalogue. Three forms of
   constraint apply, determined by the openness category of the property associated with
   the parameter key:

   - **`GLOBAL` properties**: no constraint. Any effective datetime is permitted. This is
     the default for properties with no explicit openness declaration.
   - **Named boundary-governed properties** (e.g. `BUSINESS_DAY_CLOSE`): the effective
     datetime's business date must be strictly after the most recent closure timestamp
     maintained by Parameter Configuration for the named boundary. A past effective
     datetime within the open window is late registration — it carries no consistency risk
     and is permitted. A past effective datetime on or before the closure timestamp is
     rejected. See ADR-002 (BUSINESS_DAY_CLOSE case) and ADR-017 (general model).
   - **`PROSPECTIVE_ONLY` properties**: the effective datetime must be strictly after the
     current wall-clock time at write time. Past effective datetimes are rejected
     unconditionally, regardless of boundary state. See ADR-018.

   The openness constraint is enforced by the account-features API layer during validation,
   before any write reaches the Parameter Node aggregate. All openness violations in a
   submission are collected and returned together; any violation causes the entire
   submission to be rejected. See ADR-020.

**Entities within this aggregate:**

- **Parameter Value.** Identified by (key, effective datetime). Carries the value, the
  effective datetime, and the write timestamp. Immutable once superseded. The currently active
  value for a (key, effective datetime) pair is the most recently submitted value for that
  pair; superseded values are retained in the write audit trail.

- **Parameter Value History.** The ordered collection of all parameter values for a given
  key at this node, across all effective datetimes and across all writes. Provides the temporal
  view of how configuration for a given key has evolved. Not a separate entity — the
  history is the full collection of Parameter Values for a key.

**Value objects:**

- **Classification Code.** Immutable identifier. Validates segment structure (4-character
  uppercase alphanumeric per segment, underscore-delimited). Not a domain object beyond
  its structural constraints.

- **Parameter Key.** Identifies a configurable parameter. Stable within a catalogue
  version. Opaque to this context — the meaning of a key is defined by the Account
  Servicing context that uses it.

- **Parameter Value Entry.** The tuple (value, effective datetime, write timestamp, author).
  The value itself is typed to the parameter key's declared type; that type constraint is
  enforced at the feature catalogue boundary, not within this aggregate.

- **Explicit Absence Marker.** A sentinelled value that signals deliberate absence for a
  given (key, effective datetime) pair. Terminates the resolution walk at this node for this
  key. Structurally a Parameter Value whose value is the absence marker; semantically
  distinct from any data value.

**Domain events produced:**

- `NodeCreated` — a new parameter node has been created (including implicitly created
  intermediate nodes). Carries: classification code, ledger side, creation timestamp.
- `ParameterValueSet` — a parameter value has been set or superseded at a node. Carries:
  classification code, key, effective datetime, new value (or explicit absence marker), write
  timestamp, prior value (if supersession), author.
- `ParameterValueSuperseded` — raised alongside `ParameterValueSet` when the write is a
  supersession rather than a new value. Carries: classification code, key, effective datetime,
  superseded value, supersession timestamp.

**Domain events consumed:** None. The Parameter Node aggregate is written to exclusively
via the account features API. It does not react to events from other aggregates.

---

### Account Node Attachment

> **Note (per ADR-027).** This aggregate has been reassigned to the Account bounded
> context. The authoritative definition is in the Account domain model
> (`docs/architecture/account.md`). The text below is retained here for historical
> reference; the section will be removed in a future revision of this document.

This aggregate governs the relationship between an account and its current parameter node,
and the history of that relationship. It is distinct from the account aggregate itself
(which belongs to the Account context) and from the Parameter Node aggregate.

**Identity:** The account identifier.

**Invariants:**

1. An account's ledger side does not change. A node transfer must not change the ledger
   side of the account's attachment (i.e. the first segment of the origin node and the
   destination node must be identical).

2. The destination node of a transfer must exist at the time of transfer. Nucleus does
   not create nodes on transfer.

3. An account may not be transferred after it is closed.

4. The attachment history is append-only. Prior attachments are not removed.

**Entities within this aggregate:**

- **Node Attachment.** A record of an account being attached to a specific parameter node
  from a specific point in time. Carries: account identifier, classification code,
  attachment timestamp, detachment timestamp (null while current).

**Value objects:**

- **Attachment Period.** The interval [attachment timestamp, detachment timestamp). Open
  at the right end while the attachment is current.

**Domain events produced:**

- `AccountTransferred` — an account has been moved from one parameter node to another.
  Carries: account identifier, origin classification code, destination classification code,
  effective timestamp, the classification codes of all intermediate nodes traversed (if
  any). All configurer personas that hold accounts must consume this event.

**Domain events consumed:**

- `AccountOpened` (from Account context) — triggers creation of the initial Node
  Attachment record for the account.
- `AccountClosed` (from Account context) — seals the current Node Attachment record,
  setting the detachment timestamp and preventing further transfers.

---

## Resolution Semantics

Resolution is a pure function. Given (account, key, resolution datetime), it returns the
applicable parameter value or explicit absence, with no side effects.

The resolution walk proceeds as follows:

1. Begin at the account node (account-level parameter values, if any).
2. If a value or explicit absence exists for the key whose effective datetime is on or before
   the resolution datetime, return it. If multiple effective datetimes qualify, the latest
   qualifying effective datetime governs.
3. Otherwise, move to the account's current parameter node (the leaf classification node).
4. Apply the same lookup. Return if found.
5. Walk up the tree, repeating the lookup at each ancestor node, until the global node is
   reached.
6. If no value is found at any level (including global), the key is not configured for
   this account at this date. The behaviour in this case is the concern of the consuming
   context, not of Parameter Configuration.

**Explicit absence terminates the walk.** If an explicit absence marker is found at any
level for the qualifying effective datetime, the walk terminates and "no value" is returned,
regardless of what ancestor nodes may hold.

**The resolution datetime is always explicit.** It is never implicitly the current wall-clock
time. In an API request context, when no resolution datetime is supplied by the caller, the
current datetime is used as the default — but this default is an explicit input substitution,
not an implicit dependency on system time. In a scheduled processing context, the
resolution datetime is always the business date being processed.

**The distinction between business date and wall-clock time in scheduled processing is a
domain invariant, not an implementation preference.** If end-of-day processing for
business date 2026-04-01 runs at 02:00 on 2026-04-02 (following a delay or retry), all
parameter value resolutions within that job must use 2026-04-01 as the resolution datetime.
A rate that became effective on 2026-04-01 must be applied to 2026-04-01 processing
regardless of when the job runs. A rate that becomes effective on 2026-04-02 must not be
applied to 2026-04-01 processing even if the job runs after 2026-04-02 begins.

---

## Processing Boundaries

A processing boundary is a named category of scheduled processing. When all processing
of a given class completes successfully for a given business date, the context that owns
that processing emits a boundary completion event carrying the boundary name and the
closed business date. Parameter Configuration consumes these events and maintains its own
boundary projection — a durable map from boundary name to the most recent closure
timestamp for that boundary. The projection is updated by event consumption; it is never
inferred from parameter write history or resolution call patterns, and it is never queried
from the Account Servicing context or any other context.

Boundary lifecycle events:
- **Boundary completion event**: records the closed business date for the named boundary.
  Subsequent completion events for the same boundary advance the closure timestamp.
  Duplicate completion events for the same (boundary, business date) pair are idempotent.
- **Boundary reopening event**: reverses a closure for the named boundary and business
  date, re-opening the window for that date. A reopening event for a pair not currently
  closed is idempotent.

The `PeriodClosed` and `PeriodReopened` events defined in ADR-002 are the concrete
instantiation of this pattern for the `BUSINESS_DAY_CLOSE` boundary. Their event names,
payload structure, and idempotency guarantees are established by ADR-002 and are not
changed here.

Named boundaries in current use: `BUSINESS_DAY_CLOSE`. The following are reserved for
future processing contexts whose closure cadence differs from daily end-of-day:
`WEEK_CLOSE`, `MONTH_CLOSE`, `QUARTER_CLOSE`, `YEAR_CLOSE`, `TAX_YEAR_CLOSE`. A named
boundary with no production source behaves as permanently open; its introduction requires
a corresponding event production path.

---

## Openness Categories

The openness category of a feature property governs which effective datetimes are valid
at write time. It is declared on the property definition in the feature catalogue and is
not configurable at submission time. The default for properties with no explicit
declaration is `GLOBAL`.

**`GLOBAL`.** No processing scope constraint applies. Backdating to any effective datetime
is always permitted, regardless of the closure state of any processing boundary. `GLOBAL`
is the explicit absence of a processing scope constraint, not a boundary name. It is the
correct category for properties that do not feed any processing class that produces
finalised outputs, and the provisional default for properties whose processing scope has
not yet been identified. See ADR-017.

**Named boundary categories** (e.g. `BUSINESS_DAY_CLOSE`). The submitted effective
datetime's business date (the date portion of the UTC datetime) must be strictly after
the most recent closure timestamp for the named boundary. A past effective datetime within
the open window is late registration: permitted, carrying no consistency risk, because the
processing class has not yet finalised results for that business date. A past effective
datetime on or before the closure timestamp is rejected with a structured error identifying
the property, the boundary category, and the closed business date.

**`PROSPECTIVE_ONLY`.** The submitted effective datetime must be strictly after the current
wall-clock time at write time. The comparison is on full datetimes (UTC, to second
precision), not on business dates. A past effective datetime is unconditionally rejected
regardless of boundary state. Future effective datetimes are unconditionally permitted.
`PROSPECTIVE_ONLY` is not a boundary-governed category; it does not consult the boundary
projection. It is a structural constraint arising from the existence of derived internal
properties. See ADR-018 and the Derived Internal Properties section below.

**Per-property validation.** When a submission contains properties with mixed openness
categories, each property is validated against its own declaration independently. Any
violation causes the entire submission to be rejected. All violations are reported
together. See ADR-020.

---

## Derived Internal Properties

A derived internal property is a value that Nucleus calculates from one or more account
feature property values at a defined lifecycle event and stores as an immutable fact in
the Account context. It is not a parameter value: it belongs to the Account context, not
the Parameter Configuration context, and is not subject to resolution, supersession, or
direct configuration by external clients.

**The identification principle.** A feature property P gives rise to a derived internal
property when:

1. Nucleus computes a value from P and account-specific data at a defined lifecycle event
   (typically account opening), using the values resolved from the parameter hierarchy at
   that moment.
2. The computed value is stored immutably in the Account context as a fact about the
   account from that point forward.
3. A retroactive change to P's effective datetime — setting an effective datetime that
   precedes the lifecycle event at which derivation occurred — would make the stored
   derived value inconsistent with what would have been calculated had the revised
   configuration been in force at the lifecycle event.

When all three conditions are met, P must be classified `PROSPECTIVE_ONLY`. This is the
direct consequence of the derived value's immutability. A property that does not
contribute to any derived internal property must not be classified `PROSPECTIVE_ONLY`;
doing so removes late registration capability without a corresponding benefit.

**Maturity date.** The first identified derived internal property. Calculated at account
opening as `maturity_date = opening_datetime + fixed_term_period`, where the fixed term
period is the value of the `fixedTerm.termPeriod` feature property resolved from the
parameter hierarchy at opening time. The result is stored immutably against the account
in the Account context. Therefore, `fixedTerm.termPeriod` is classified `PROSPECTIVE_ONLY`.
Accounts open when a new fixed term period configuration takes effect retain their
opening-time maturity dates; the new configuration applies only to accounts opened on or
after the new effective datetime. The fixed term period feature and the maturity date are
not yet implemented; this section is their first architectural definition. See ADR-019.

---

## Context Relationships

**Account context (downstream from Parameter Configuration):**
Parameter Configuration supplies resolved configuration to the Account Servicing context
on demand. The Account Servicing context provides the resolution datetime and the account
identity; Parameter Configuration performs the resolution and returns the applicable
values. The Account Servicing context does not hold a copy of configuration — it queries
Parameter Configuration at resolution time. The integration pattern is
customer/supplier: Account Servicing is the downstream customer of the resolution service.

**Account context (upstream from Parameter Configuration) — node attachment lifecycle:**
The Account context drives Node Attachment creation and sealing via `AccountOpened` and
`AccountClosed` events. In this direction, the Account context is upstream and Parameter
Configuration is conformist: it reacts to account lifecycle events it does not control.

**Configurer personas (Cameron, Sasha, Liam, Maya) — upstream:**
Configurers write to the Parameter Node aggregate via the account features API. They are
upstream of this context in the traditional sense — they supply configuration that Nucleus
honours. The interface is narrow: `PUT /account-features/{classificationCode}` and the
corresponding `PATCH` endpoint. Nucleus validates the submission against the feature
catalogue and translates it to parameter key-value pairs. The configurer personas do not
interact with the internal parameter model directly.

**Account Servicing context — `AccountTransferred` consumer:**
All configurer personas must consume `AccountTransferred`. The Account Servicing context
must also be informed of node transfers, as a transfer may change the resolved
configuration for in-flight processing. The `AccountTransferred` event is the integration
mechanism; the Account Servicing context's response to it is outside this model.

---

## Open Questions

**OQ-1: Backdating controls. RESOLVED.**
Parameter values with effective datetimes in closed periods are not permitted. Nucleus rejects
any submission whose effective datetime falls within a period that has been closed — this is
the point at which the financial record is final and the consistency risk of a
configuration change is irreconcilable without mandatory reprocessing of immutable ledger
entries. A submission with an effective datetime in the past but within an open period is
permitted: this is late registration, carries no consistency risk, and must not be
conflated with backdating.

The domain question of *whether* to permit backdating is therefore resolved: it is not
permitted. What remains as a policy question — and the subject of ADR-002 — is the
definition and governance of "closed period": what event or condition closes a period,
which context owns and signals that close, and whether Parameter Configuration enforces
this boundary autonomously or in response to an explicit signal from the processing
context.

**OQ-2: Account-level parameter values on node transfer. RESOLVED.**
Account-level parameter values are preserved unchanged on node transfer. They remain at
the account node level and continue to take precedence over all classification-code-level
values during resolution, exactly as before the transfer. The account node is the most
specific level of the hierarchy; its contents are a property of the account, not of the
node the account is attached to, and a transfer does not alter the account's own state.

A configurer that wishes to clear or change account-level values following a transfer may
do so explicitly via the account features API after the transfer is complete.

**OQ-3: Feature catalogue structure — unified vs. per-ledger-side. RESOLVED.**
The feature catalogue is unified. Features whose domain meaning genuinely differs between
asset and liability accounts are expressed as distinct catalogue entries named to reflect
that distinction (e.g. an asset interest feature for lending accounts, a liability interest
feature for savings and current accounts). Features whose domain meaning is uniform across
ledger sides appear as single catalogue entries.

The account-features API enforces feature validity before writing. Because configuration
is always submitted against a classification code, the ledger-side element of that code
is available at submission time. The ledger side is the one element of the classification
code from which Nucleus infers semantics; the API uses it to determine which catalogue
features are valid for the submission and rejects any feature not applicable to that
ledger side with a structured, actionable error. No explicit per-feature applicability
metadata is required — the feature's scope is expressed through its name and position in
the catalogue, and enforced through the ledger-side prefix at submission time.

**OQ-4: Hypothetical configuration query endpoint. RESOLVED.**
Nucleus exposes `GET /account-features/{classificationCode}?asAt={date}`, returning the
resolved account features for a hypothetical account at the given classification code and
effective datetime. The `asAt` parameter defaults to the current date if not supplied. The
traversal begins at the classification node; account-level values are not included. The
response is in the same strongly-typed account features representation used by all other
account-features endpoints. See ADR-007.

---

## Open Questions

**OQ-5: Account Node Attachment package placement. RESOLVED.**

The Account Node Attachment aggregate is reassigned from the Parameter Configuration
bounded context to the Account bounded context, per ADR-027. The aggregate's identity
is the account, its invariants are about account state and lifecycle, and its principal
collaborators are within the Account context. The Parameter Configuration context retains
ownership of parameter nodes and their values; it does not participate in attachment
lifecycle. The provisional assignment in this document is superseded by ADR-027; this
document and any implementation that placed the aggregate in `iterator.nucleus.parameters`
must be updated to reflect the reassignment.

---

## ADR Candidates Summary

| Candidate | Decision to be recorded |
|---|---|
| ADR-001: Full parameter value history | A node holds a full temporal history of values per key — multiple values at different effective datetimes. A single overwritten current value is not sufficient. |
| ADR-002: Closed period governance | The definition of what constitutes period close, which context owns and signals it, and how Parameter Configuration enforces the boundary against submissions with effective datetimes in closed periods. |
| ~~ADR-003: Account-level parameter values on node transfer~~ | ~~Resolved: preserved unchanged. See OQ-2.~~ |
| ADR-004: Business date as resolution reference in scheduled processing | Scheduled processing must parameterise parameter resolution by the business date being processed, not by the wall-clock time at execution. |
| ~~ADR-005: Feature catalogue structure~~ | ~~Resolved: unified catalogue, with side-specific features named distinctly and validity enforced by the ledger-side prefix at submission time. See OQ-3.~~ |
| ADR-006: Explicit absence mechanism | The mechanism by which a child node may suppress inheritance of a parameter value from a parent node — signalling deliberate absence rather than non-configuration. |
| ADR-007: Hypothetical configuration query endpoint | Nucleus exposes `GET /account-features/{classificationCode}?asAt={date}` to allow configurers to verify resolved configuration without opening an account. |
| ADR-012 | Package structure and bounded context boundaries: the top-level package layout, the dependency rules between bounded contexts, and the rationale for flat compound package names over nested package hierarchies. |
| ADR-017 | Processing boundary model and openness categories: three distinct categories (`GLOBAL`, named boundary, `PROSPECTIVE_ONLY`); `GLOBAL` as the permissive default; `BUSINESS_DAY_CLOSE` as the boundary for end-of-day processing; the boundary projection maintained by Parameter Configuration. |
| ADR-018 | `PROSPECTIVE_ONLY` openness category: effective datetime must be strictly after wall-clock time at write; applies to properties whose retrospective change corrupts derived internal properties; wall-clock constraint is sufficient without per-account knowledge. |
| ADR-019 | Derived internal properties: identification principle; Account context ownership; maturity date as first instance; any contributing property must be `PROSPECTIVE_ONLY`. |
| ADR-020 | Per-property openness validation in mixed-category submissions: each property validated against its own category; total rejection on any failure; exhaustive per-property error attribution. |