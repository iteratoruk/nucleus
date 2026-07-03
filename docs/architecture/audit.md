# Domain Model: Audit

## Bounded Context

The Audit context owns the recording and reading of *audit events*: immutable records that
significant business and operational occurrences happened in Nucleus. Its responsibility is
evidential. It exists so that a party who was not present at the moment an occurrence took place —
Otto investigating an incident hours later, Ripley conducting a regulatory examination weeks later —
can establish, without reconstructing state from the operational database, whether and when that
occurrence was recorded.

What this context owns: the notion of an audit event as an immutable fact; the audit trail as the
append-only accumulation of those facts; the shared vocabulary through which every other context
contributes its own event kinds; and the terms on which the trail is read back — filtered by type,
bounded by time, surfaced within a configurable availability horizon.

What this context does not own: the occurrences themselves. An account opening happens in the
Accounts context; a parameter value is superseded in the parameter-value context; a scheduled task
starts and finishes in the Scheduling context. The Audit context never causes any of these. It
holds the *record that they happened*, which is a different thing from the happening, and the
distinction between the two is the load-bearing idea in everything below. It also does not own the
classification of what is worth auditing: each context decides which of its own occurrences are
significant and names them. Audit supplies the form of the record, not the judgement of
significance.

## Ubiquitous Language

**Occurrence** (also *business event*, *operational event*). Something that happened in a feature
context and is complete as a fact the moment it happens: an account was opened, a node was created,
a scheduled task finished. An occurrence is not part of the Audit context. It is the *referent* of
an audit event.

**Audit event** (also *audit record*). The immutable record that an occurrence happened. It is
evidence *of* the occurrence, not the occurrence itself. An audit event is a fact about the past
that, once recorded, is never altered and never contradicted — only, at most, followed by further
audit events. Every audit event carries: its type, its principal, its recording time, and the
detail of what occurred.

**Audit trail.** The append-only accumulation of all audit events ever recorded in a deployment.
The trail only ever grows. Nothing in the domain updates or removes an entry once written; this is
an invariant of the same character as the immutability of ledger entries, not a storage policy.

**Audit event type.** The classification of an audit event — `ACCOUNT_OPENED`, `NODE_CREATED`,
`ScheduledTaskStarted`, and so on. The *vocabulary* of types is not owned centrally: each feature
context defines and owns the types of the occurrences it records. The Audit context owns only the
shared contract that makes a type recordable and readable, not the enumeration of types itself.

**Principal** (also *actor*, *author*). The party to whom the occurrence is attributed — carried as
the client identity on the originating request. It is an attribute (a value) of the audit event,
not a separate entity within this context. Filtering the trail by principal is a dimension of the
read model, alongside filtering by type and by recording time.

**Recording time** (also *record time*). The instant at which the trail recorded the audit event.
This is the trail's ordering coordinate and the coordinate the read model filters on: when the
stories say an event was "recorded two days ago" or "recorded 6 days ago", they mean recording time.

**Occurrence time** (also *event time*). The instant at which the underlying occurrence happened. In
Nucleus's current synchronous recording, occurrence time and recording time coincide closely enough
to be treated as one; the domain nonetheless holds them apart, because they *can* diverge (a record
written after the fact, or an occurrence backdated) and the trail must never let a divergence
silently reorder history. Where they diverge, recording time governs the trail's ordering and its
read window; occurrence time, if it matters to a reader, is detail carried within the event.

**Read-availability horizon** (the stories' *retention period*). The interval, ending at the
present, over which recorded audit events are surfaced to a reader. It is a *lens over a retained
trail*, not a boundary that destroys events: narrowing it hides older events from the read model
without unmaking them, and widening it reveals them again. The stories name this the "retention
period"; the model keeps that label as a synonym but resolves its meaning to an availability window
(see [ADR-001](adrs/ADR-001-audit-retention-is-a-read-availability-horizon.md)), because that is the
only reading under which the stories' own acceptance criteria are coherent.

**Availability window.** The concrete interval `[now − horizon, now]`. An audit event is *available*
when it is present in the trail and its recording time falls within this window.

**Purge / archival policy.** The physical destruction or relocation of recorded audit events — a
*distinct* concern from the availability horizon, governed by compliance rather than by operational
convenience, and not addressed by the stories in scope. The horizon decides what a reader sees;
purge would decide what physically survives. The model names purge only to hold it firmly apart
from the horizon.

**Compliance minimum.** A regulatory floor on how long audit events must physically survive. Because
it constrains *purge*, and the horizon destroys nothing, the compliance minimum does not bound how
short Otto may set a deployment's horizon. This is the resolution of the Otto/Ripley tension
recorded below.

**Untrusted store.** The premise that the persistence mechanism holding the audit trail is not
assumed to satisfy rigorous security constraints. The trail is modelled as though its store could be
read by a party the system does not control.

**Undifferentiated reader.** The premise that the read mechanism enforces no roles or privileges and
cannot filter what it surfaces by the authority of the viewer. Every reader sees the trail at the
lowest common denominator of privilege.

**Content ceiling** (also *disclosure ceiling*). The invariant that an audit event may contain only
information whose permanent disclosure, to the least-privileged reader, is acceptable — no personally
identifiable information, no sensitive data, nothing whose exposure is a security or regulatory risk.
The ceiling is a property of what an audit event *is*, not a handling rule applied afterwards. Recorded
in [ADR-002](adrs/ADR-002-audit-event-content-ceiling.md).

## Aggregates

### Audit Event

The audit event is the aggregate, and it is a small one: each recorded event is its own aggregate
root with no child entities. There is deliberately no "audit trail aggregate" enclosing them,
because no invariant spans two audit events. Each is recorded independently and completely; the
trail is their set, not a consistency boundary over them. This matters for retention: the horizon
operates over the set, never within the boundary of any single event, so it can never violate an
event's integrity.

**Identity.** An audit event has surrogate identity, assigned at the moment of recording. Two audit
events are never the same event, even when they record the same occurrence: each recording is its
own distinct fact. An audit event is therefore an entity, not a value object — value equality is not
a meaningful question to ask of it. (Contrast a monetary amount, where £100.00 GBP is £100.00 GBP
irrespective of which object holds it. Two audit events with identical contents are still two facts:
that the trail recorded the occurrence twice is itself information, not a duplicate to be collapsed.)

**Invariants.**

- *Immutability.* Once recorded, an audit event's type, principal, recording time, and detail never
  change. A correction to an occurrence is a new occurrence with its own audit event, never an edit
  to an existing record — the same discipline as ledger corrections.
- *Append-only trail.* The trail admits insertions only. No domain operation updates or removes a
  recorded event. The read-availability horizon is not an exception to this: it changes what a
  reader *sees*, not what the trail *contains*.
- *Monotonic recording order.* Recording time is assigned by the trail and orders the trail. An
  event's place in the recorded order is fixed at recording and never revised, even if its
  occurrence time is earlier than that of events already recorded.
- *Content ceiling.* An audit event contains only information whose permanent disclosure to the
  least-privileged reader is acceptable — no PII, no sensitive data. This invariant is developed in
  its own section below and recorded in
  [ADR-002](adrs/ADR-002-audit-event-content-ceiling.md); it is stated here because it is a
  condition of a well-formed audit event, not a policy layered over one.

**Entities within this aggregate.** None. The audit event is atomic.

**Value objects.** The audit event type (a classification drawn from the owning context's
vocabulary); the principal (the attributed client identity); recording time and, where carried,
occurrence time (instants); and the occurrence detail (the structured description of what happened).
Every one of these is bound by the content ceiling: the occurrence detail carries references to the
occurrence, not a reproduction of its sensitive substance, and even the principal is admissible only
because a client identity is a safe internal reference.

**Domain events produced.** The audit event has exactly one lifecycle moment — its recording — and
no others, because it is immutable and never removed. The act of recording *is* the event; there is
no separate "audited" notification, and deliberately no meta-audit of the audit (which would regress
without end). The audit trail is a terminus for domain events from other contexts, not a source of
new ones.

**Domain events consumed.** Every significant occurrence that any feature context elects to record.
The Audit context is a pure downstream consumer here: it receives occurrences and materialises them
as audit events. It never decides which occurrences are significant — that judgement belongs to the
originating context.

## The Read Model

To read the audit trail is to ask an evidential question: *did this kind of thing happen, and
when?* The read model answers it as a query over the trail — filtered by audit event type, by
principal, and by a recording-time lower bound, and clipped to the availability window. There is no separate read store in the
domain; the trail is its own read model. An implementation may materialise projections, but the
domain requires none, and none changes the meaning of a read.

Two distinctions govern how a reader must interpret a result.

**Evidence, not occurrence.** The presence of an `ACCOUNT_OPENED` audit event is evidence that an
account was opened; it is not the opening. Otto reads records, never occurrences. This is usually a
pedantic point and occasionally a critical one: the reliability of the evidence is exactly the
reliability of the recording discipline, and no better.

**The meaning of emptiness.** An empty result is a valid answer, not an error — story #17 is right
to insist on this. But its meaning is bounded. Within the availability window, and for an event type
that the originating context does record, an empty result is reliable evidence that no such
occurrence was recorded. Outside the window, or for an occurrence that was never audited in the
first place, emptiness is *inconclusive*: it means "not recorded and visible here", which is not the
same as "did not happen". The read model owes the reader a result; it cannot owe them the assurance
that absence-from-window equals absence-from-history. Under the availability-window resolution this
subtlety is contained — the events are still in the trail, merely out of view — whereas a purge
model would make emptiness genuinely ambiguous, because a purged event and an event that never
occurred are indistinguishable to a reader. This is a further reason the model resolves to an
availability window.

## The Content Ceiling: What an Audit Event May Contain

Three facts about the conditions under which an audit event exists combine to bound what it may
contain.

The first is the **untrusted store**: the persistence mechanism holding the trail is not assumed to
satisfy rigorous security constraints, so the trail must be modelled as though its store could be
read by a party the system does not control. The second is the **undifferentiated reader**: the read
mechanism enforces no roles or privileges and cannot filter what it surfaces by the authority of the
viewer, so every reader sees the trail at the lowest common denominator of privilege. The third is
the audit event's own **immutability**: once recorded it cannot be redacted, amended, or withdrawn,
so a disclosure once made is permanent.

Taken together, these three make recording an audit event equivalent, in confidentiality terms, to
*publishing its contents permanently to the least-privileged reader*. No control exists that can
walk such a disclosure back: not access control, because the reader has none; not redaction, because
the record is immutable; not the store, because the store is untrusted. There is no downstream point
at which an over-broad audit event can be made safe.

The invariant follows directly. **An audit event may contain only information whose permanent
disclosure to the least-privileged reader is acceptable — no personally identifiable information, no
sensitive data, nothing whose exposure is a security or regulatory risk.** This is the *content
ceiling*, and it is part of the definition of a well-formed audit event, not a handling rule applied
to one after the fact. The bank's judgement of what is "acceptable to disclose" is a policy input;
the architectural ceiling — that the audit event's content may not exceed what is safe in an
untrusted store read at lowest privilege — is firm. Recorded in
[ADR-002](adrs/ADR-002-audit-event-content-ceiling.md).

Four consequences shape the domain.

**Reference, not reproduction.** An audit event records *that* an occurrence happened and enough to
identify *which* occurrence: it carries references and identifiers that are safe to disclose — an
account identifier, a node identifier, a correlation key — not the sensitive substance those
references point to. The substance, where a reader legitimately needs it, is resolved through a
properly access-controlled path outside this context. This is the thing-versus-record-of-a-thing
distinction made load-bearing: the audit event *points at* the occurrence, it does not *embody* it,
and here a record of a thing must not reproduce the thing.

**Audit cannot borrow the messaging channel's confidentiality mechanism.** The Kafka channel can
carry sensitive dimensions on events and rely on structured confidentiality classifications plus
topic access controls to route them only to authorised subscribers — this is how Ripley's tipping-off
constraint is discharged on that channel. The audit channel has no equivalent instrument: its reader
is undifferentiated and its store untrusted, so classification-plus-access-control is unavailable to
it. Audit's *only* instrument is exclusion at the point of recording. This sharpens the standing "a
happening is either an audit event or a Kafka message, choose one channel" principle: a sensitive
dimension of a happening may be admissible on the Kafka channel and simultaneously inadmissible on
the audit channel, and where both a record and a notification are wanted, the sensitive substance
may travel only on the channel that can protect it.

**The content ceiling is the only read-side control there is.** Because the read mechanism enforces
no privilege filtering, access control at read time cannot serve as a fallback for over-broad
content — there is no access control at read time to fall back to. The ceiling must therefore be
enforced where content is *authored*: in each feature context that defines an audit event type and
populates its detail. It cannot be enforced at the read boundary, and there is no later boundary that
could enforce it.

**Attribution is subject to the ceiling too.** The principal is admissible only because a client
identity is a safe internal reference. An attribution that itself named sensitive substance — that
identified a specific human as the subject of a financial-crime investigation, say — would breach the
ceiling exactly as sensitive detail would.

There is a genuine tension with compliance that this ceiling exposes rather than resolves. Ripley
wants a complete auditable record of every restriction applied and lifted, including *on what basis*
it was applied; for a financial-crime restriction the basis is precisely the sensitive substance the
ceiling excludes, and the fact that an account is under such a restriction may itself be sensitive.
The direction of resolution is that the general audit trail records the *fact and the non-sensitive
dimensions* — that a restriction of a stated (or, where the type itself is sensitive, deliberately
generic) kind was applied to account X at time T by principal P — while the sensitive basis lives in
a properly access-controlled compliance record outside this context. The consequence to state
plainly is that **the general audit trail is not, by itself, the complete compliance record for
sensitive matters.** That opens a compliance-record concern which the stories in scope do not, and it
is carried below as an open question rather than resolved here.

## Retention: The Crux

Story #18 asks that the available history be configurable per deployment. Beneath that request sits
a decision the request does not name: *what is a retention period?* Two readings are possible, and
they diverge sharply.

**Retention as availability window.** The period is a lens over a retained trail. Events are kept;
the period governs only how far back the read endpoint looks. Narrowing the period hides older
events immediately and reversibly; widening it reveals them again. The trail stays append-only and
complete. "Retention" here is really *read-availability horizon*.

**Retention as purge boundary.** The period is a destruction boundary. Events older than it are
physically removed. Narrowing the period destroys the events that fall outside it; once destroyed,
widening the period again cannot bring them back. The trail is a rolling window of what physically
survives.

The two readings are not equally compatible with story #18's own acceptance criteria. The criteria
are written entirely in terms of what a read returns, and one of them settles the matter: *"Retention
period extended beyond the default — given the period is configured as 30 days, and an event was
recorded 20 days ago, then the result contains the event recorded 20 days ago."* Under a purge model
running the 7-day default, an event recorded 20 days ago would already have been destroyed, and no
later widening of the period could return it; the scenario would be unsatisfiable. It is satisfiable
only if events are never destroyed by the period — that is, only under the availability-window
reading. The acceptance criteria therefore already presuppose retention-as-availability-window; the
open question was answered, implicitly, the moment those scenarios were written.

**Resolved position: retention is a read-availability horizon.** Recorded in
[ADR-001](adrs/ADR-001-audit-retention-is-a-read-availability-horizon.md). The horizon is a
non-destructive lens; the trail remains append-only and complete; narrowing and widening are both
safe and reversible. This resolution also cleanly dissolves the open questions that surround #18:

- *Semantics on reduction (immediate, or going-forward only?).* The dichotomy was an artefact of the
  purge reading. Under an availability window, a reduction applies immediately to the *view* and
  never to the *trail*: the newly-excluded events are hidden, not removed, and a later extension
  restores them. There is no destructive "going forward" variant to choose, because nothing is
  destroyed. **Resolved:** reduction takes effect immediately on subsequent reads, non-destructively.

- *Deployment-time or runtime configuration?* Because every change to the horizon is safe and
  reversible, nothing in the domain forbids changing it while the deployment runs. Equally, nothing
  in the domain *requires* runtime mutability. The phrase "per deployment" is satisfied by a
  deployment-scoped configuration value; "extended at times" in production is a safe operation
  whenever the value is read. **Resolved:** the horizon is deployment-scoped configuration; the
  domain does not mandate runtime adjustment, and the availability-window model makes runtime
  adjustment carry no domain risk should it later be wanted.

- *Invalid period (zero, negative, unparseable)?* Absence of configuration and invalidity of
  configuration are different situations and deserve different treatments. Absence is a request for
  the default; the "not configured" scenario says so — absent means seven days. Invalidity is an
  operator error, and Otto's world does not tolerate silently swallowed operator errors: a
  deployment that quietly substituted a default when the operator asked for something specific would
  be an operability trap, exactly the kind of silent divergence between intent and behaviour that
  the persona exists to forbid. **Resolved:** an absent horizon falls back to the seven-day default;
  a present-but-invalid horizon (zero, negative, unparseable) is rejected loudly at deployment
  start rather than defaulted.

- *Is there a maximum?* The horizon cannot surface events that were never recorded, so its effect is
  already bounded by how much history the deployment has actually accumulated. A ten-year horizon on
  a three-day-old deployment returns three days of events. **Resolved:** the domain imposes no
  maximum; the horizon is bounded in effect by the retained history, not by a configured ceiling.

## The Otto / Ripley Tension

The sharpest cross-persona question was whether Otto's operational freedom to keep only "an hour or
two" of history in a development environment collides with Ripley's compliance dependence on a
complete, immutable, examinable trail — whether a short horizon breaches a regulatory retention
floor.

Under the availability-window resolution, the tension does not arise, because Otto and Ripley are
not talking about the same thing. Otto sets a *horizon* — a read lens that destroys nothing. Ripley
depends on the trail's *completeness and survival* — a property of the trail itself, and, where a
regulatory floor applies, of the *purge policy* that would one day remove events from it. A short
horizon in development narrows what Otto sees; it does not shorten what survives. Otto may therefore
set the horizon as short as operational convenience wants, in any environment, without touching
Ripley's concern.

The compliance minimum is real, but it constrains a concern the stories in scope do not open: the
purge/archival policy, which decides what physically survives and is currently *unmodelled and
deliberately deferred*. Until a purge policy exists, the trail simply grows, which serves Ripley's
completeness need without qualification. When a purge policy is modelled, the compliance minimum
becomes its governing floor — and at that point it will bound *purge*, still not the horizon. This
keeps the compliance-floor question off the critical path for #17 and #18 entirely.

## Context Relationships

**Feature contexts → Audit (open-host / published language; conformist).** Every feature context is
an upstream supplier of occurrences and a conformist to a published contract. The Audit context
publishes the shared form of an audit event and the interface by which a context names its own event
types; each feature context conforms to that form and supplies both its own type vocabulary and its
own event instances. The dependency runs one way at the level of the shared contract — the Audit
context depends on no feature context, and knows nothing of accounts, nodes, or scheduled tasks —
which is what lets audit remain a stable, feature-agnostic kernel. This one-way dependency is a
domain-organisational commitment, enforced structurally, not merely a coding convention. The
published contract is not only structural: conforming to it means conforming to the *content ceiling*
as well as to the form of an event. Because the ceiling can be enforced only where content is
authored — inside the feature context — each feature context is responsible for keeping the detail of
its own audit events within it. The Audit context supplies the form and the constraint; it cannot
police the substance a feature context puts into a record.

**Audit → Otto / Operations (customer / supplier; Otto is customer).** The operational read endpoint
is the supplied capability; Otto is the customer whose incident-investigation need shapes it. The
read model's obligations — filterability by type and recording time, a truthful empty result, an
availability horizon tuned per deployment — are this relationship expressed as requirements.

**Audit → Compliance / Ripley and Eddie (customer / supplier; examination as the driving need).**
Ripley and Eddie are downstream customers of the trail for regulatory examination. Their need —
completeness, immutability, structure sufficient for examination — is an upstream *constraint* on
the Audit context's invariants: the append-only and immutability invariants exist in part to
discharge it. Ripley's compliance-minimum concern attaches, when it becomes live, to the deferred
purge policy rather than to the read model. The content ceiling qualifies this relationship: the
general audit trail can supply Ripley the *fact* of a sensitive occurrence and its non-sensitive
dimensions, but not its sensitive substance, which must reside in a separately access-controlled
compliance record. Where a compliance obligation requires that substance, the general trail is a
partial supplier and the access-controlled record is the complete one.

## Open Questions

The domain questions that blocked stories #17 and #18 are resolved above and, where a resolution
forecloses a reasonable alternative, recorded as an ADR. What remains open is genuinely out of scope
for those stories.

- **ADR-001 (accepted) — audit retention is a read-availability horizon, not a purge boundary.**
  The decision that unblocks #18; it forecloses the purge reading. The purge/archival policy and its
  compliance floor are deliberately postponed until Otto's storage-cost concern becomes material and
  Ripley's regulatory-conformance requirements are established, at which point they are taken up as
  the future concern recorded below.

- **ADR-002 (accepted) — audit events are bound by a content ceiling.** It forecloses ever placing
  PII or sensitive data in the audit trail and establishes exclusion-at-recording as the only
  available control. It is a standing constraint on every feature context that defines an audit event
  type, orthogonal to the read and retention behaviour of #17 and #18, so it does not block either
  story from entering TDD; it governs what any event those stories read may contain.

- **Filtering the read model by principal (#17) — resolved, in scope.** The principal is an
  admissible attribute of every audit event (a safe internal reference under the content ceiling),
  so filtering by it introduces no new domain concern and is ruled a dimension of the read model
  alongside type and recording time. #17's out-of-scope exclusion of principal filtering is
  withdrawn; the story author may add a corresponding scenario.

- **Read-time access control on the operational endpoint (#17).** Out of scope for #17, and the
  content ceiling (ADR-002) is what stands in its place: because the endpoint enforces no
  privilege filtering, the trail is kept safe to read by excluding sensitive content at recording,
  not by restricting who may read. Should a future story introduce differentiated readers, the
  ceiling could be relaxed for higher-privilege views — but that is a new context boundary, not a
  change to these stories.

- **The access-controlled compliance record for sensitive substance (future).** Exposed by the
  content ceiling: the sensitive basis of a restriction or investigation cannot live in the general
  audit trail, so a separately access-controlled compliance record is implied for it. This is a
  future architecture concern tied to Ripley; it is explicitly *not* required to implement #17 or
  #18, which read the general trail only.

- **The purge / archival policy and its compliance minimum (future).** Unmodelled and deliberately
  deferred. This is the future ADR candidate that will govern what physically survives the trail and
  encode the regulatory floor. It is explicitly *not* required to implement #17 or #18: under
  ADR-001 the trail grows and nothing is destroyed, so both stories stand without it. It should be
  opened as its own architecture session when a deployment's storage growth or a concrete regulatory
  obligation makes physical purge a live concern — at which point the compliance minimum bounds the
  purge boundary, never the availability horizon.