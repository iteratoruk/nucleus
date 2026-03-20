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
meaning from the individual segments.

**Ledger side.** The first segment of a classification code. Identifies whether the
configuration pertains to the asset or liability side of the ledger. This is an intrinsic
property of a node and, by extension, of any account attached to it.

**Parameter node.** A node in the classification code tree. Has an identity (its
classification code), a parent (implicit from the code structure), and zero or more
parameter values. A node with no explicit values is a valid node — it inherits all values
from ancestors.

**Root node.** A parameter node whose classification code consists of a single segment
(e.g. `SAVE`, `LEND`). The root node for a ledger side is the first explicitly configured
ancestor below the global node.

**Global node.** A tacit, internal node that sits above all root nodes. Its values are
maintained by Nucleus and serve as system-wide fallbacks. No external client may write to
the global node.

**Parameter value.** A key-value pair attached to a parameter node. Every parameter value
carries an effective date — the date from which it governs resolution. A parameter value
is identified by the triple (node, key, effective date).

**Effective date.** The date from which a parameter value is applicable for resolution
purposes. Not the date on which the value was written to Nucleus. These two dates are
distinct and must not be conflated.

**Resolution date.** The date supplied to the resolution function as the "as at" date.
In scheduled processing contexts, this is the business date being processed, not the
wall-clock time at execution. See Resolution Semantics below.

**Applicable value.** The parameter value returned by the resolution function for a given
(account, key, resolution date) triple. Determined by walking up the node tree from the
account's own node toward the global node, returning the first value found for the key
whose effective date is on or before the resolution date.

**Explicit absence.** A deliberately set state at a node indicating that the key has no
value at this level, and that the resolution walk must terminate here rather than
continuing to the parent. Distinct from "not yet configured," which permits the walk to
continue. A node that has never been written for a given key is not configured; a node that
has been explicitly set to absent for a key stops inheritance.

**Account node.** The tacit, most specific level of the hierarchy. An account's own
parameter values, set at opening time or subsequently, sit at this level and take
precedence over all classification-code-level values during resolution.

**Write audit trail.** The record of all values that have ever been submitted for a given
(node, key, effective date) triple, in submission order. The write audit trail is distinct
from the parameter value history: the history is the set of values at different effective
dates; the audit trail is the record of changes to any given triple over time. The active
value for a triple is always the most recently submitted value.

**Node transfer.** The act of moving an account from one parameter node to another after
opening. The account's ledger side does not change on transfer. The prior node attachment
is recorded in the node transfer history.

**Business date.** The date for which a scheduled processing job is executing. Distinct
from the wall-clock time at execution. The business date governs parameter value resolution
in all scheduled processing contexts.

---

## Aggregates

### Parameter Node

**Identity:** The classification code. Two nodes are the same aggregate if and only if
their classification codes are identical. The classification code is immutable once the
node is created.

**Invariants:**

1. The ledger side of a node (the first segment of its classification code) is immutable.
   It may not be changed after the node is created.

2. At most one active parameter value may exist for a given (key, effective date) pair at
   a node at any point in time. A write for an existing (key, effective date) pair is a
   supersession of the prior value; the prior value is not destroyed but is moved to the
   write audit trail and no longer governs resolution.

3. A node whose intermediate ancestors do not yet exist may be created — Nucleus creates
   the intermediate nodes as empty nodes automatically. An empty node is a valid node.

4. Only Nucleus may write to the global node. No external client may submit values for the
   global node.

**Entities within this aggregate:**

- **Parameter Value.** Identified by (key, effective date). Carries the value, the
  effective date, and the write timestamp. Immutable once superseded. The currently active
  value for a (key, effective date) pair is the most recently submitted value for that
  pair; superseded values are retained in the write audit trail.

- **Parameter Value History.** The ordered collection of all parameter values for a given
  key at this node, across all effective dates and across all writes. Provides the temporal
  view of how configuration for a given key has evolved. Not a separate entity — the
  history is the full collection of Parameter Values for a key.

**Value objects:**

- **Classification Code.** Immutable identifier. Validates segment structure (4-character
  uppercase alphanumeric per segment, underscore-delimited). Not a domain object beyond
  its structural constraints.

- **Parameter Key.** Identifies a configurable parameter. Stable within a catalogue
  version. Opaque to this context — the meaning of a key is defined by the Account
  Servicing context that uses it.

- **Parameter Value Entry.** The tuple (value, effective date, write timestamp, author).
  The value itself is typed to the parameter key's declared type; that type constraint is
  enforced at the feature catalogue boundary, not within this aggregate.

- **Explicit Absence Marker.** A sentinelled value that signals deliberate absence for a
  given (key, effective date) pair. Terminates the resolution walk at this node for this
  key. Structurally a Parameter Value whose value is the absence marker; semantically
  distinct from any data value.

**Domain events produced:**

- `NodeCreated` — a new parameter node has been created (including implicitly created
  intermediate nodes). Carries: classification code, ledger side, creation timestamp.
- `ParameterValueSet` — a parameter value has been set or superseded at a node. Carries:
  classification code, key, effective date, new value (or explicit absence marker), write
  timestamp, prior value (if supersession), author.
- `ParameterValueSuperseded` — raised alongside `ParameterValueSet` when the write is a
  supersession rather than a new value. Carries: classification code, key, effective date,
  superseded value, supersession timestamp.

**Domain events consumed:** None. The Parameter Node aggregate is written to exclusively
via the account features API. It does not react to events from other aggregates.

---

### Account Node Attachment

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

Resolution is a pure function. Given (account, key, resolution date), it returns the
applicable parameter value or explicit absence, with no side effects.

The resolution walk proceeds as follows:

1. Begin at the account node (account-level parameter values, if any).
2. If a value or explicit absence exists for the key whose effective date is on or before
   the resolution date, return it. If multiple effective dates qualify, the latest
   qualifying effective date governs.
3. Otherwise, move to the account's current parameter node (the leaf classification node).
4. Apply the same lookup. Return if found.
5. Walk up the tree, repeating the lookup at each ancestor node, until the global node is
   reached.
6. If no value is found at any level (including global), the key is not configured for
   this account at this date. The behaviour in this case is the concern of the consuming
   context, not of Parameter Configuration.

**Explicit absence terminates the walk.** If an explicit absence marker is found at any
level for the qualifying effective date, the walk terminates and "no value" is returned,
regardless of what ancestor nodes may hold.

**The resolution date is always explicit.** It is never implicitly the current wall-clock
time. In an API request context, when no resolution date is supplied by the caller, the
current date is used as the default — but this default is an explicit input substitution,
not an implicit dependency on system time. In a scheduled processing context, the
resolution date is always the business date being processed.

**The distinction between business date and wall-clock time in scheduled processing is a
domain invariant, not an implementation preference.** If end-of-day processing for
business date 2026-04-01 runs at 02:00 on 2026-04-02 (following a delay or retry), all
parameter value resolutions within that job must use 2026-04-01 as the resolution date.
A rate that became effective on 2026-04-01 must be applied to 2026-04-01 processing
regardless of when the job runs. A rate that becomes effective on 2026-04-02 must not be
applied to 2026-04-01 processing even if the job runs after 2026-04-02 begins.

---

## Context Relationships

**Account context (downstream from Parameter Configuration):**
Parameter Configuration supplies resolved configuration to the Account Servicing context
on demand. The Account Servicing context provides the resolution date and the account
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

**OQ-1: Backdating controls.**
Domain reasoning establishes that backdating a parameter value (effective date in the
past) changes the historical record of what configuration governed past processing.
Ledger entries produced under the prior configuration are immutable facts; the
configuration that produced them would appear, retrospectively, to say something
different. Domain reasoning cannot determine: who may authorise a backdated update; what
controls must surround it; whether affected accounts must be identified; whether
reprocessing of affected accounts is mandatory, advisory, or not required; and how the
discrepancy between immutable ledger entries and the revised configuration record is
surfaced to Alex from Accounting. This must be resolved in an ADR before any story
involving parameter value correction or adjustment is implemented.
**→ ADR candidate: Backdating of parameter values.**

**OQ-2: Account-level parameter values on node transfer.**
When an account is transferred from one parameter node to another, its account-level
parameter values (set at opening or subsequently) remain at the account node level. Three
positions are coherent: (a) preserve all account-level values unchanged — they remain the
most specific override; (b) clear all account-level values — the account inherits cleanly
from the new node; (c) revalidate account-level values against the new node's feature
configuration, preserving applicable ones and clearing those no longer valid in the new
context. Each position has legitimate uses and each forecloses scenarios the others
support. This must be resolved in an ADR; the choice has direct implications for how node
transfer is used in practice by all three configurer personas.
**→ ADR candidate: Account-level parameter values on node transfer.**

**OQ-3: Feature catalogue structure — unified vs. per-ledger-side.**
The set of valid features in a `PUT /account-features` request may be defined as a single
unified catalogue (where each feature declares which ledger sides it applies to) or as
two per-ledger-side catalogues (one for assets, one for liabilities). A unified catalogue
with per-feature applicability rules accommodates cross-side features (restriction types,
general behavioural parameters) more cleanly; per-side catalogues make the valid feature
set for any given node immediately apparent without per-feature metadata. This decision
affects catalogue maintenance, versioning, and the validation error messages configurers
receive when they submit inapplicable features. It cannot be resolved by domain reasoning
alone.
**→ ADR candidate: Feature catalogue structure.**

**OQ-4: Hypothetical configuration query endpoint.**
Whether Nucleus should expose `GET /account-features/{classificationCode}?asAt={date}` —
allowing a configurer to verify the resolved configuration that would apply to a
hypothetical account at a given classification code and date, without opening an account —
has not been decided. The use case is legitimate: Sasha, Liam, and Maya all have reason
to verify configuration before opening accounts, particularly after setting future-dated
parameter values. The cost is a read path that performs the full hierarchy traversal
without an account context. This is a capability and prioritisation decision, not a domain
question. It should be resolved before stories about configuration verification are
written, as the absence of this endpoint affects how configurers must validate their
submissions.

---

## ADR Candidates Summary

| Candidate | Decision to be recorded |
|---|---|
| ADR-001: Full parameter value history | A node holds a full temporal history of values per key — multiple values at different effective dates. A single overwritten current value is not sufficient. |
| ADR-002: Backdating of parameter values | The controls, authorisation model, and reprocessing obligations that apply when a parameter value is set with an effective date in the past. |
| ADR-003: Account-level parameter values on node transfer | Whether account-level overrides are preserved, cleared, or revalidated when an account is transferred to a new parameter node. |
| ADR-004: Business date as resolution reference in scheduled processing | Scheduled processing must parameterise parameter resolution by the business date being processed, not by the wall-clock time at execution. |
| ADR-005: Feature catalogue structure | Whether the set of valid features per `PUT /account-features` request is defined as a unified catalogue with per-feature applicability rules or as per-ledger-side catalogues. |
| ADR-006: Explicit absence mechanism | The mechanism by which a child node may suppress inheritance of a parameter value from a parent node — signalling deliberate absence rather than non-configuration. |