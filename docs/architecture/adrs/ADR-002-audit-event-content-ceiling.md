# ADR-002: Audit events are bound by a content ceiling

**Date:** 2026-07-03
**Status:** Accepted

## Context

An audit event exists under three conditions that, taken together, determine what it may safely
contain.

The **store is untrusted.** The persistence mechanism holding the audit trail is not assumed to
satisfy rigorous security constraints; the trail must be modelled as though its store could be read
by a party the system does not control.

The **reader is undifferentiated.** The read mechanism — the Spring Actuator surface in the current
implementation — enforces no roles or privileges and cannot filter what it surfaces by the authority
of the viewer. It is assumed reachable only by internal users such as Otto, but among those users it
draws no distinction: every reader sees the trail at the lowest common denominator of privilege.

The **record is immutable.** Once recorded, an audit event cannot be redacted, amended, or withdrawn.
A disclosure once made through the trail is permanent.

These three combine to a single conclusion: recording an audit event is equivalent, in
confidentiality terms, to publishing its contents permanently to the least-privileged reader. No
control exists that can walk such a disclosure back — not access control, because the reader has
none; not redaction, because the record is immutable; not the store, because the store is untrusted.
There is no downstream point at which an over-broad audit event can be made safe.

This bounds what an audit event may be, and it must be decided at the level of the domain model
rather than left to the discretion of each feature that records events, because a single breach is
permanent and cross-context: any feature context that populates an audit event with sensitive detail
publishes it irrevocably through a shared, unprivileged trail.

## Decision

An audit event is bound by a **content ceiling**: it may contain only information whose permanent
disclosure to the least-privileged reader is acceptable — no personally identifiable information, no
sensitive data, nothing whose exposure is a security or regulatory risk. The ceiling is part of the
definition of a well-formed audit event, not a handling rule applied to one after recording.

Consequently:

- An audit event records **references, not reproductions**: identifiers and keys that locate the
  occurrence (an account identifier, a node identifier, a correlation key), never the sensitive
  substance those references point to. The substance, where legitimately needed, is resolved through
  a properly access-controlled path outside the audit context.
- The ceiling is enforced **where content is authored** — inside each feature context that defines an
  audit event type and populates its detail — because there is no read-time control that could
  enforce it later. Access control at read time cannot serve as a fallback for over-broad content;
  there is none to fall back to.
- **Attribution is subject to the ceiling.** The principal is admissible only as a safe internal
  reference; an attribution that itself named sensitive substance would breach the ceiling as surely
  as sensitive detail would.
- The audit channel **cannot borrow the messaging channel's confidentiality mechanism.** Kafka can
  carry sensitive dimensions under confidentiality classifications and topic access controls; the
  audit channel, with an undifferentiated reader and an untrusted store, has only exclusion at the
  point of recording. A sensitive dimension of a happening may therefore be admissible on the Kafka
  channel and simultaneously inadmissible on the audit channel.
- The general audit trail is therefore **not, by itself, the complete compliance record** for
  sensitive matters. It records the fact and the non-sensitive dimensions of a sensitive occurrence;
  the sensitive substance belongs to a separately access-controlled compliance record, deferred as a
  future concern.

## Consequences

**Positive.**

- A permanent, irrecoverable class of disclosure is prevented by construction rather than by
  after-the-fact handling: because nothing sensitive enters the trail, no property of the store or
  the reader can leak it.
- The audit trail becomes safe to persist in an untrusted store and to read at the lowest privilege —
  which is exactly what the current implementation assumes — without a separate access-control
  mechanism.
- The distinction between the audit channel and the Kafka channel is sharpened usefully: it becomes
  clear which dimensions of a happening may travel on which channel, and why a sensitive happening
  may be recordable only where it can be protected.

**Negative.**

- The general audit trail cannot, alone, satisfy a compliance obligation that requires the sensitive
  basis of an action. A separate, access-controlled compliance record is implied for that substance,
  which is additional architecture to be modelled when such an obligation becomes live.
- Every feature context that defines audit event types now carries a content obligation it must
  honour when populating event detail. The audit context supplies the constraint but cannot police
  the substance a feature context puts into a record, so conformance rests with the authoring
  context.

**Risks.**

- The ceiling is authored, not mechanically enforced: a feature context can violate it by placing
  sensitive detail in an event, and because the store is untrusted and the record immutable, the
  breach is permanent the moment it is recorded. The mitigation is to treat the content of audit
  event detail as a first-class review concern in every context that records events, and to prefer
  references over substance as the default posture.
- "Sensitive" and "PII" are policy judgements that evolve; a datum benign today may become sensitive
  under a later regulation, while the records already written are immutable. The ceiling reduces
  exposure but cannot retroactively protect what was recorded under an earlier judgement — a further
  reason to keep event detail minimal and reference-based rather than descriptive.

## Alternatives Considered

**Rely on access control at read time.** Rejected: the read mechanism enforces no privileges and
cannot filter by viewer authority, so there is no read-time control to rely on. Introducing one would
be a new context boundary — differentiated readers over the audit trail — not a property of the
system as it stands, and even then it would not protect against the untrusted store.

**Rely on securing the store.** Rejected as an assumption: the store is explicitly not assumed to
meet rigorous security constraints, and immutability means a single lapse is permanent. Designing the
content of the record to be safe regardless of the store is the conservative posture, and it is the
only one that holds when the store cannot be trusted.

**Carry sensitive detail under confidentiality classifications, as the Kafka channel does.** Rejected
for the audit channel: classifications are only as good as the access controls that honour them, and
the audit reader is undifferentiated. Classification-plus-access-control is available to the messaging
channel and unavailable here, which is precisely why the audit channel's instrument must be exclusion
rather than classification.