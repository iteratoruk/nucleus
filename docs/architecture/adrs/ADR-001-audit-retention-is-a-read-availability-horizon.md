# ADR-001: Audit retention is a read-availability horizon, not a purge boundary

**Date:** 2026-07-03
**Status:** Accepted

## Context

Story #18 asks that the amount of audit history available in a deployment be configurable — a full
week or more in production, an hour or two in development — without a code change. The request names
a "retention period" but does not say what a retention period *is*, and two readings of the term
diverge sharply enough to change the behaviour of the system and the meaning of the audit trail.

Under the first reading, the retention period is a **read-availability horizon**: a lens over a
trail that is itself retained in full. The period governs only how far back the read endpoint looks.
Narrowing it hides older events reversibly; widening it reveals them again. The trail remains
append-only and complete.

Under the second reading, the retention period is a **purge boundary**: events older than the period
are physically destroyed. Narrowing the period destroys the events outside it; widening it again
cannot restore them. The trail is a rolling window of what physically survives.

Three forces bear on the choice.

*The acceptance criteria of #18 already discriminate between the two.* The "Retention period extended
beyond the default" scenario configures a 30-day period and requires that an event recorded 20 days
ago is returned. Under a purge model that had been running the 7-day default, that event would
already have been destroyed and could not be returned by any later widening of the period. The
scenario is satisfiable only if the period never destroys events — that is, only under the
availability-window reading. The criteria therefore presuppose it.

*The audit trail carries an immutability invariant.* Audit events are facts about the past, of the
same character as ledger entries: recorded once, never altered, never removed by domain action. A
purge boundary sits in direct tension with that invariant; an availability horizon does not touch it.

*Otto and Ripley appear to conflict, and the reading decides whether they do.* Otto wants the freedom
to keep very little history in development; Ripley depends on a complete, immutable trail and, in
principle, on a regulatory retention floor. If retention destroys events, Otto's short development
horizon and Ripley's floor are in genuine collision. If retention only narrows a view, they are not
talking about the same thing and there is no collision.

## Decision

The audit retention period is a **read-availability horizon**: a non-destructive lens over a fully
retained, append-only trail. It defines the availability window `[now − horizon, now]` over which
recorded audit events are surfaced by the read model. It never removes events from the trail.

Consequently:

- Narrowing the horizon hides newly-excluded events from subsequent reads immediately and
  reversibly; widening it restores them. There is no destructive "applies going forward only"
  variant, because nothing is destroyed.
- An **absent** horizon configuration falls back to the seven-day default. A **present but invalid**
  configuration — zero, negative, or unparseable — is rejected loudly at deployment start rather than
  silently defaulted, because a silent substitution of intent is an operability failure in Otto's
  terms.
- The horizon has no configured maximum; its effect is bounded only by how much history the
  deployment has actually accumulated.
- Physical destruction of audit events — a **purge / archival policy** — is a separate concern, left
  unmodelled and deferred. Any regulatory **compliance minimum** constrains that future purge policy,
  never this horizon.

## Consequences

**Positive.**

- Story #18's acceptance criteria become coherent, the "extended beyond default" scenario in
  particular, which no purge reading can satisfy.
- The trail's append-only immutability invariant is preserved intact; retention becomes a read
  concern rather than a write-back mutation of history.
- Horizon changes are safe and reversible in any direction, removing the "immediate vs. going
  forward" ambiguity from #18 entirely.
- The Otto/Ripley tension dissolves: a short development horizon narrows a view without shortening
  what survives, so it cannot breach a compliance floor, which governs purge instead.
- An empty read result keeps a bounded, honest meaning — the events are still in the trail, merely
  out of view — rather than the irrecoverable ambiguity a purge model creates between a destroyed
  event and one that never occurred.

**Negative.**

- The trail grows without bound until a separate purge/archival policy is modelled. This is accepted
  deliberately: unbounded growth of an append-only evidential record is the conservative failure
  mode, and in a core banking context indefinite retention aligns with the compliance need rather
  than working against it.
- "Retention period" now denotes read availability rather than physical retention, which can mislead
  a reader who expects the word to mean deletion. The model and this ADR name the horizon explicitly
  to counter that.

**Risks.**

- If storage growth becomes operationally material before a purge policy is modelled, the deferral
  bites. The mitigation is to open the purge/archival architecture session when growth or a concrete
  regulatory obligation makes physical destruction a live concern — not before, so that the floor is
  designed against a real obligation rather than a guessed one.
- A future purge policy must be modelled so that it composes with this horizon without reintroducing
  the destroy-on-reduce semantics rejected here: purge removes only what a compliance floor permits,
  and the horizon continues to view whatever survives.

## Alternatives Considered

**Retention as a purge boundary.** Rejected. It cannot satisfy #18's "extended beyond default"
acceptance criterion, it stands in tension with the trail's immutability invariant, it makes horizon
reduction an irreversible destruction of history, it turns an empty read result into an irrecoverable
ambiguity, and it puts Otto's operational tuning into direct collision with Ripley's compliance
dependence. Its only advantage — bounding storage growth — is better served by a separate,
compliance-governed purge policy that this decision defers rather than forecloses.

**Fall back to the default on invalid configuration.** Rejected for the invalid case (retained for
the absent case). Silently substituting the seven-day default when an operator configured something
specific hides operator error, which is precisely the silent divergence between stated intent and
actual behaviour that Otto's persona forbids. Absence of configuration is a request for the default;
invalid configuration is a mistake that should surface at deployment start.