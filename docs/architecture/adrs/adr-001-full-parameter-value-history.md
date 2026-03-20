# ADR-001: Full Parameter Value History

**Date:** 2026-03-20
**Status:** Accepted

## Context

The parameter value hierarchy governs account servicing behaviour in Nucleus. Configurers
(Sasha, Liam, Maya) register product configuration against classification code nodes and
expect Nucleus to honour that configuration autonomously throughout an account's lifetime,
including at servicing events — interest rate transitions, maturity handling, fee schedule
changes — that occur on specific future dates without re-instruction from the configurer.

Maya's rate transition use case makes the requirement concrete. A mortgage product may
carry a fixed introductory rate for two years, after which a successor rate applies.
Maya registers the successor rate at classification time, or at any point before the
transition date, by submitting a parameter value with a future effective date. Nucleus
must apply the successor rate on the transition date without Maya re-instructing it.
This pre-scheduling of a configuration change via a future effective date is the
mechanism by which Nucleus takes autonomous ownership of account lifecycle.

The same pattern applies across product domains: Liam's lending products carry interest
rates that may be scheduled to change; Sasha's fixed-term savings products have maturity
parameters set at issuance with a future effective date. In each case, the configurer's
intent is expressed once, at configuration time, and Nucleus executes against it at the
right moment.

The question this ADR resolves is whether a parameter node may hold multiple values for
the same key — one per effective date — or whether each key at a node may hold only its
most recently written value, with prior values lost on update.

## Decision

A parameter node holds a complete temporal history of values for each parameter key.
Multiple values for the same key may exist at a node, each carrying a distinct effective
date. A parameter value is identified by the triple (node, key, effective date).

The resolution function, given a resolution date, returns the value whose effective date
is the latest date on or before the resolution date. If no value for the key has an
effective date on or before the resolution date, the resolution walk continues to the
parent node.

Writing a new value for an existing (node, key, effective date) triple supersedes the
prior value for that triple. The superseded value is retained in the write audit trail
and no longer governs resolution. The write audit trail is distinct from the temporal
history: the history is the set of values at different effective dates; the audit trail
is the record of all writes to any given triple.

## Consequences

**Positive:**

- Future-dated parameter values are the mechanism by which scheduled configuration
  changes are pre-registered. Configurers submit their intent once; Nucleus executes
  against it autonomously at the correct time. No configurer re-instruction is required
  at the moment of a servicing event.
- The resolution function is temporally complete. It can answer "what was the applicable
  value on date D?" for any D, without loss of information. This supports both scheduled
  processing (which resolves against a past business date) and historical audit queries.
- Regulatory and audit queries about historical configuration state can be answered from
  the parameter record itself, without depending on application logs, snapshots, or
  out-of-band records.
- Independent effective dates per key allow configuration changes to different parameters
  to be scheduled at different times without coupling. An interest rate change effective
  in April and a fee schedule change effective in June can be pre-registered in the same
  node simultaneously.

**Negative:**

- Resolution logic is more complex than a simple key lookup. For each key at each node
  level, the function must find the latest qualifying effective date on or before the
  resolution date, rather than reading a single current value.
- The write model must treat the triple (node, key, effective date) as the identity of
  a parameter value, not the pair (node, key). This affects how writes are validated
  and how supersession is managed.
- There is no semantically simple "give me all current values for this node" flat read.
  Any such query must specify or default a resolution date, because "current" is only
  meaningful relative to a point in time.
- Storage grows over time as history accumulates. For parameters that change infrequently
  (which is the common case for product configuration), growth is slow. It is not
  self-limiting, however, and a long-lived node will accumulate history indefinitely
  without a compaction or archival policy.

**Risks:**

- If scheduled processing jobs resolve parameter values against wall-clock time rather
  than the business date being processed, future-dated values may be applied earlier
  than intended, or late-running jobs may resolve against the wrong date. This risk is
  addressed by the domain invariant stated in the parameter value hierarchy model: the
  business date is always the explicit resolution date in scheduled processing contexts.
  It is not mitigated by this ADR; it must be enforced by the processing context.
- A node with a parameter key that changes very frequently — for example, a tracker rate
  linked to an external reference rate updated monthly over a 25-year mortgage term —
  may accumulate hundreds of historical values for a single key. Resolution queries that
  scan this history must be efficiently supported. This is a storage and indexing concern,
  not a domain concern, but it should be considered when the parameter store is designed.

## Alternatives Considered

**Single current value (overwrite on update).** Each (node, key) pair holds only its most
recently written value. A write replaces the prior value; the prior value is not retained.
This is the simplest possible model. It is incompatible with the pre-scheduling
requirement: if Maya writes a future rate and then a system process resolves the key
before the effective date, there is no prior value to return — the future rate would be
applied immediately, or the write would need to be deferred, which transfers the
scheduling responsibility back to the configurer. The configurer relationship requires
that Nucleus honour future-dated configuration autonomously. This alternative forecloses
that requirement entirely. Rejected.

**Node-level snapshots (full-node versioning).** Each PUT to a node creates a new version
of the complete node configuration, with a single effective date for the entire snapshot.
The resolution function returns the snapshot effective as at the resolution date. This
model supports future-dating, but it requires that every configuration change re-submit
all keys — a change to one key forces a full node snapshot. More significantly, it
prevents two keys at the same node from having independent effective dates: an interest
rate change effective in April and a fee schedule change effective in June cannot both be
in flight simultaneously within the same node, because any snapshot can carry only one
effective date. Maya's use case, and others like it, require per-key effective dates.
Rejected on grounds of insufficient granularity.