# ADR-006: Explicit Absence Mechanism

**Date:** 2026-03-20
**Status:** Accepted

## Context

The parameter value hierarchy resolves configuration by walking up the node tree from the
account's own node toward the global node, returning the first value found for a given key
whose effective date is on or before the resolution date. If no value is found at a given
level, the walk continues to the parent.

This means a child node cannot prevent inheritance of a parent's value by simply not
configuring a key. The absence of an entry at the child level is not a signal — it is
the absence of a signal, and the walk continues upward regardless.

There are legitimate domain cases where a child node must suppress a feature that a parent
node configures. A parent classification node may define an early withdrawal penalty that
applies across a product family; a specific product tier under that node is a promotional
offering that carries no such penalty. A parent node may configure a restriction type that
applies to most products in its family but is explicitly inapplicable to one. In each
case, the child node needs to make a positive, deliberate assertion: "this key has no
value here, and the walk must not continue to the parent."

Two states must therefore be distinguishable at every node, for every key:

1. **Not configured.** No decision has been made about this key at this node. The walk
   continues to the parent. This is the state of every key at a newly created node.

2. **Explicitly absent.** A deliberate decision has been made that this key has no value
   at this node, and that the parent's value must not be inherited. The walk terminates
   here.

These states have different resolution semantics. They cannot be collapsed into one
without losing the ability to express deliberate inheritance suppression.

The question this ADR resolves is how explicit absence is represented in the parameter
store — what mechanism gives the resolution walk a positive, durable signal to terminate.

## Decision

Explicit absence is represented as a parameter value whose value field contains a reserved
absence marker. It is stored in the same structure as any other parameter value, identified
by the same triple (node, key, effective date), and governed by the same rules: it carries
an effective date, a write timestamp, and an author; it participates in supersession (an
explicit absence can be superseded by a real value, restoring inheritance, or by a
subsequent explicit absence with a later effective date); it is subject to the
closed-period restriction; and it is visible in the parameter value history for the key.

The resolution walk, upon finding a parameter value at a given level, checks whether the
value is the absence marker before returning. If it is, the walk returns "no value" and
terminates — it does not continue to the parent. If it is a real value, the walk returns
that value and terminates. If no parameter value exists at this level for the qualifying
effective date, the walk continues to the parent unchanged.

The absence marker is a reserved value distinct from any value that a valid feature
configuration may produce. It is not null, not an empty string, and not representable
by a configurer submitting a normal feature value through the account-features API.

The account-features API must expose a mechanism for configurers to set explicit absence
for a specific key at a node. This is distinct from omitting a key from a PUT request
(which leaves the existing value or inherited value unchanged, depending on the
operation's semantics) and from any RFC 6902 PATCH operation that removes a key from the
represented document (which may or may not signal explicit absence, depending on how the
PATCH endpoint maps operations to parameter store writes). The precise API expression of
explicit absence is a design detail for the implementation session; the requirement is
that configurers can express it, and that the API's handling of omission versus explicit
absence is unambiguous in its documentation.

## Consequences

**Positive:**

- The resolution walk has a single, uniform entry structure at every level: a parameter
  value, carrying a value or an absence marker, with effective date, write timestamp,
  and author. No additional entity type is introduced. The walk checks one thing at each
  level.
- Explicit absence participates fully in the temporal model. A node may configure a key
  as explicitly absent from a future effective date — suppressing inheritance starting on
  a defined date without affecting current behaviour. This supports the same pre-scheduling
  pattern as real values: a product that will lose a feature from a future date can have
  that suppression registered in advance.
- Restoring inheritance is expressible and reversible. An explicit absence marker can be
  superseded by a real value, which re-enables resolution to find the value at this level,
  or by removing the explicit absence such that the walk resumes passing through this node
  to its parent. The write audit trail preserves the full history of these transitions.
- The closed-period restriction applies uniformly. Explicit absence markers set with
  effective dates in closed periods are rejected at write time by the same rule that
  applies to all parameter value writes, with no special-casing.

**Negative:**

- The resolution walk must distinguish between the absence marker and a real value at
  each level. This is a check against the reserved marker value, not a type distinction,
  which means the marker must be reliably reserved and never producible by a legitimate
  feature configuration write.
- The account-features API must expose explicit absence as a first-class operation, with
  clear documentation distinguishing it from omission. This adds a concept that
  configurers must understand: there are three distinct things they can express for a
  given key — "I am providing a value," "I am not mentioning this key" (omission), and
  "I am asserting that this key has no value here." The third is conceptually less
  obvious than the first two.
- The parameter value history for a key at a node may now contain a mixture of real
  values and absence markers across its effective date timeline. Queries that display
  this history must render absence markers meaningfully rather than displaying the raw
  sentinel, and must make clear to the reader what each entry represents.

**Risks:**

- **Unintended inheritance suppression.** A configurer who sets explicit absence at an
  intermediate node suppresses inheritance for all accounts attached to that node and all
  descendant nodes that do not themselves configure the key. If the intent was to suppress
  inheritance only for accounts at a specific leaf node, setting the marker at an
  intermediate node is an error with wide effect. The account-features API should surface
  the scope of the operation — which nodes and accounts will be affected — before the
  write is accepted, or at minimum make the scope unambiguous in the API documentation
  and in the confirmation response.
- **Confusion between absence and misconfiguration.** If the absence marker is not
  clearly surfaced in operational tooling, an operator or auditor inspecting the parameter
  store may interpret an explicitly absent key as a misconfiguration or data gap rather
  than a deliberate decision. The distinction must be visible in any read representation
  of the parameter value history: "not configured" (no entry) and "explicitly absent"
  (entry with absence marker) must be rendered differently.
- **Supersession of absence by a future real value.** If an explicit absence with effective
  date D is superseded by a real value with effective date D+N, the node will resolve the
  real value from D+N onward and suppress inheritance between D and D+N. This is the
  intended behaviour, but it means the timeline for a key at a node may alternate between
  real values, absent periods, and inherited periods in ways that are not immediately
  apparent from a flat list of entries. Resolution behaviour should be testable and
  explainable for such timelines.

## Alternatives Considered

**Deletion of the parameter value record.** When a configurer wants to suppress
inheritance at a node, they delete the parameter value for the key at that node. The
resolution walk, finding no entry, continues to the parent — or, with a "delete means
stop" convention, terminates. Deletion cannot express "stop the walk" by itself: finding
no entry at a level is already the signal to continue. There is no way, from the absence
of a record, to distinguish "no decision made" from "deliberately suppressed." A
"delete means stop" convention requires storing something to represent the deletion — a
tombstone — which is the sentinel value approach under a different name, without the
temporal model (a tombstone with no effective date cannot express "absent from date D").
Rejected: deletion cannot express the required semantics without reintroducing a stored
marker.

**Null value in the value field.** Explicit absence is stored as a parameter value with a
null in the value field, and the resolution walk treats null as the absence marker. This
is a degenerate form of the sentinel approach. It is rejected because null has an
established ambiguity in storage systems (null as missing data, null as an explicit
assertion, null as an uninitialised field) and because it does not survive all
serialisation and deserialisation round-trips without a defined convention. A reserved,
non-null absence marker is unambiguous in a way that null is not.

**A separate explicit-absence entity.** Explicit absence is stored as a distinct entity
type — separate from parameter values — with its own table or collection, its own
(node, key, effective date) identity, and its own write history. The resolution walk
checks for absence entities in addition to value entities at each level. This is
conceptually clean but introduces a second entity type with the same identity structure,
effective-date semantics, supersession logic, and closed-period restriction as parameter
values. Every rule that governs parameter values must be duplicated or generalised to
cover absence entities. The unified sentinel approach achieves the same resolution
semantics with a single entity type and no duplication of governance rules. Rejected on
grounds of unnecessary structural complexity.